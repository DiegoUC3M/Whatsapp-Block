package com.diegouc3m.whatsappblock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ViewIdLookupShadow::class, SnapshotAccessibilityServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BlockerAccessibilityServiceTest {
    private lateinit var context: Context
    private var controller: ServiceController<BlockerAccessibilityService>? = null
    private lateinit var service: BlockerAccessibilityService
    private lateinit var serviceShadow: SnapshotAccessibilityServiceShadow

    @Before fun clearLocalState() {
        context = ApplicationProvider.getApplicationContext()
        BlockedContactsRepository.clearAll(context)
        context.getSharedPreferences("privacy_consent", Context.MODE_PRIVATE).edit().clear().commit()
        BlockedContactsRepository.addContact(context, CONTACT)
    }

    @After fun destroyService() {
        controller?.destroy()
    }

    @Test fun verifiedSelectedChatPerformsOneBackAction() {
        launchService()
        serviceShadow.setRootInActiveWindow(chat())

        deliverWhatsAppEvent()

        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), serviceShadow.globalActionsPerformed)
        // The second root read verifies the focused chat immediately before acting.
        assertEquals(2, serviceShadow.rootReadCount)
    }

    @Test fun queuedWhatsAppEventCannotNavigateAnotherApplication() {
        launchService()
        serviceShadow.setRootInActiveWindow(chat(pkg = "com.android.settings"))

        deliverWhatsAppEvent()

        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun switchingAppsBetweenDetectionAndActionCancelsBack() {
        launchService()
        serviceShadow.setRootInActiveWindow(chat())
        serviceShadow.rootAfterFirstRead = chat(pkg = "com.android.settings")

        deliverWhatsAppEvent()

        assertEquals(2, serviceShadow.rootReadCount)
        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun inactiveUnfocusedAndSystemWindowsCannotNavigateBack() {
        launchService()
        for (root in listOf(
            chat(active = false),
            chat(focused = false),
            chat(windowType = AccessibilityWindowInfo.TYPE_SYSTEM)
        )) {
            serviceShadow.setRootInActiveWindow(root)
            deliverWhatsAppEvent()
        }

        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun profileChatListAndMessageTextCannotAuthorizeBlocking() {
        launchService()
        val profile = chat(composer = false)
        val chatList = chat(titleId = "conversations_row_contact_name", composer = false)
        val messageBody = chat(titleId = "message_text")
        val hiddenTitle = chat(titleVisible = false)
        for (root in listOf(profile, chatList, messageBody, hiddenTitle)) {
            serviceShadow.setRootInActiveWindow(root)
            deliverWhatsAppEvent()
        }

        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun noConsentPreventsEvenReadingTheActiveWindow() {
        launchService(consented = false)
        serviceShadow.setRootInActiveWindow(chat())

        deliverWhatsAppEvent()

        assertEquals(0, serviceShadow.rootReadCount)
        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun pausingAndRevokingPreventFurtherWindowAccessOrActions() {
        launchService()
        serviceShadow.setRootInActiveWindow(chat())
        ConsentStore.setPaused(context, true)
        deliverWhatsAppEvent()
        assertEquals(0, serviceShadow.rootReadCount)
        ConsentStore.revoke(context)
        deliverWhatsAppEvent()

        assertEquals(0, serviceShadow.rootReadCount)
        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
    }

    @Test fun screenOffCancelsAnExistingQuotaObservationUntilANewChatEvent() {
        BlockedContactsRepository.setContactBlockMode(context, CONTACT, BlockMode.QUOTA)
        BlockedContactsRepository.setContactQuotaMinutes(context, CONTACT, 50)
        launchService()
        serviceShadow.setRootInActiveWindow(chat())
        deliverWhatsAppEvent()
        val firstReads = serviceShadow.rootReadCount
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(serviceShadow.rootReadCount > firstReads)

        // Keep the fixture interactive and visible to model a rapid off/on interruption:
        // the SCREEN_OFF broadcast itself must invalidate the existing observation.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        val readsAfterInterruption = serviceShadow.rootReadCount
        val usageAfterInterruption = BlockedContactsRepository.getContactQuotaUsedMs(context, CONTACT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(readsAfterInterruption, serviceShadow.rootReadCount)
        assertEquals(usageAfterInterruption, BlockedContactsRepository.getContactQuotaUsedMs(context, CONTACT))
        assertTrue(serviceShadow.globalActionsPerformed.isEmpty())
        deliverWhatsAppEvent()
        assertTrue(serviceShadow.rootReadCount > readsAfterInterruption)
    }

    private fun launchService(consented: Boolean = true) {
        if (consented) ConsentStore.grant(context)
        controller = Robolectric.buildService(BlockerAccessibilityService::class.java).create()
        service = requireNotNull(controller).get()
        serviceShadow = org.robolectric.shadow.api.Shadow.extract(service)
        // Android invokes this protected framework lifecycle callback after binding.
        // No private application fields or implementation methods are accessed.
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
    }

    private fun deliverWhatsAppEvent() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = WhatsAppPackages.WHATSAPP
        try {
            service.onAccessibilityEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun chat(
        pkg: String = WhatsAppPackages.WHATSAPP,
        active: Boolean = true,
        focused: Boolean = true,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        titleId: String = "conversation_contact_name",
        titleVisible: Boolean = true,
        composer: Boolean = true
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(1)
            setType(windowType)
            setActive(active)
            setFocused(focused)
        }
        shadowOf(root).setAccessibilityWindowInfo(window)
        val title = AccessibilityNodeInfo.obtain().apply {
            viewIdResourceName = "$pkg:id/$titleId"
            isVisibleToUser = titleVisible
            text = CONTACT
        }
        shadowOf(root).addChild(title)
        if (composer) {
            shadowOf(root).addChild(AccessibilityNodeInfo.obtain().apply {
                viewIdResourceName = "$pkg:id/entry"
                isVisibleToUser = true
            })
        }
        return root
    }

    private companion object {
        const val CONTACT = "Chat de prueba"
    }
}

/** Robolectric's standard node shadow lacks lookup by resource ID. */
@Implements(AccessibilityNodeInfo::class)
class ViewIdLookupShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    @Implementation
    protected fun findAccessibilityNodeInfosByViewId(viewId: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(current: AccessibilityNodeInfo) {
            if (current.viewIdResourceName == viewId) result.add(AccessibilityNodeInfo.obtain(current))
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(node)
        return result
    }
}

/** Each Android window read returns its own recyclable snapshot. */
@Implements(AccessibilityService::class)
class SnapshotAccessibilityServiceShadow : ShadowAccessibilityService() {
    var rootReadCount = 0
        private set
    var rootAfterFirstRead: AccessibilityNodeInfo? = null

    @Implementation
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        rootReadCount++
        val root = if (rootReadCount > 1 && rootAfterFirstRead != null) {
            rootAfterFirstRead
        } else super.getRootInActiveWindow()
        return root?.let { AccessibilityNodeInfo.obtain(it) }
    }
}
