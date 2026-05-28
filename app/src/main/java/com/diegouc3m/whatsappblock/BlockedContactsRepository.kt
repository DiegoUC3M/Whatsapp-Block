package com.diegouc3m.whatsappblock

import android.content.Context
import android.content.SharedPreferences

object BlockedContactsRepository {

    private const val PREFS_NAME = "whatsapp_blocker_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"
    private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    private const val KEY_SCHEDULE_START_HOUR = "schedule_start_hour"
    private const val KEY_SCHEDULE_START_MINUTE = "schedule_start_minute"
    private const val KEY_SCHEDULE_END_HOUR = "schedule_end_hour"
    private const val KEY_SCHEDULE_END_MINUTE = "schedule_end_minute"
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

    fun getScheduleStart(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_SCHEDULE_START_HOUR, 0), p.getInt(KEY_SCHEDULE_START_MINUTE, 0))
    }

    fun getScheduleEnd(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_SCHEDULE_END_HOUR, 23), p.getInt(KEY_SCHEDULE_END_MINUTE, 59))
    }

    fun setScheduleStart(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_SCHEDULE_START_HOUR, hour)
            .putInt(KEY_SCHEDULE_START_MINUTE, minute)
            .apply()
    }

    fun setScheduleEnd(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_SCHEDULE_END_HOUR, hour)
            .putInt(KEY_SCHEDULE_END_MINUTE, minute)
            .apply()
    }

    /**
     * Returns true if blocking should be active right now based on schedule settings.
     * If schedule is disabled, always returns true (blocking always active).
     */
    fun isWithinSchedule(context: Context): Boolean {
        if (!isScheduleEnabled(context)) return true

        val (startH, startM) = getScheduleStart(context)
        val (endH, endM) = getScheduleEnd(context)

        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinutes = startH * 60 + startM
        val endMinutes = endH * 60 + endM

        return if (startMinutes <= endMinutes) {
            // Normal range: e.g. 08:00 - 22:00
            currentMinutes in startMinutes..endMinutes
        } else {
            // Overnight range: e.g. 22:00 - 06:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
