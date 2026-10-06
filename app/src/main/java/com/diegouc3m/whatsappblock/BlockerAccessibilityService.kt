package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/** Applies only the chat rules explicitly configured by the device's user. */
class BlockerAccessibilityService : AccessibilityService() {
    private data class Chat(val contact: String, val windowId: Int)

    private var contacts: Set<String> = emptySet()
    private var observedChat: Chat? = null
    private var quotaClock: QuotaClock? = null
    private var lastBackAt: Long? = null
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            evaluateChat(fromTicker = true)
            if (observedChat != null) handler.postDelayed(this, TICK_MS)
        }
    }

    private val rulesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Counter writes are observations, not configuration changes.
        if (key == null || !isUsageCounterKey(key)) {
            stopObserving()
            contacts = BlockedContactsRepository.getBlockedContacts(this).toSet()
        }
    }
    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        stopObserving()
        if (!ConsentStore.hasConsent(this)) disableSelf()
    }
    private val interruptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = stopObserving()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BlockedContactsRepository.removeLegacyAvatarData(this)
        contacts = BlockedContactsRepository.getBlockedContacts(this).toSet()
        BlockedContactsRepository.registerListener(this, rulesListener)
        ConsentStore.registerListener(this, consentListener)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                this, interruptionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        // Upgrades and enabling the Android service alone do not imply in-app consent.
        if (!ConsentStore.hasConsent(this)) disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (event.packageName?.toString() !in WhatsAppPackages.ALL) return
        evaluateChat()
    }

    override fun onInterrupt() = stopObserving()

    override fun onDestroy() {
        stopObserving()
        BlockedContactsRepository.unregisterListener(this, rulesListener)
        ConsentStore.unregisterListener(this, consentListener)
        if (receiverRegistered) {
            unregisterReceiver(interruptionReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun canInspectNow(): Boolean = ChatSafety.canInspect(
        consented = ConsentStore.hasConsent(this),
        paused = ConsentStore.isPaused(this),
        interactive = getSystemService(PowerManager::class.java).isInteractive,
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    )

    private fun evaluateChat(fromTicker: Boolean = false) {
        if (!canInspectNow() || contacts.isEmpty()) {
            stopObserving()
            return
        }
        val chat = findVerifiedChat()
        if (chat == null || !BlockedContactsRepository.isContactBlockingEnabled(this, chat.contact)) {
            stopObserving()
            return
        }
        if (observedChat != chat) {
            stopObserving()
            observedChat = chat
            if (!fromTicker) handler.postDelayed(ticker, TICK_MS)
        }

        when (BlockedContactsRepository.getContactBlockMode(this, chat.contact)) {
            BlockMode.SCHEDULE -> {
                quotaClock = null
                if (BlockedContactsRepository.isWithinScheduleForContact(this, chat.contact)) {
                    performBlockBack(chat)
                }
                // Continue watching: a chosen schedule may start while the chat is open.
            }
            BlockMode.QUOTA -> {
                val elapsed = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val clock = quotaClock
                if (clock == null) {
                    quotaClock = QuotaClock(elapsed, wall)
                } else {
                    clock.sample(elapsed, wall).forEach { slice ->
                        BlockedContactsRepository.addContactQuotaUsage(
                            this, chat.contact, slice.durationMs, slice.atMillis
                        )
                    }
                }
                if (BlockedContactsRepository.isContactQuotaExceeded(this, chat.contact)) {
                    performBlockBack(chat)
                }
            }
        }
    }

    private fun performBlockBack(expected: Chat) {
        val now = SystemClock.elapsedRealtime()
        if (lastBackAt?.let { now - it < BACK_COOLDOWN_MS } == true) return
        if (!canInspectNow() ||
            expected.contact !in BlockedContactsRepository.getBlockedContacts(this) ||
            !BlockedContactsRepository.isContactBlockingEnabled(this, expected.contact)) return
        val shouldBlock = when (BlockedContactsRepository.getContactBlockMode(this, expected.contact)) {
            BlockMode.SCHEDULE -> BlockedContactsRepository.isWithinScheduleForContact(this, expected.contact)
            BlockMode.QUOTA -> BlockedContactsRepository.isContactQuotaExceeded(this, expected.contact)
        }
        // A queued event is insufficient: recheck the actual focused chat just before BACK.
        if (!shouldBlock || findVerifiedChat() != expected) {
            stopObserving()
            return
        }
        lastBackAt = now
        if (performGlobalAction(GLOBAL_ACTION_BACK)) stopObserving()
    }

    private fun stopObserving() {
        observedChat = null
        quotaClock = null
        handler.removeCallbacks(ticker)
        // Do not charge an unverified interval after leaving, locking, pausing or revoking.
    }

    private fun findVerifiedChat(): Chat? {
        if (!canInspectNow()) return null
        val root = try { rootInActiveWindow } catch (_: SecurityException) { null } ?: return null
        try {
            val pkg = root.packageName?.toString() ?: return null
            if (pkg !in WhatsAppPackages.ALL) return null
            val window = root.window ?: return null
            try {
                if (!window.isActive || !window.isFocused ||
                    window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
                // Inspect IDs and visibility only. Never read the composer or message contents.
                val hasComposer = hasVisibleNode(root, "$pkg:id/entry")
                val title = visibleConversationTitle(root, "$pkg:id/conversation_contact_name")
                val contact = ChatSafety.matchContact(
                    pkg, window.isActive, window.isFocused,
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                    title, hasComposer, contacts
                ) ?: return null
                return Chat(contact, root.windowId)
            } finally {
                window.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun hasVisibleNode(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        try {
            return nodes.any { it.isVisibleToUser }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun visibleConversationTitle(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        try {
            return nodes.filter { it.isVisibleToUser }
                .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.singleOrNull()
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun isUsageCounterKey(key: String): Boolean = listOf(
        "contact_quota_used_ms_", "contact_quota_hour_stamp_",
        "contact_daily_used_ms_", "contact_daily_day_stamp_"
    ).any(key::startsWith)

    companion object {
        private const val TICK_MS = 1_000L
        private const val BACK_COOLDOWN_MS = 800L
    }
}
