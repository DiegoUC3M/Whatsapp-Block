package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppBlocker"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return

        val root = rootInActiveWindow ?: return
        val allText = mutableListOf<String>()

        // Traverse the entire accessibility tree and collect all visible text.
        // This approach does NOT rely on view IDs, making it resilient to WhatsApp updates.
        traverseTree(root, allText)

        val blocked = BlockedContactsRepository.getBlockedContacts(applicationContext)
        val matched = blocked.firstOrNull { name ->
            allText.any { it.contains(name, ignoreCase = true) }
        }

        if (matched != null) {
            Log.d(TAG, "Blocked contact detected: $matched — navigating back")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun traverseTree(node: AccessibilityNodeInfo?, output: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) output.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) output.add(desc)
        for (i in 0 until node.childCount) {
            traverseTree(node.getChild(i), output)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }
}
