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
    }

    private var cachedBlockedContacts: Set<String> = emptySet()

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

        val root = rootInActiveWindow ?: return
        try {
            if (treeContainsBlockedContact(root)) {
                Log.d(TAG, "Blocked contact detected — navigating back")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Traverses the accessibility tree looking for any blocked contact name.
     * Returns true as soon as a match is found (short-circuit) for performance.
     * Properly recycles all child nodes to avoid memory leaks.
     */
    private fun treeContainsBlockedContact(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && matchesBlocked(text)) return true

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && matchesBlocked(desc)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (treeContainsBlockedContact(child)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun matchesBlocked(text: String): Boolean {
        return cachedBlockedContacts.any { text.contains(it, ignoreCase = true) }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }
}
