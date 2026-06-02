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

/**
 * A group of weekdays that share the same set of time slots.
 *
 * Days are stored as indices 0..6 where 0 = Monday and 6 = Sunday.
 * A given weekday must belong to at most one group within a contact's schedule.
 */
data class ScheduleGroup(
    val days: Set<Int>,
    val slots: List<TimeSlot>
) {
    fun serialize(): String {
        val daysPart = days.sorted().joinToString(DAY_SEPARATOR) { it.toString() }
        val slotsPart = slots.joinToString(SLOT_SEPARATOR) { it.serialize() }
        return "$daysPart$DAY_SLOT_SEPARATOR$slotsPart"
    }

    companion object {
        const val DAY_SEPARATOR = ","
        const val SLOT_SEPARATOR = "|"
        const val DAY_SLOT_SEPARATOR = "#"

        /** Number of weekdays. Index 0 = Monday … 6 = Sunday. */
        const val DAY_COUNT = 7

        fun deserialize(s: String): ScheduleGroup {
            val parts = s.split(DAY_SLOT_SEPARATOR, limit = 2)
            val daysPart = parts.getOrNull(0).orEmpty()
            val slotsPart = parts.getOrNull(1).orEmpty()

            val days = if (daysPart.isBlank()) {
                emptySet()
            } else {
                daysPart.split(DAY_SEPARATOR)
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 0 until DAY_COUNT }
                    .toSet()
            }

            val slots = if (slotsPart.isBlank()) {
                emptyList()
            } else {
                slotsPart.split(SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
            }

            return ScheduleGroup(days, slots)
        }

        /** Returns the current weekday as an index 0 = Monday … 6 = Sunday. */
        fun currentDayIndex(): Int {
            val cal = java.util.Calendar.getInstance()
            return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                else -> 6 // Sunday
            }
        }
    }
}

object BlockedContactsRepository {

    private const val PREFS_NAME = "whatsapp_blocker_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"
    private const val MAX_CONTACT_NAME_LENGTH = 100
    private const val AVATAR_HASH_LENGTH = 16
    private const val KEY_PENDING_AVATAR_ENROLLMENT_CONTACT = "pending_avatar_enrollment_contact"

    // Per-contact schedule key prefixes
    private const val KEY_PREFIX_SCHEDULE_ENABLED = "contact_schedule_enabled_"
    private const val KEY_PREFIX_SCHEDULE_SLOTS = "contact_schedule_slots_"
    private const val KEY_PREFIX_SCHEDULE_GROUPS = "contact_schedule_groups_"
    private const val KEY_PREFIX_AVATAR_HASHES = "contact_avatar_hashes_"

