package com.diegouc3m.whatsappblock

import android.content.Context

object BlockedContactsRepository {

    private const val PREFS_NAME = "whatsapp_blocker_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"
    private const val KEY_AVATAR_HASH_CONTACTS = "avatar_hash_contacts"
    private const val KEY_PENDING_AVATAR_ENROLLMENT = "pending_avatar_enrollment"
    private const val KEY_PENDING_STATUS_MESSAGE = "pending_status_message"

    fun getBlockedContacts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CONTACTS, emptySet())?.toSet() ?: emptySet()
    }

    fun addContact(context: Context, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedContacts(context).toMutableSet()
        current.add(trimmedName)
        prefs.edit().putStringSet(KEY_CONTACTS, current).apply()
    }

    fun removeContact(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedContacts(context).toMutableSet()
        current.remove(name)
        val avatarContacts = getAvatarHashContacts(prefs).toMutableSet()
        avatarContacts.remove(name)
        prefs.edit()
            .putStringSet(KEY_CONTACTS, current)
            .putStringSet(KEY_AVATAR_HASH_CONTACTS, avatarContacts)
            .remove(avatarHashesKey(name))
            .apply()

        if (getPendingAvatarEnrollment(context) == name) {
            clearPendingAvatarEnrollment(context)
        }
    }

    fun addAvatarHash(context: Context, name: String, hash: Long) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serializedHashes = prefs.getStringSet(avatarHashesKey(trimmedName), emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        serializedHashes.add(hashToHex(hash))

        val avatarContacts = getAvatarHashContacts(prefs).toMutableSet()
        avatarContacts.add(trimmedName)

        prefs.edit()
            .putStringSet(avatarHashesKey(trimmedName), serializedHashes)
            .putStringSet(KEY_AVATAR_HASH_CONTACTS, avatarContacts)
            .apply()
    }

    fun getAvatarHashes(context: Context): Map<String, Set<Long>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getAvatarHashContacts(prefs).associateWith { name ->
            getAvatarHashesForContact(prefs, name)
        }.filterValues { it.isNotEmpty() }
    }

    fun getAllAvatarHashes(context: Context): Set<Long> {
        return getAvatarHashes(context).values.flatten().toSet()
    }

    fun requestAvatarEnrollment(context: Context, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_AVATAR_ENROLLMENT, trimmedName).apply()
    }

    fun getPendingAvatarEnrollment(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PENDING_AVATAR_ENROLLMENT, null)
    }

    fun clearPendingAvatarEnrollment(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PENDING_AVATAR_ENROLLMENT).apply()
    }

    fun setPendingStatusMessage(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_STATUS_MESSAGE, message).apply()
    }

    fun consumePendingStatusMessage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_PENDING_STATUS_MESSAGE, null)
        if (message != null) {
            prefs.edit().remove(KEY_PENDING_STATUS_MESSAGE).apply()
        }
        return message
    }

    private fun getAvatarHashesForContact(prefs: android.content.SharedPreferences, name: String): Set<Long> {
        return prefs.getStringSet(avatarHashesKey(name), emptySet())
            ?.mapNotNull(::hexToHash)
            ?.toSet()
            ?: emptySet()
    }

    private fun getAvatarHashContacts(prefs: android.content.SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_AVATAR_HASH_CONTACTS, emptySet())?.toSet() ?: emptySet()
    }

    private fun avatarHashesKey(name: String): String {
        val encodedName = name.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
        return "avatar_hashes_$encodedName"
    }

    private fun hashToHex(hash: Long): String {
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    private fun hexToHash(value: String): Long? {
        return try {
            java.lang.Long.parseUnsignedLong(value, 16)
        } catch (_: NumberFormatException) {
            null
        }
    }
}
