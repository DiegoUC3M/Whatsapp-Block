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
        private const val BACK_ACTION_COOLDOWN_MS = 2000L
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

        // Only react to window state changes (opening a chat) to reduce noise
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            if (isInsideBlockedChat(root)) {
                val now = System.currentTimeMillis()
                if (now - lastBackActionTime > BACK_ACTION_COOLDOWN_MS) {
                    Log.d(TAG, "Blocked contact chat detected — navigating back")
                    lastBackActionTime = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Checks if we are currently inside a chat with a blocked contact by looking
     * specifically at the conversation title bar (the contact name at the top of the chat).
     * This avoids false positives from the main chat list or message bubbles.
     */
    private fun isInsideBlockedChat(root: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Look for the known conversation_contact_name view ID
        for (viewId in CONVERSATION_TITLE_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes != null) {
                for (node in nodes) {
                    try {
                        val text = node.text?.toString()
                        if (!text.isNullOrBlank() && matchesBlocked(text)) {
                            return true
                        }
                    } finally {
                        node.recycle()
                    }
                }
            }
        }

        // Strategy 2: Fallback — look for a toolbar-like container that has the contact name.
        // WhatsApp's conversation screen has an action bar with the contact name.
        // We look for nodes with specific characteristics of the conversation header.
        return checkConversationHeader(root)
    }

    /**
     * Fallback detection: looks for the contact name in what appears to be
     * a conversation header/toolbar area. The conversation header in WhatsApp
     * typically contains a back arrow, profile image, and the contact name.
     * We identify it by looking for an actionBar or toolbar-like parent.
     */
    private fun checkConversationHeader(root: AccessibilityNodeInfo): Boolean {
        // Look for the action_bar or toolbar containers
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
                        if (toolbarContainsBlockedName(bar)) {
                            return true
                        }
                    } finally {
                        bar.recycle()
                    }
                }
            }
        }
        return false
    }

    /**
     * Searches only within a toolbar/action bar node for a blocked contact name.
     */
    private fun toolbarContainsBlockedName(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && matchesBlocked(text)) return true

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && matchesBlocked(desc)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (toolbarContainsBlockedName(child)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun matchesBlocked(text: String): Boolean {
        return cachedBlockedContacts.any { text.equals(it, ignoreCase = true) }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }
}
