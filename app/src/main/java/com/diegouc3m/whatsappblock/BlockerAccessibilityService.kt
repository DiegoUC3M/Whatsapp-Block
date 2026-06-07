package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        /**
         * How long (after the user arms avatar enrollment and opens the chat) we keep
         * trying to capture the avatar before giving up. While enrolling, blocking is
         * suppressed for that contact so the screenshot can be taken; bounding the window
         * guarantees we don't leave the contact permanently unblocked if capture fails.
         */
        private const val ENROLLMENT_WINDOW_MS = 20_000L
    }

    private var cachedBlockedContacts: Set<String> = emptySet()
    private var cachedAvatarHashesByContact: Map<String, Set<String>> = emptyMap()
    private var cachedHasAnyAvatarHashes: Boolean = false
    private var lastBackActionTime: Long = 0L
    private val avatarMatcher = AvatarMatcher()

    // In-memory enrollment window tracking (not persisted): which contact we are
    // currently trying to enroll and when that attempt window started.
    private var enrollmentContact: String? = null
    private var enrollmentStartElapsed: Long = 0L

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
            val matchedByName = findBlockedContactInChat(root)
            val canCaptureAvatar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

            // --- Enrollment mode -------------------------------------------------
            // While the user is enrolling a contact's avatar, suppress blocking for
            // THAT contact so the screenshot can be captured, but keep blocking any
            // other blocked contact by name. The window is time-bounded so a failed
            // capture can never leave the contact permanently unblocked.
            val pendingEnrollment = BlockedContactsRepository.getPendingAvatarEnrollmentContact(applicationContext)
            if (pendingEnrollment != null && canCaptureAvatar) {
                if (!pendingEnrollment.equals(enrollmentContact, ignoreCase = true)) {
                    enrollmentContact = pendingEnrollment
                    enrollmentStartElapsed = SystemClock.elapsedRealtime()
                }

                val withinWindow =
                    SystemClock.elapsedRealtime() - enrollmentStartElapsed <= ENROLLMENT_WINDOW_MS
                if (withinWindow) {
                    avatarMatcher.captureAvatarHash(this, root) { observedHash ->
                        runOnMainThread {
                            if (!observedHash.isNullOrBlank()) {
                                enrollPendingContactAvatarHash(pendingEnrollment, observedHash)
                            }
                        }
                    }
                    // Still block other blocked contacts while enrolling this one.
                    if (matchedByName != null && !matchedByName.equals(pendingEnrollment, ignoreCase = true)) {
                        maybeBlockContact(matchedByName, "name")
                    }
                    return
                }

                // Window elapsed without a successful capture: give up enrolling so the
                // contact resumes normal blocking instead of staying suppressed forever.
                Log.d(TAG, "Avatar enrollment window expired for $pendingEnrollment — giving up")
                BlockedContactsRepository.setPendingAvatarEnrollmentContact(applicationContext, null)
                resetEnrollmentTracking()
            }

            // --- Normal blocking -------------------------------------------------
            // Name matching is the reliable, synchronous primary mechanism.
            if (matchedByName != null) {
                maybeBlockContact(matchedByName, "name")
                return
            }

            // Best-effort secondary path: avatar matching for chats that didn't match
            // by name (API 30+ only, and only when we actually have stored hashes).
            if (canCaptureAvatar && cachedHasAnyAvatarHashes) {
                avatarMatcher.captureAvatarHash(this, root) { observedHash ->
                    runOnMainThread {
                        val avatarMatch = observedHash?.let {
                            avatarMatcher.findBestMatch(it, cachedAvatarHashesByContact)
                        } ?: return@runOnMainThread
                        Log.d(
                            TAG,
                            "Avatar match: ${avatarMatch.contact} (distance=${avatarMatch.distance}, hash=${avatarMatch.observedHash})"
                        )
                        maybeBlockContact(avatarMatch.contact, "avatar")
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun enrollPendingContactAvatarHash(pendingContact: String?, hash: String) {
        val contact = pendingContact ?: return
        resetEnrollmentTracking()
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

    private fun resetEnrollmentTracking() {
        enrollmentContact = null
        enrollmentStartElapsed = 0L
    }

    private fun maybeBlockContact(contact: String?, reason: String) {
        val blockedContact = contact ?: return
        if (!BlockedContactsRepository.isWithinScheduleForContact(applicationContext, blockedContact)) return

        val now = System.currentTimeMillis()
        if (now - lastBackActionTime > BACK_ACTION_COOLDOWN_MS) {
            Log.d(TAG, "Blocked contact chat detected via $reason: $blockedContact — navigating back")
            lastBackActionTime = now
            performGlobalAction(GLOBAL_ACTION_BACK)
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
