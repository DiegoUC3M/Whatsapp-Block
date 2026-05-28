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
    private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    private const val KEY_SCHEDULE_SLOTS = "schedule_slots"
    private const val KEY_SCHEDULE_SLOTS_ORDERED = "schedule_slots_ordered"
    private const val SLOT_SEPARATOR = "|"
    private const val MAX_CONTACT_NAME_LENGTH = 100

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
        prefs(context).edit().putStringSet(KEY_CONTACTS, current).apply()
    }

    // --- Schedule ---

    fun isScheduleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)
    }

    fun setScheduleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    fun getScheduleSlots(context: Context): List<TimeSlot> {
        // Try ordered storage first, fall back to legacy StringSet for migration
        val ordered = prefs(context).getString(KEY_SCHEDULE_SLOTS_ORDERED, null)
        if (ordered != null) {
            if (ordered.isEmpty()) return emptyList()
            return ordered.split(SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
        }
        // Migrate from legacy StringSet storage
        val raw = prefs(context).getStringSet(KEY_SCHEDULE_SLOTS, emptySet()) ?: emptySet()
        val slots = raw.mapNotNull { TimeSlot.deserialize(it) }
            .sortedBy { it.startHour * 60 + it.startMinute }
        if (slots.isNotEmpty()) {
            setScheduleSlots(context, slots)
        }
        return slots
    }

    fun setScheduleSlots(context: Context, slots: List<TimeSlot>) {
        val serialized = slots.joinToString(SLOT_SEPARATOR) { it.serialize() }
        prefs(context).edit()
            .putString(KEY_SCHEDULE_SLOTS_ORDERED, serialized)
            .remove(KEY_SCHEDULE_SLOTS) // Remove legacy key
            .apply()
    }

    fun addScheduleSlot(context: Context, slot: TimeSlot) {
        val current = getScheduleSlots(context).toMutableList()
        current.add(slot)
        setScheduleSlots(context, current)
    }

    fun removeScheduleSlot(context: Context, slot: TimeSlot) {
        val current = getScheduleSlots(context).toMutableList()
        current.remove(slot)
        setScheduleSlots(context, current)
    }

    /**
     * Returns true if blocking should be active right now based on schedule settings.
     * If schedule is disabled, always returns true (blocking always active).
     * If schedule is enabled, returns true if current time is within ANY of the time slots.
     */
    fun isWithinSchedule(context: Context): Boolean {
        if (!isScheduleEnabled(context)) return true
        val slots = getScheduleSlots(context)
        if (slots.isEmpty()) return true // No slots configured = always active
        return slots.any { it.isCurrentlyActive() }
    }

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
