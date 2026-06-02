package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlin.math.abs

object AvatarMatcher {

    const val DEFAULT_HAMMING_THRESHOLD = 10

    private const val TAG = "WhatsAppBlocker"
    private const val D_HASH_WIDTH = 9
    private const val D_HASH_HEIGHT = 8
    private const val MIN_CAPTURE_INTERVAL_MS = 333L
    private const val SQUARE_ASPECT_RATIO_TOLERANCE = 0.35f
    private const val IMAGE_VIEW_SCORE_BONUS = 120f

    @Volatile
    private var lastCaptureTimestampMs = 0L

    @Volatile
    private var captureInFlight = false

    enum class CaptureRequestStatus {
        STARTED,
        THROTTLED,
        IN_FLIGHT,
        NO_BOUNDS,
        UNSUPPORTED
    }

    fun requestAvatarHashCapture(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        onSuccess: (Long) -> Unit,
        onFailure: (String) -> Unit
    ): CaptureRequestStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureRequestStatus.UNSUPPORTED
        }

        val avatarBounds = findAvatarBounds(service, root) ?: return CaptureRequestStatus.NO_BOUNDS

        synchronized(this) {
            if (captureInFlight) return CaptureRequestStatus.IN_FLIGHT

            val now = SystemClock.elapsedRealtime()
            if (now - lastCaptureTimestampMs < MIN_CAPTURE_INTERVAL_MS) {
                Log.d(TAG, "Skipping avatar screenshot because takeScreenshot is rate limited")
                return CaptureRequestStatus.THROTTLED
            }

            captureInFlight = true
            lastCaptureTimestampMs = now
        }

        return try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(service),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hash = computeHashFromScreenshot(screenshot, avatarBounds)
                            if (hash != null) {
                                onSuccess(hash)
                            } else {
                                onFailure("Failed to compute avatar hash from screenshot crop")
                            }
                        } finally {
                            screenshot.hardwareBuffer.close()
                            captureInFlight = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        captureInFlight = false
                        onFailure("takeScreenshot failed with errorCode=$errorCode")
                    }
                }
            )
            CaptureRequestStatus.STARTED
        } catch (exception: RuntimeException) {
            captureInFlight = false
            onFailure("takeScreenshot threw ${exception.javaClass.simpleName}: ${exception.message}")
            CaptureRequestStatus.UNSUPPORTED
        }
    }

    fun findMatchingContact(
        avatarHash: Long,
        storedHashes: Map<String, Set<Long>>,
        threshold: Int = DEFAULT_HAMMING_THRESHOLD
    ): Pair<String, Int>? {
        var bestMatch: Pair<String, Int>? = null

        storedHashes.forEach { (contactName, hashes) ->
            hashes.forEach { storedHash ->
                val distance = hammingDistance(avatarHash, storedHash)
                if (distance <= threshold && (bestMatch == null || distance < bestMatch!!.second)) {
                    bestMatch = contactName to distance
                }
            }
        }

        return bestMatch
    }

    fun hammingDistance(first: Long, second: Long): Int {
        return java.lang.Long.bitCount(first xor second)
    }

    private fun computeHashFromScreenshot(
        screenshot: AccessibilityService.ScreenshotResult,
        requestedBounds: Rect
    ): Long? {
        val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
            ?: return null
        val safeBounds = Rect(
            requestedBounds.left.coerceAtLeast(0),
            requestedBounds.top.coerceAtLeast(0),
            requestedBounds.right.coerceAtMost(bitmap.width),
            requestedBounds.bottom.coerceAtMost(bitmap.height)
        )
        if (safeBounds.width() <= 0 || safeBounds.height() <= 0) return null

        val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        val croppedBitmap = Bitmap.createBitmap(
            softwareBitmap,
            safeBounds.left,
            safeBounds.top,
            safeBounds.width(),
            safeBounds.height()
        )
        val scaledBitmap = Bitmap.createScaledBitmap(
            croppedBitmap,
            D_HASH_WIDTH,
            D_HASH_HEIGHT,
            true
        )

        return try {
            computeDHash(scaledBitmap)
        } finally {
            if (softwareBitmap !== bitmap) {
                softwareBitmap.recycle()
            }
            croppedBitmap.recycle()
            scaledBitmap.recycle()
        }
    }

    private fun computeDHash(bitmap: Bitmap): Long {
        var hash = 0L

        for (y in 0 until D_HASH_HEIGHT) {
            for (x in 0 until D_HASH_WIDTH - 1) {
                val left = grayscale(bitmap.getPixel(x, y))
                val right = grayscale(bitmap.getPixel(x + 1, y))
                hash = hash shl 1
                if (left > right) {
                    hash = hash or 1L
                }
            }
        }

        return hash
    }

    private fun grayscale(pixel: Int): Int {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        return ((red * 77) + (green * 150) + (blue * 29)) shr 8
    }

    private fun findAvatarBounds(service: AccessibilityService, root: AccessibilityNodeInfo): Rect? {
        val displayMetrics = service.resources.displayMetrics
        val minSizePx = (28f * displayMetrics.density).toInt()
        val maxSizePx = (96f * displayMetrics.density).toInt()
        val targetSizePx = 40f * displayMetrics.density
        val candidates = mutableListOf<Pair<Rect, Float>>()

        collectCandidates(
            node = root,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            minSizePx = minSizePx,
            maxSizePx = maxSizePx,
            targetSizePx = targetSizePx,
            candidates = candidates
        )

        return candidates.minByOrNull { it.second }?.first
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        minSizePx: Int,
        maxSizePx: Int,
        targetSizePx: Float,
        candidates: MutableList<Pair<Rect, Float>>
    ) {
        node ?: return

        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val width = bounds.width()
            val height = bounds.height()
            val averageSize = (width + height) / 2f
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()

            val isSquareEnough = abs(width - height) <= averageSize * SQUARE_ASPECT_RATIO_TOLERANCE
            val isReasonableSize = width in minSizePx..maxSizePx && height in minSizePx..maxSizePx
            val isNearHeader = centerY <= screenHeight * 0.35f && centerX <= screenWidth * 0.5f

            if (isSquareEnough && isReasonableSize && isNearHeader) {
                val className = node.className?.toString().orEmpty()
                val imageBonus = if (
                    className.contains("ImageView") || className.contains("ImageButton")
                ) {
                    IMAGE_VIEW_SCORE_BONUS
                } else {
                    0f
                }

                val score = (centerY * 2f) +
                    centerX +
                    abs(averageSize - targetSizePx) * 2f +
                    abs(width - height) * 4f -
                    imageBonus

                candidates += Rect(bounds) to score
            }
        }

        for (childIndex in 0 until node.childCount) {
            collectCandidates(
                node = node.getChild(childIndex),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                minSizePx = minSizePx,
                maxSizePx = maxSizePx,
                targetSizePx = targetSizePx,
                candidates = candidates
            )
        }
    }
}
