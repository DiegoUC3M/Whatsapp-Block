package com.diegouc3m.whatsappblock

import org.junit.Assert.*
import org.junit.Test

class ChatSafetyTest {
    private val contacts = setOf("Diego")

    @Test fun onlyAnActiveFocusedWhatsAppConversationCanMatch() {
        for (pkg in WhatsAppPackages.ALL) {
            assertEquals("Diego", match(pkg = pkg))
        }
        for (pkg in listOf(null, "com.android.settings", "com.other.chat", "com.whatsapp.debug")) {
            assertNull(match(pkg = pkg))
        }
    }

    @Test fun queuedWhatsAppEventCannotAuthorizeForeignOrUnfocusedRoot() {
        assertNull(match(pkg = "com.android.settings"))
        assertNull(match(active = false))
        assertNull(match(focused = false))
        assertNull(match(application = false))
    }

    @Test fun aNameWithoutBothConversationTitleAndComposerFailsClosed() {
        // A chat list, a contact profile or a message containing the name is insufficient.
        assertNull(match(title = null))
        assertNull(match(composer = false))
        assertNull(match(title = "WhatsApp"))
        assertNull(match(title = "Mensaje para Diego"))
        assertNull(match(title = "Otro chat"))
    }

    @Test fun matchesExactDisplayedNameAndRejectsAmbiguousConfiguredAliases() {
        assertEquals("Diego", match(title = "diego"))
        assertNull(ChatSafety.matchContact(
            WhatsAppPackages.WHATSAPP, true, true, true, "Diego", true,
            setOf("Diego", "DIEGO")
        ))
    }

    @Test fun consentPauseLockAndScreenStateGateAllInspection() {
        assertTrue(ChatSafety.canInspect(true, false, true, false))
        assertFalse(ChatSafety.canInspect(false, false, true, false))
        assertFalse(ChatSafety.canInspect(true, true, true, false))
        assertFalse(ChatSafety.canInspect(true, false, false, false))
        assertFalse(ChatSafety.canInspect(true, false, true, true))
    }

    private fun match(
        pkg: String? = WhatsAppPackages.WHATSAPP,
        active: Boolean = true,
        focused: Boolean = true,
        application: Boolean = true,
        title: String? = "Diego",
        composer: Boolean = true
    ): String? = ChatSafety.matchContact(pkg, active, focused, application, title, composer, contacts)
}
