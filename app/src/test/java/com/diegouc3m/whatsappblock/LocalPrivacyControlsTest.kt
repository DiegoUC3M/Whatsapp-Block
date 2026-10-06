package com.diegouc3m.whatsappblock

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalPrivacyControlsTest {
    private lateinit var context: Application

    @Before fun clearLocalState() {
        context = RuntimeEnvironment.getApplication()
        BlockedContactsRepository.clearAll(context)
        context.getSharedPreferences("privacy_consent", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun newInstallAndUnknownConsentRevisionNeverAuthorizeAccess() {
        assertFalse(ConsentStore.hasConsent(context))
        assertTrue(ConsentStore.isPaused(context))
        context.getSharedPreferences("privacy_consent", Context.MODE_PRIVATE).edit()
            .putInt("accepted_revision", 999).commit()
        assertFalse(ConsentStore.hasConsent(context))
    }

    @Test fun grantingPausingAndRevokingPreserveUserControl() {
        ConsentStore.grant(context)
        assertTrue(ConsentStore.hasConsent(context))
        assertFalse(ConsentStore.isPaused(context))
        ConsentStore.setPaused(context, true)
        assertTrue(ConsentStore.isPaused(context))
        ConsentStore.revoke(context)
        assertFalse(ConsentStore.hasConsent(context))
        assertTrue(ConsentStore.isPaused(context))
    }

    @Test fun upgradeErasesLegacyAvatarDataButPreservesChosenRules() {
        BlockedContactsRepository.addContact(context, "Prueba")
        val prefs = context.getSharedPreferences("whatsapp_blocker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("contact_avatar_hashes_Prueba", setOf("0123456789abcdef"))
            .putString("pending_avatar_enrollment_contact", "Prueba").commit()
        BlockedContactsRepository.removeLegacyAvatarData(context)
        assertFalse(prefs.contains("contact_avatar_hashes_Prueba"))
        assertFalse(prefs.contains("pending_avatar_enrollment_contact"))
        assertEquals(setOf("Prueba"), BlockedContactsRepository.getBlockedContacts(context))
    }

    @Test fun removeOneContactErasesItsRulesAndCounters() {
        BlockedContactsRepository.addContact(context, "Prueba")
        BlockedContactsRepository.setContactQuotaMinutes(context, "Prueba", 5)
        BlockedContactsRepository.addContactQuotaUsage(context, "Prueba", 1000)
        BlockedContactsRepository.removeContact(context, "Prueba")
        assertTrue(BlockedContactsRepository.getBlockedContacts(context).isEmpty())
        val keys = context.getSharedPreferences("whatsapp_blocker_prefs", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.none { it.endsWith("Prueba") })
    }

    @Test fun clearAllErasesNamesRulesAndUsageAndCannotRecreateDeletedContact() {
        BlockedContactsRepository.addContact(context, "Prueba")
        BlockedContactsRepository.setContactQuotaMinutes(context, "Prueba", 5)
        BlockedContactsRepository.addContactQuotaUsage(context, "Prueba", 1000)
        BlockedContactsRepository.clearAll(context)
        BlockedContactsRepository.addContactQuotaUsage(context, "Prueba", 1000)
        assertTrue(context.getSharedPreferences("whatsapp_blocker_prefs", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun caseAliasesCannotCreateTwoConflictingRules() {
        assertTrue(BlockedContactsRepository.addContact(context, "Prueba"))
        assertFalse(BlockedContactsRepository.addContact(context, "PRUEBA"))
    }
}
