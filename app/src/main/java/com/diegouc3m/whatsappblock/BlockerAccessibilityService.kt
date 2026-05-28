package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppBlocker"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

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
    }

    private var cachedBlockedContacts: Set<String> = emptySet()
    private var lastBackActionTime: Long = 0L

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cachedBlockedContacts = BlockedContactsRepository.getBlockedContacts(applicationContext)
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cachedBlockedContacts = BlockedContactsRepository.getBlockedContacts(applicationContext)
        BlockedContactsRepository.registerListener(applicationContext, prefsListener)
    }

    override fun onDestroy() {
        BlockedContactsRepository.unregisterListener(applicationContext, prefsListener)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return
        if (cachedBlockedContacts.isEmpty()) return

        // React to window state changes (opening a chat) and content changes (re-entering a chat)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            val blockedContact = findBlockedContactInChat(root)
            if (blockedContact != null) {
                // Check per-contact schedule
                if (!BlockedContactsRepository.isWithinScheduleForContact(applicationContext, blockedContact)) return

                val now = System.currentTimeMillis()
                if (now - lastBackActionTime > BACK_ACTION_COOLDOWN_MS) {
                    Log.d(TAG, "Blocked contact chat detected: $blockedContact — navigating back")
                    lastBackActionTime = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        } finally {
            root.recycle()
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
