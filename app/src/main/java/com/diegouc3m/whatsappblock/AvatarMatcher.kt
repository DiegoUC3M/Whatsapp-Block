package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class AvatarMatcher {

    data class AvatarMatchResult(
        val contact: String,
        val storedHash: String,
        val observedHash: String,
        val distance: Int
    )

    companion object {
        private const val TAG = "AvatarMatcher"
        private const val HASH_SIZE = 8
        // 64-bit dHash: resize to 9x8, compare 8 horizontal pairs per row => 8*8 = 64 bits.
        private const val HASH_WIDTH = HASH_SIZE + 1
        private const val HASH_HEIGHT = HASH_SIZE
        private const val HASH_HEX_LENGTH = 16
        // The platform enforces a minimum interval of ~1 second between
        // AccessibilityService.takeScreenshot() calls. Requesting faster than this
        // makes every extra call fail with ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
        // which is what previously broke avatar capture/matching in active chats
        // (WhatsApp fires frequent content-change events). Stay at/above that limit.
        private const val SCREENSHOT_COOLDOWN_MS = 1000L
        private const val HAMMING_THRESHOLD = 10

        // Reasonable pixel-size bounds for a WhatsApp chat-header avatar across a
        // wide range of screen densities. The back-arrow and action icons are
        // ~24-36dp (~70-110px on xxhdpi), while the avatar is ~40-48dp
        // (~120-180px on xxhdpi). Bounds err on the wide side intentionally.
        private const val MIN_AVATAR_SIDE_PX = 60
        private const val MAX_AVATAR_SIDE_PX = 400

        private val AVATAR_VIEW_IDS = listOf(
            "com.whatsapp:id/conversation_contact_photo",
            "com.whatsapp.w4b:id/conversation_contact_photo",
            "com.whatsapp:id/conversation_contact_photo_container",
            "com.whatsapp.w4b:id/conversation_contact_photo_container"
        )

        private val TOOLBAR_VIEW_IDS = listOf(
            "com.whatsapp:id/action_bar",
            "com.whatsapp.w4b:id/action_bar",
            "com.whatsapp:id/toolbar",
            "com.whatsapp.w4b:id/toolbar"
        )
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var captureInFlight = false
    private var lastCaptureElapsedRealtime = 0L

    fun captureAvatarHash(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        callback: (String?) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        synchronized(this) {
            if (captureInFlight) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastCaptureElapsedRealtime < SCREENSHOT_COOLDOWN_MS) return false
            captureInFlight = true
            lastCaptureElapsedRealtime = now
        }

        val avatarBounds = findAvatarBounds(root)
        if (avatarBounds == null) {
            markCaptureComplete()
            callback(null)
            return true
        }

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val bitmap = screenshotToBitmap(screenshot)
                        val hash = bitmap?.let { bitmapToHashHex(it, avatarBounds) }
                        callback(hash)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Avatar screenshot processing failed", t)
                        callback(null)
                    } finally {
                        markCaptureComplete()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed with code=$errorCode")
                    markCaptureComplete()
                    callback(null)
                }
            }
        )

        return true
    }

    fun findBestMatch(
        observedHashHex: String,
        hashesByContact: Map<String, Set<String>>,
        maxDistance: Int = HAMMING_THRESHOLD
    ): AvatarMatchResult? {
        val observed = parseHash(observedHashHex) ?: return null
        var best: AvatarMatchResult? = null

        for ((contact, hashes) in hashesByContact) {
            for (storedHash in hashes) {
                val stored = parseHash(storedHash) ?: continue
                val distance = (observed xor stored).countOneBits()
                if (distance > maxDistance) continue

                val current = best
                if (current == null || distance < current.distance) {
                    best = AvatarMatchResult(contact, storedHash, observedHashHex, distance)
                }
            }
        }
        return best
    }

    private fun findAvatarBounds(root: AccessibilityNodeInfo): Rect? {
        for (viewId in AVATAR_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: continue
            for (node in nodes) {
                try {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) return rect
                } finally {
                    node.recycle()
                }
            }
        }

        for (toolbarId in TOOLBAR_VIEW_IDS) {
            val bars = root.findAccessibilityNodeInfosByViewId(toolbarId) ?: continue
            for (bar in bars) {
                try {
                    val guess = findAvatarInToolbar(bar)
                    if (guess != null) return guess
                } finally {
                    bar.recycle()
                }
            }
        }
        return null
    }

    /**
     * Heuristic avatar locator for WhatsApp's chat toolbar when the well-known
     * avatar view IDs are not present (frequent across WhatsApp versions/variants).
     *
     * The previous implementation returned the first ImageView it encountered, which
     * is almost always the back-arrow icon — making the captured hash worthless and
     * effectively breaking avatar enrollment. Instead, we collect every image-like
     * node in the toolbar subtree and pick the one that best matches an avatar:
     *
     *   * roughly square (aspect ratio in [0.75, 1.33])
     *   * sized like an avatar, not a 24dp icon (min side at least
     *     [MIN_AVATAR_SIDE_PX] pixels) and not full-width
     *   * not the leftmost image (the back arrow is always leftmost in the toolbar)
     *
     * Among candidates we prefer the largest valid one and fall back to the
     * second-leftmost image if no candidate qualifies.
     */
    private fun findAvatarInToolbar(toolbar: AccessibilityNodeInfo): Rect? {
        val images = mutableListOf<Rect>()
        collectImageBounds(toolbar, images)
        if (images.isEmpty()) return null

        // Sort by horizontal position; the back arrow is always the leftmost.
        images.sortBy { it.left }

        val candidates = images.drop(1).filter { isAvatarShape(it) }
        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { it.width().toLong() * it.height().toLong() }
        }

        // Fall back to the second image (typically the avatar even if our shape
        // heuristic rejected it) so we don't regress to capturing the back arrow.
        return images.getOrNull(1)
    }

    private fun collectImageBounds(node: AccessibilityNodeInfo, out: MutableList<Rect>) {
        val className = node.className?.toString().orEmpty()
        if (className.contains("ImageView", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        ) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.left >= 0 && rect.top >= 0) {
                out.add(Rect(rect))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectImageBounds(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isAvatarShape(rect: Rect): Boolean {
        val w = rect.width()
        val h = rect.height()
        if (w < MIN_AVATAR_SIDE_PX || h < MIN_AVATAR_SIDE_PX) return false
        if (w > MAX_AVATAR_SIDE_PX || h > MAX_AVATAR_SIDE_PX) return false
        val ratio = w.toDouble() / h.toDouble()
        return ratio in 0.75..1.33
    }

    private fun screenshotToBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val hardwareBuffer: HardwareBuffer = result.hardwareBuffer ?: return null
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace) ?: return null
            try {
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap.recycle()
            }
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun bitmapToHashHex(bitmap: Bitmap, avatarBounds: Rect): String? {
        val safeRect = Rect(
            avatarBounds.left.coerceIn(0, bitmap.width),
            avatarBounds.top.coerceIn(0, bitmap.height),
            avatarBounds.right.coerceIn(0, bitmap.width),
            avatarBounds.bottom.coerceIn(0, bitmap.height)
        )
        if (safeRect.width() <= 1 || safeRect.height() <= 1) return null

        val cropped = Bitmap.createBitmap(bitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
        val resized = Bitmap.createScaledBitmap(cropped, HASH_WIDTH, HASH_HEIGHT, true)
        cropped.recycle()

        var hash = 0uL
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_SIZE) {
                val leftLuma = luma(resized.getPixel(x, y))
                val rightLuma = luma(resized.getPixel(x + 1, y))
                hash = hash shl 1
                if (leftLuma > rightLuma) {
                    hash = hash or 1uL
                }
            }
        }
        resized.recycle()
        return hash.toString(16).padStart(HASH_HEX_LENGTH, '0').lowercase()
    }

    private fun luma(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // ITU-R BT.601 integer approximation for RGB -> grayscale luminance.
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun parseHash(hashHex: String): ULong? {
        val normalized = hashHex.trim().lowercase()
        return if (normalized.length == HASH_HEX_LENGTH) {
            normalized.toULongOrNull(16)
        } else {
            null
        }
    }

    private fun markCaptureComplete() {
        synchronized(this) {
            captureInFlight = false
        }
    }
}
