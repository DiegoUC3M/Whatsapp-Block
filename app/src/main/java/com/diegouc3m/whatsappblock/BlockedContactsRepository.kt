package com.diegouc3m.whatsappblock

import android.content.Context

object BlockedContactsRepository {

    private const val PREFS_NAME = "whatsapp_blocker_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"

    fun getBlockedContacts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CONTACTS, emptySet()) ?: emptySet()
    }

    fun addContact(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedContacts(context).toMutableSet()
        current.add(name.trim())
        prefs.edit().putStringSet(KEY_CONTACTS, current).apply()
    }

    fun removeContact(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedContacts(context).toMutableSet()
        current.remove(name)
        prefs.edit().putStringSet(KEY_CONTACTS, current).apply()
    }
}
