package com.diegouc3m.whatsappblock

/** Fail closed: titles alone cannot identify a chat or authorize a global action. */
internal object ChatSafety {
    fun canInspect(consented: Boolean, paused: Boolean, interactive: Boolean, locked: Boolean): Boolean =
        consented && !paused && interactive && !locked

    fun matchContact(
        packageName: String?,
        active: Boolean,
        focused: Boolean,
        applicationWindow: Boolean,
        visibleConversationTitle: String?,
        hasVisibleComposer: Boolean,
        configuredContacts: Set<String>
    ): String? {
        if (packageName !in WhatsAppPackages.ALL || !active || !focused || !applicationWindow ||
            !hasVisibleComposer || visibleConversationTitle.isNullOrBlank()) return null
        val matches = configuredContacts.filter { it.equals(visibleConversationTitle.trim(), ignoreCase = true) }
        return matches.singleOrNull()
    }
}
