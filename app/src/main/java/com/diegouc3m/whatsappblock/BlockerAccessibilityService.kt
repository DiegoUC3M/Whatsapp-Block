package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppBlocker"

        /**
         * Known resource IDs for the contact name in WhatsApp's conversation toolbar.
         * This is the TextView that shows the contact/group name at the top of a chat.
         */
        private val CONVERSATION_TITLE_VIEW_IDS = setOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )

        /** Cooldown to prevent rapid repeated back actions causing a loop. */
        private const val BACK_ACTION_COOLDOWN_MS = 800L

        /** How often the quota session ticker accumulates usage while a quota chat is open. */
        private const val QUOTA_TICK_MS = 5_000L
    }

    private var cachedBlockedContacts: Set<String> = emptySet()
    private var cachedAvatarHashesByContact: Map<String, Set<String>> = emptyMap()
    private var cachedHasAnyAvatarHashes: Boolean = false
    private var lastBackActionTime: Long = 0L
    private val avatarMatcher = AvatarMatcher()

    private var quotaSessionContact: String? = null
    private var quotaSessionLastTick: Long = 0L
    private val quotaHandler = Handler(Looper.getMainLooper())
    private val quotaTicker = object : Runnable {
        override fun run() {
            tickQuotaSession()
            if (quotaSessionContact != null) {
                quotaHandler.postDelayed(this, QUOTA_TICK_MS)
            }
        }
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cachedBlockedContacts = BlockedContactsRepository.getBlockedContacts(applicationContext)
            cachedAvatarHashesByContact = BlockedContactsRepository.getBlockedContactsAvatarHashes(applicationContext)
            cachedHasAnyAvatarHashes = cachedAvatarHashesByContact.values.any { it.isNotEmpty() }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cachedBlockedContacts = BlockedContactsRepository.getBlockedContacts(applicationContext)
        cachedAvatarHashesByContact = BlockedContactsRepository.getBlockedContactsAvatarHashes(applicationContext)
        cachedHasAnyAvatarHashes = cachedAvatarHashesByContact.values.any { it.isNotEmpty() }
        BlockedContactsRepository.registerListener(applicationContext, prefsListener)
    }

    override fun onDestroy() {
        endQuotaSession()
        BlockedContactsRepository.unregisterListener(applicationContext, prefsListener)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WhatsAppPackages.ALL) return
        if (cachedBlockedContacts.isEmpty()) return

        // React to window state changes (opening a chat) and content changes (re-entering a chat)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            val fallbackByName = findBlockedContactInChat(root)
            val pendingEnrollment = BlockedContactsRepository.getPendingAvatarEnrollmentContact(applicationContext)
            val shouldAttemptAvatar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (pendingEnrollment != null || cachedHasAnyAvatarHashes)

            if (!shouldAttemptAvatar) {
                maybeBlockContact(fallbackByName, "name")
                return
            }

            val started = avatarMatcher.captureAvatarHash(this, root) { observedHash ->
                runOnMainThread {
                    if (!observedHash.isNullOrBlank()) {
                        enrollPendingContactAvatarHash(pendingEnrollment, observedHash)
                    }

                    val avatarMatch = observedHash?.let {
                        avatarMatcher.findBestMatch(it, cachedAvatarHashesByContact)
                    }
                    if (avatarMatch != null) {
                        Log.d(
                            TAG,
                            "Avatar match: ${avatarMatch.contact} (distance=${avatarMatch.distance}, hash=${avatarMatch.observedHash})"
                        )
                    }
                    val contactToBlock = avatarMatch?.contact ?: fallbackByName
                    val reason = if (avatarMatch != null) "avatar" else "name"
                    maybeBlockContact(contactToBlock, reason)
                }
            }

            if (!started) {
                maybeBlockContact(fallbackByName, "name")
            }
        } finally {
            root.recycle()
        }
    }

    private fun enrollPendingContactAvatarHash(pendingContact: String?, hash: String) {
        val contact = pendingContact ?: return
        val blockedContacts = BlockedContactsRepository.getBlockedContacts(applicationContext)
        if (blockedContacts.none { it.equals(contact, ignoreCase = true) }) {
            BlockedContactsRepository.setPendingAvatarEnrollmentContact(applicationContext, null)
            return
        }
        val added = BlockedContactsRepository.addContactAvatarHash(applicationContext, contact, hash)
        if (added) {
            Log.d(TAG, "Enrolled avatar hash for $contact: $hash")
            cachedAvatarHashesByContact = BlockedContactsRepository.getBlockedContactsAvatarHashes(applicationContext)
            cachedHasAnyAvatarHashes = cachedAvatarHashesByContact.values.any { it.isNotEmpty() }
        }
        BlockedContactsRepository.setPendingAvatarEnrollmentContact(applicationContext, null)
    }

    private fun maybeBlockContact(contact: String?, reason: String) {
        val blockingEnabled = contact != null &&
            BlockedContactsRepository.isContactBlockingEnabled(applicationContext, contact)
        if (contact == null || contact != quotaSessionContact || !blockingEnabled) {
            endQuotaSession()
        }
        if (contact == null || !blockingEnabled) return
        val blockedContact = contact

        when (BlockedContactsRepository.getContactBlockMode(applicationContext, blockedContact)) {
            BlockMode.SCHEDULE -> {
                endQuotaSession()
                if (!BlockedContactsRepository.isWithinScheduleForContact(applicationContext, blockedContact)) return
                performBlockBack(blockedContact, reason)
            }
            BlockMode.QUOTA -> {
                startQuotaSession(blockedContact)
                flushQuotaUsage(blockedContact)
                if (BlockedContactsRepository.isContactQuotaExceeded(applicationContext, blockedContact)) {
                    endQuotaSession()
                    performBlockBack(blockedContact, "$reason/quota")
                }
            }
        }
    }

    private fun performBlockBack(blockedContact: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBackActionTime > BACK_ACTION_COOLDOWN_MS) {
            Log.d(TAG, "Blocked contact chat detected via $reason: $blockedContact — navigating back")
            lastBackActionTime = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // --- Quota session tracking ---

    /** Starts counting chat time for a quota-mode contact whose chat is currently open. */
    private fun startQuotaSession(contact: String) {
        if (quotaSessionContact == contact) return
        endQuotaSession()
        quotaSessionContact = contact
        quotaSessionLastTick = System.currentTimeMillis()
        quotaHandler.postDelayed(quotaTicker, QUOTA_TICK_MS)
    }

    /** Persists any pending chat time and stops the session ticker. */
    private fun endQuotaSession() {
        val contact = quotaSessionContact ?: return
        quotaSessionContact = null
        quotaHandler.removeCallbacks(quotaTicker)
        flushQuotaUsage(contact)
    }

    /**
     * Adds the time elapsed since the last tick to the contact's hourly usage counter.
     * If the clock hour changed since the last tick, only the portion of the elapsed
     * time that falls within the current hour is counted, so the reset at the hour
     * change (e.g. 19:59 → 20:00) stays accurate.
     */
    private fun flushQuotaUsage(contact: String) {
        val now = System.currentTimeMillis()
        val last = quotaSessionLastTick
        quotaSessionLastTick = now
        val delta = now - maxOf(last, startOfCurrentHourMillis())
        if (delta > 0) {
            BlockedContactsRepository.addContactQuotaUsage(applicationContext, contact, delta)
        }
    }

    private fun startOfCurrentHourMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Periodic check while a quota chat is open: verifies the chat is still in the
     * foreground, accumulates usage, and blocks once the hourly quota is spent.
     */
    private fun tickQuotaSession() {
        val contact = quotaSessionContact ?: return

        val root = rootInActiveWindow
        val stillOpen = if (root == null) {
            false
        } else {
            try {
                val pkg = root.packageName?.toString()
                pkg != null && pkg in WhatsAppPackages.ALL &&
                    findBlockedContactInChat(root) == contact
            } finally {
                root.recycle()
            }
        }

        if (!stillOpen) {
            endQuotaSession()
            return
        }

        flushQuotaUsage(contact)
        if (BlockedContactsRepository.isContactQuotaExceeded(applicationContext, contact)) {
            endQuotaSession()
            performBlockBack(contact, "quota")
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    /**
     * Finds a blocked contact name in the current chat screen.
     * Returns the matched contact name or null if not in a blocked chat.
     */
    private fun findBlockedContactInChat(root: AccessibilityNodeInfo): String? {
        // Strategy 1: Look for the known conversation_contact_name view ID
        for (viewId in CONVERSATION_TITLE_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes != null) {
                for (node in nodes) {
                    try {
                        val text = node.text?.toString()
                        if (!text.isNullOrBlank()) {
                            val matched = findMatchingBlockedContact(text)
                            if (matched != null) return matched
                        }
                    } finally {
                        node.recycle()
                    }
                }
            }
        }

        // Strategy 2: Fallback — look for a toolbar-like container
        return findBlockedContactInHeader(root)
    }

    /**
     * Fallback detection: looks for the contact name in what appears to be
     * a conversation header/toolbar area.
     */
    private fun findBlockedContactInHeader(root: AccessibilityNodeInfo): String? {
        val actionBarIds = listOf(
            "com.whatsapp:id/action_bar",
            "com.whatsapp.w4b:id/action_bar",
            "com.whatsapp:id/toolbar",
            "com.whatsapp.w4b:id/toolbar"
        )

        for (barId in actionBarIds) {
            val bars = root.findAccessibilityNodeInfosByViewId(barId)
            if (bars != null) {
                for (bar in bars) {
                    try {
                        val matched = findBlockedNameInToolbar(bar)
                        if (matched != null) return matched
                    } finally {
                        bar.recycle()
                    }
                }
            }
        }
        return null
    }

    /**
     * Searches only within a toolbar/action bar node for a blocked contact name.
     */
    private fun findBlockedNameInToolbar(node: AccessibilityNodeInfo?): String? {
        node ?: return null

        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            val matched = findMatchingBlockedContact(text)
            if (matched != null) return matched
        }

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            val matched = findMatchingBlockedContact(desc)
            if (matched != null) return matched
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val matched = findBlockedNameInToolbar(child)
                if (matched != null) return matched
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun findMatchingBlockedContact(text: String): String? {
        return cachedBlockedContacts.firstOrNull { text.equals(it, ignoreCase = true) }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }
}
