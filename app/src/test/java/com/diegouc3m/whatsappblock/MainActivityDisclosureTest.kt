package com.diegouc3m.whatsappblock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityDisclosureTest {
    private var main: ActivityController<MainActivity>? = null
    private var privacy: ActivityController<PrivacyActivity>? = null

    @Before
    fun clearStoredState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("privacy_consent", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        BlockedContactsRepository.clearAll(context)
    }

    @After
    fun tearDown() {
        main?.pause()?.stop()?.destroy()
        privacy?.pause()?.stop()?.destroy()
    }

    @Test
    fun rejectingDisclosureDoesNotGrantConsentOrOpenAndroidSettings() {
        val activity = launchMain()
        assertFalse(ConsentStore.hasConsent(activity))
        activity.findViewById<Button>(R.id.btnOpenSettings).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ConsentStore.hasConsent(activity))

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val observedConsent = mutableListOf<Boolean>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            observedConsent += ConsentStore.hasConsent(activity)
        }
        ConsentStore.registerListener(activity, listener)
        try {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            // AppCompat dispatches dialog buttons through Handler messages, just as on Android.
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(dialog.isShowing)
            assertTrue("The rejection callback must update stored preferences", observedConsent.isNotEmpty())
            assertTrue(observedConsent.all { !it })
            assertFalse(ConsentStore.hasConsent(activity))
            assertTrue(ConsentStore.isPaused(activity))
            assertNull(shadowOf(activity).nextStartedActivity)
            // Declining access does not lock the user out of local rule configuration.
            assertTrue(activity.findViewById<Button>(R.id.btnAdd).isEnabled)
            assertTrue(activity.findViewById<Button>(R.id.btnPrivacy).isEnabled)
        } finally {
            ConsentStore.unregisterListener(activity, listener)
        }
    }

    @Test
    fun acceptingDisclosureGrantsConsentBeforeOpeningAndroidSettings() {
        val activity = launchMain()
        activity.findViewById<Button>(R.id.btnOpenSettings).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ConsentStore.hasConsent(activity))

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertTrue(ConsentStore.hasConsent(activity))
        assertFalse(ConsentStore.isPaused(activity))
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action
        )
    }

    @Test
    fun privacyIsAccessibleWithoutConsentAndShowsTheFullBundledPolicy() {
        val activity = launchMain()
        activity.findViewById<Button>(R.id.btnPrivacy).performClick()
        val intent: Intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(PrivacyActivity::class.java.name, intent.component?.className)
        assertFalse(ConsentStore.hasConsent(activity))

        privacy = Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        val policyActivity = requireNotNull(privacy).get()
        val text = textFrom(policyActivity.window.decorView)
        assertTrue(text.contains("Datos que utiliza y finalidad"))
        assertTrue(text.contains("Conservación y borrado"))
        assertTrue(text.contains("Contacto de privacidad"))
        assertTrue(text.contains("Cambios de la política"))
        // Rendering the bundled policy starts no browser and makes no permission request.
        assertNull(shadowOf(policyActivity).nextStartedActivity)
    }

    private fun launchMain(): MainActivity {
        main = Robolectric.buildActivity(MainActivity::class.java).setup()
        return requireNotNull(main).get()
    }

    private fun textFrom(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { textFrom(view.getChildAt(it)) }
        else -> ""
    }
}
