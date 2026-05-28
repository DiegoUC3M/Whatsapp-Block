package com.diegouc3m.whatsappblock

import android.content.Context
import android.content.SharedPreferences

/**
 * Represents a time slot with start and end times in hours and minutes.
 * Serialized as "HH:MM-HH:MM" for storage.
 */
data class TimeSlot(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    fun serialize(): String = String.format("%02d:%02d-%02d:%02d", startHour, startMinute, endHour, endMinute)

    fun isCurrentlyActive(): Boolean {
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // Overnight range: e.g. 22:00 - 06:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    companion object {
        fun deserialize(s: String): TimeSlot? {
            val parts = s.split("-")
            if (parts.size != 2) return null
            val start = parts[0].split(":")
            val end = parts[1].split(":")
            if (start.size != 2 || end.size != 2) return null
            return try {
                TimeSlot(start[0].toInt(), start[1].toInt(), end[0].toInt(), end[1].toInt())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

object BlockedContactsRepository {

    private const val PREFS_NAME = "whatsapp_blocker_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"
    private const val SLOT_SEPARATOR = "|"
    private const val MAX_CONTACT_NAME_LENGTH = 100

    // Per-contact schedule key prefixes
    private const val KEY_PREFIX_SCHEDULE_ENABLED = "contact_schedule_enabled_"
    private const val KEY_PREFIX_SCHEDULE_SLOTS = "contact_schedule_slots_"

    // Legacy global schedule keys (for migration)
    private const val KEY_SCHEDULE_ENABLED_LEGACY = "schedule_enabled"
    private const val KEY_SCHEDULE_SLOTS_LEGACY = "schedule_slots"
    private const val KEY_SCHEDULE_SLOTS_ORDERED_LEGACY = "schedule_slots_ordered"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBlockedContacts(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_CONTACTS, emptySet()) ?: emptySet()
    }

    fun addContact(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CONTACT_NAME_LENGTH) return false
        val current = getBlockedContacts(context).toMutableSet()
        current.add(trimmed)
        prefs(context).edit().putStringSet(KEY_CONTACTS, current).apply()
        return true
    }

    fun removeContact(context: Context, name: String) {
        val current = getBlockedContacts(context).toMutableSet()
        current.remove(name)
        // Also remove per-contact schedule data
        prefs(context).edit()
            .putStringSet(KEY_CONTACTS, current)
            .remove(KEY_PREFIX_SCHEDULE_ENABLED + name)
            .remove(KEY_PREFIX_SCHEDULE_SLOTS + name)
            .apply()
    }

    // --- Per-Contact Schedule ---

    fun isContactScheduleEnabled(context: Context, contact: String): Boolean {
        return prefs(context).getBoolean(KEY_PREFIX_SCHEDULE_ENABLED + contact, false)
    }

    fun setContactScheduleEnabled(context: Context, contact: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX_SCHEDULE_ENABLED + contact, enabled).apply()
    }

    fun getContactScheduleSlots(context: Context, contact: String): List<TimeSlot> {
        val raw = prefs(context).getString(KEY_PREFIX_SCHEDULE_SLOTS + contact, null)
            ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
    }

    fun setContactScheduleSlots(context: Context, contact: String, slots: List<TimeSlot>) {
        val serialized = slots.joinToString(SLOT_SEPARATOR) { it.serialize() }
        prefs(context).edit()
            .putString(KEY_PREFIX_SCHEDULE_SLOTS + contact, serialized)
            .apply()
    }

    fun addContactScheduleSlot(context: Context, contact: String, slot: TimeSlot) {
        val current = getContactScheduleSlots(context, contact).toMutableList()
        current.add(slot)
        setContactScheduleSlots(context, contact, current)
    }

    fun removeContactScheduleSlot(context: Context, contact: String, slot: TimeSlot) {
        val current = getContactScheduleSlots(context, contact).toMutableList()
        current.remove(slot)
        setContactScheduleSlots(context, contact, current)
    }

    /**
     * Returns true if blocking should be active right now for the given contact.
     * If schedule is disabled for the contact, always returns true (blocking always active).
     * If schedule is enabled, returns true only if current time is within ANY of the contact's time slots.
     */
    fun isWithinScheduleForContact(context: Context, contact: String): Boolean {
        if (!isContactScheduleEnabled(context, contact)) return true
        val slots = getContactScheduleSlots(context, contact)
        if (slots.isEmpty()) return true // No slots configured = always active
        return slots.any { it.isCurrentlyActive() }
    }

    /**
     * Migrates legacy global schedule data to per-contact schedules.
     * Should be called once on app startup. Applies the global schedule to all existing contacts.
     */
    fun migrateGlobalScheduleIfNeeded(context: Context) {
        val p = prefs(context)
        // Check if legacy global schedule exists
        if (!p.contains(KEY_SCHEDULE_ENABLED_LEGACY) &&
            !p.contains(KEY_SCHEDULE_SLOTS_ORDERED_LEGACY) &&
            !p.contains(KEY_SCHEDULE_SLOTS_LEGACY)) {
            return // Nothing to migrate
        }

        val globalEnabled = p.getBoolean(KEY_SCHEDULE_ENABLED_LEGACY, false)
        val globalSlots = getGlobalSlotsLegacy(context)
        val contacts = getBlockedContacts(context)

        val editor = p.edit()
        // Apply global schedule to each contact that doesn't already have per-contact config
        for (contact in contacts) {
            if (!p.contains(KEY_PREFIX_SCHEDULE_ENABLED + contact)) {
                editor.putBoolean(KEY_PREFIX_SCHEDULE_ENABLED + contact, globalEnabled)
                if (globalSlots.isNotEmpty()) {
                    val serialized = globalSlots.joinToString(SLOT_SEPARATOR) { it.serialize() }
                    editor.putString(KEY_PREFIX_SCHEDULE_SLOTS + contact, serialized)
                }
            }
        }
        // Remove legacy keys
        editor.remove(KEY_SCHEDULE_ENABLED_LEGACY)
        editor.remove(KEY_SCHEDULE_SLOTS_LEGACY)
        editor.remove(KEY_SCHEDULE_SLOTS_ORDERED_LEGACY)
        editor.apply()
    }

    private fun getGlobalSlotsLegacy(context: Context): List<TimeSlot> {
        val p = prefs(context)
        val ordered = p.getString(KEY_SCHEDULE_SLOTS_ORDERED_LEGACY, null)
        if (ordered != null) {
            if (ordered.isEmpty()) return emptyList()
            return ordered.split(SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
        }
        val raw = p.getStringSet(KEY_SCHEDULE_SLOTS_LEGACY, emptySet()) ?: emptySet()
        return raw.mapNotNull { TimeSlot.deserialize(it) }
    }

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
