package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.os.Build
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

        if (handlePendingAvatarEnrollment(root, allText)) {
            return
        }

        val matchedByName = findNameMatch(allText)
        val avatarHashes = BlockedContactsRepository.getAvatarHashes(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && avatarHashes.isNotEmpty()) {
            when (
                AvatarMatcher.requestAvatarHashCapture(
                    service = this,
                    root = root,
                    onSuccess = { capturedHash ->
                        val avatarMatch = AvatarMatcher.findMatchingContact(capturedHash, avatarHashes)
                        if (avatarMatch != null) {
                            Log.d(
                                TAG,
                                "Blocked contact detected by avatar: ${avatarMatch.first} " +
                                    "(distance=${avatarMatch.second}) — navigating back"
                            )
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        } else if (matchedByName != null) {
                            Log.d(
                                TAG,
                                "Blocked contact detected by name fallback: $matchedByName — navigating back"
                            )
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    },
                    onFailure = { reason ->
                        Log.d(TAG, "Avatar capture failed: $reason")
                        if (matchedByName != null) {
                            Log.d(
                                TAG,
                                "Blocked contact detected by name fallback after avatar failure: " +
                                    "$matchedByName — navigating back"
                            )
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }
                )
            ) {
                AvatarMatcher.CaptureRequestStatus.STARTED -> return

                AvatarMatcher.CaptureRequestStatus.IN_FLIGHT,

                AvatarMatcher.CaptureRequestStatus.THROTTLED,
                AvatarMatcher.CaptureRequestStatus.NO_BOUNDS,
                AvatarMatcher.CaptureRequestStatus.UNSUPPORTED -> Unit
            }
        }

        if (matchedByName != null) {
            Log.d(TAG, "Blocked contact detected by name fallback: $matchedByName — navigating back")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun handlePendingAvatarEnrollment(
        root: AccessibilityNodeInfo,
        allText: List<String>
    ): Boolean {
        val pendingContact = BlockedContactsRepository.getPendingAvatarEnrollment(applicationContext)
            ?: return false

        val targetVisible = allText.any { text ->
            text.contains(pendingContact, ignoreCase = true)
        }
        if (!targetVisible) return false

        when (
            AvatarMatcher.requestAvatarHashCapture(
                service = this,
                root = root,
                onSuccess = { capturedHash ->
                    BlockedContactsRepository.addAvatarHash(
                        applicationContext,
                        pendingContact,
                        capturedHash
                    )
                    BlockedContactsRepository.clearPendingAvatarEnrollment(applicationContext)
                    BlockedContactsRepository.setPendingStatusMessage(
                        applicationContext,
                        getString(R.string.avatar_enrollment_success, pendingContact)
                    )
                    Log.d(TAG, "Enrolled avatar hash for $pendingContact")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                },
                onFailure = { reason ->
                    BlockedContactsRepository.clearPendingAvatarEnrollment(applicationContext)
                    BlockedContactsRepository.setPendingStatusMessage(
                        applicationContext,
                        getString(R.string.avatar_enrollment_failed, pendingContact)
                    )
                    Log.d(TAG, "Avatar enrollment failed for $pendingContact: $reason")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            )
        ) {
            AvatarMatcher.CaptureRequestStatus.STARTED,
            AvatarMatcher.CaptureRequestStatus.IN_FLIGHT,
            AvatarMatcher.CaptureRequestStatus.THROTTLED -> {
                Log.d(TAG, "Avatar enrollment in progress for $pendingContact")
                return true
            }

            AvatarMatcher.CaptureRequestStatus.NO_BOUNDS -> {
                Log.d(TAG, "Avatar bounds not ready yet for $pendingContact")
                return true
            }

            AvatarMatcher.CaptureRequestStatus.UNSUPPORTED -> {
                BlockedContactsRepository.clearPendingAvatarEnrollment(applicationContext)
                BlockedContactsRepository.setPendingStatusMessage(
                    applicationContext,
                    getString(R.string.avatar_enrollment_requires_api_30)
                )
                Log.d(TAG, "Avatar enrollment skipped because screenshot capture is unavailable")
            }
        }

        return false
    }

    private fun findNameMatch(allText: List<String>): String? {
        val blocked = BlockedContactsRepository.getBlockedContacts(applicationContext)
        return blocked.firstOrNull { name ->
            allText.any { it.contains(name, ignoreCase = true) }
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