    private const val GROUP_SEPARATOR = ";"

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
        val pendingEnrollment = getPendingAvatarEnrollmentContact(context)
        val editor = prefs(context).edit()
        // Also remove per-contact schedule data
        editor
            .putStringSet(KEY_CONTACTS, current)
            .remove(KEY_PREFIX_SCHEDULE_ENABLED + name)
            .remove(KEY_PREFIX_SCHEDULE_SLOTS + name)
            .remove(KEY_PREFIX_SCHEDULE_GROUPS + name)
            .remove(KEY_PREFIX_AVATAR_HASHES + name)
        if (pendingEnrollment?.equals(name, ignoreCase = true) == true) {
            editor.remove(KEY_PENDING_AVATAR_ENROLLMENT_CONTACT)
        }
        editor.apply()
    }

    fun getContactAvatarHashes(context: Context, contact: String): Set<String> {
        return prefs(context).getStringSet(KEY_PREFIX_AVATAR_HASHES + contact, emptySet()) ?: emptySet()
    }

    fun getBlockedContactsAvatarHashes(context: Context): Map<String, Set<String>> {
        val contacts = getBlockedContacts(context)
        if (contacts.isEmpty()) return emptyMap()
        return contacts.associateWith { getContactAvatarHashes(context, it) }
    }

    fun addContactAvatarHash(context: Context, contact: String, hash: String): Boolean {
        val normalizedHash = normalizeAvatarHash(hash) ?: return false
        val contacts = getBlockedContacts(context)
        if (contact !in contacts) return false
        val current = getContactAvatarHashes(context, contact).toMutableSet()
        current.add(normalizedHash)
        prefs(context).edit().putStringSet(KEY_PREFIX_AVATAR_HASHES + contact, current).apply()
        return true
    }

    fun getPendingAvatarEnrollmentContact(context: Context): String? {
        val raw = prefs(context).getString(KEY_PENDING_AVATAR_ENROLLMENT_CONTACT, null)
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setPendingAvatarEnrollmentContact(context: Context, contact: String?) {
        val editor = prefs(context).edit()
        val normalizedContact = contact?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedContact == null) {
            editor.remove(KEY_PENDING_AVATAR_ENROLLMENT_CONTACT)
        } else {
            editor.putString(KEY_PENDING_AVATAR_ENROLLMENT_CONTACT, normalizedContact)
        }
        editor.apply()
    }

    private fun normalizeAvatarHash(hash: String): String? {
        val trimmed = hash.trim().lowercase()
        if (trimmed.length != AVATAR_HASH_LENGTH) return null
        return if (trimmed.all { it in '0'..'9' || it in 'a'..'f' }) trimmed else null
    }

    // --- Per-Contact Schedule ---

    fun isContactScheduleEnabled(context: Context, contact: String): Boolean {
        return prefs(context).getBoolean(KEY_PREFIX_SCHEDULE_ENABLED + contact, false)
    }

    fun setContactScheduleEnabled(context: Context, contact: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX_SCHEDULE_ENABLED + contact, enabled).apply()
    }

    // --- Per-Contact Schedule Groups ---

    /**
     * Returns the schedule groups configured for the contact.
     * Each group bundles a set of weekdays with a list of time slots.
     */
    fun getContactScheduleGroups(context: Context, contact: String): List<ScheduleGroup> {
        val raw = prefs(context).getString(KEY_PREFIX_SCHEDULE_GROUPS + contact, null)
            ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(GROUP_SEPARATOR)
            .filter { it.isNotEmpty() }
            .map { ScheduleGroup.deserialize(it) }
    }

    fun setContactScheduleGroups(context: Context, contact: String, groups: List<ScheduleGroup>) {
        val serialized = groups.joinToString(GROUP_SEPARATOR) { it.serialize() }
        prefs(context).edit()
            .putString(KEY_PREFIX_SCHEDULE_GROUPS + contact, serialized)
            .apply()
    }

    /** Adds a new empty schedule group (no days, no slots) for the contact. */
    fun addContactScheduleGroup(context: Context, contact: String) {
        val current = getContactScheduleGroups(context, contact).toMutableList()
        current.add(ScheduleGroup(emptySet(), emptyList()))
        setContactScheduleGroups(context, contact, current)
    }

    /** Removes the schedule group at the given index. */
    fun removeContactScheduleGroup(context: Context, contact: String, groupIndex: Int) {
        val current = getContactScheduleGroups(context, contact).toMutableList()
        if (groupIndex in current.indices) {
            current.removeAt(groupIndex)
            setContactScheduleGroups(context, contact, current)
        }
    }

    /**
     * Toggles a weekday for the group at [groupIndex].
     * Enforces that a weekday belongs to at most one group: enabling a day removes
     * it from every other group first.
     */
    fun setContactScheduleGroupDay(
        context: Context,
        contact: String,
        groupIndex: Int,
        day: Int,
        enabled: Boolean
    ) {
        val current = getContactScheduleGroups(context, contact).toMutableList()
        if (groupIndex !in current.indices) return

        val updated = current.mapIndexed { index, group ->
            val days = group.days.toMutableSet()
            if (index == groupIndex) {
                if (enabled) days.add(day) else days.remove(day)
            } else if (enabled) {
                // Ensure uniqueness: a day can only live in one group.
                days.remove(day)
            }
            group.copy(days = days)
        }
        setContactScheduleGroups(context, contact, updated)
    }

    /** Adds a time slot to the group at [groupIndex]. */
    fun addContactScheduleGroupSlot(context: Context, contact: String, groupIndex: Int, slot: TimeSlot) {
        updateGroupSlots(context, contact, groupIndex) { it.add(slot) }
    }

    /** Replaces the slot at [slotIndex] within the group at [groupIndex]. */
    fun updateContactScheduleGroupSlot(
        context: Context,
        contact: String,
        groupIndex: Int,
        slotIndex: Int,
        slot: TimeSlot
    ) {
        updateGroupSlots(context, contact, groupIndex) {
            if (slotIndex in it.indices) it[slotIndex] = slot
        }
    }

    /** Removes the slot at [slotIndex] within the group at [groupIndex]. */
    fun removeContactScheduleGroupSlot(context: Context, contact: String, groupIndex: Int, slotIndex: Int) {
        updateGroupSlots(context, contact, groupIndex) {
            if (slotIndex in it.indices) it.removeAt(slotIndex)
        }
    }

    private inline fun updateGroupSlots(
        context: Context,
        contact: String,
        groupIndex: Int,
        mutate: (MutableList<TimeSlot>) -> Unit
    ) {
        val current = getContactScheduleGroups(context, contact).toMutableList()
        if (groupIndex !in current.indices) return
        val slots = current[groupIndex].slots.toMutableList()
        mutate(slots)
        current[groupIndex] = current[groupIndex].copy(slots = slots)
        setContactScheduleGroups(context, contact, current)
    }

    /**
     * Returns true if blocking should be active right now for the given contact.
     * If schedule is disabled for the contact, always returns true (blocking always active).
     * If schedule is enabled:
     *   - No groups configured = always active.
     *   - Otherwise, only the group containing today's weekday applies. If no group
     *     covers today, blocking is inactive. If today's group has no slots, blocking
     *     is active all day; otherwise it is active only within the group's time slots.
     */
    fun isWithinScheduleForContact(context: Context, contact: String): Boolean {
        if (!isContactScheduleEnabled(context, contact)) return true
        val groups = getContactScheduleGroups(context, contact)
        if (groups.isEmpty()) return true // No groups configured = always active

        val today = ScheduleGroup.currentDayIndex()
        val group = groups.firstOrNull { today in it.days } ?: return false
        if (group.slots.isEmpty()) return true // Day selected with no slots = active all day
        return group.slots.any { it.isCurrentlyActive() }
    }

    /**
     * Migrates legacy schedule data to the per-contact group model.
     * Should be called once on app startup. Handles two levels of legacy data:
     *   1. The old global schedule (applied to all contacts).
     *   2. The old per-contact flat slot list (converted to a single all-days group).
     */
    fun migrateGlobalScheduleIfNeeded(context: Context) {
        migrateLegacyGlobalSchedule(context)
        migrateLegacyPerContactSlots(context)
    }

    private fun migrateLegacyGlobalSchedule(context: Context) {
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
                    val serialized = globalSlots.joinToString(ScheduleGroup.SLOT_SEPARATOR) { it.serialize() }
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

    private fun migrateLegacyPerContactSlots(context: Context) {
        val p = prefs(context)
        val allDays = (0 until ScheduleGroup.DAY_COUNT).toSet()
        val editor = p.edit()
        var changed = false

        for (contact in getBlockedContacts(context)) {
            val slotsKey = KEY_PREFIX_SCHEDULE_SLOTS + contact
            if (!p.contains(slotsKey)) continue
            // Only migrate if no group config already exists for this contact.
            if (!p.contains(KEY_PREFIX_SCHEDULE_GROUPS + contact)) {
                val slots = getLegacyContactSlots(context, contact)
                if (slots.isNotEmpty()) {
                    val group = ScheduleGroup(allDays, slots)
                    editor.putString(KEY_PREFIX_SCHEDULE_GROUPS + contact, group.serialize())
                }
            }
            editor.remove(slotsKey)
            changed = true
        }

        if (changed) editor.apply()
    }

    private fun getLegacyContactSlots(context: Context, contact: String): List<TimeSlot> {
        val raw = prefs(context).getString(KEY_PREFIX_SCHEDULE_SLOTS + contact, null)
            ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(ScheduleGroup.SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
    }

    private fun getGlobalSlotsLegacy(context: Context): List<TimeSlot> {
        val p = prefs(context)
        val ordered = p.getString(KEY_SCHEDULE_SLOTS_ORDERED_LEGACY, null)
        if (ordered != null) {
            if (ordered.isEmpty()) return emptyList()
            return ordered.split(ScheduleGroup.SLOT_SEPARATOR).mapNotNull { TimeSlot.deserialize(it) }
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
