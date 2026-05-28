package com.diegouc3m.whatsappblock

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.diegouc3m.whatsappblock.databinding.ActivityMainBinding
import com.diegouc3m.whatsappblock.ui.ContactListAdapter
import com.diegouc3m.whatsappblock.ui.ScheduleSlotAdapter
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContactListAdapter
    private lateinit var scheduleAdapter: ScheduleSlotAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupScheduleUI()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshContactList()
    }

    private fun setupRecyclerView() {
        adapter = ContactListAdapter { name ->
            BlockedContactsRepository.removeContact(this, name)
            refreshContactList()
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.rvContacts.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnAdd.setOnClickListener { addContact() }

        binding.etContactName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addContact()
                true
            } else false
        }
    }

    private fun setupScheduleUI() {
        scheduleAdapter = ScheduleSlotAdapter(
            onEditStart = { position, slot ->
                TimePickerDialog(this, { _, hour, minute ->
                    updateSlot(position, slot.copy(startHour = hour, startMinute = minute))
                }, slot.startHour, slot.startMinute, true).show()
            },
            onEditEnd = { position, slot ->
                TimePickerDialog(this, { _, hour, minute ->
                    updateSlot(position, slot.copy(endHour = hour, endMinute = minute))
                }, slot.endHour, slot.endMinute, true).show()
            },
            onDelete = { slot ->
                BlockedContactsRepository.removeScheduleSlot(this, slot)
                refreshScheduleSlots()
            }
        )
        binding.rvScheduleSlots.layoutManager = LinearLayoutManager(this)
        binding.rvScheduleSlots.adapter = scheduleAdapter

        val scheduleEnabled = BlockedContactsRepository.isScheduleEnabled(this)
        binding.switchSchedule.isChecked = scheduleEnabled
        binding.layoutScheduleTimes.visibility = if (scheduleEnabled) View.VISIBLE else View.GONE

        refreshScheduleSlots()

        binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            BlockedContactsRepository.setScheduleEnabled(this, isChecked)
            binding.layoutScheduleTimes.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnAddSlot.setOnClickListener {
            // Add a default slot 00:00 - 23:59
            BlockedContactsRepository.addScheduleSlot(this, TimeSlot(0, 0, 23, 59))
            refreshScheduleSlots()
        }
    }

    private fun updateSlot(position: Int, newSlot: TimeSlot) {
        val slots = BlockedContactsRepository.getScheduleSlots(this).toMutableList()
        if (position in slots.indices) {
            slots[position] = newSlot
            BlockedContactsRepository.setScheduleSlots(this, slots)
            refreshScheduleSlots()
        }
    }

    private fun refreshScheduleSlots() {
        val slots = BlockedContactsRepository.getScheduleSlots(this)
        scheduleAdapter.submitList(slots)
    }

    private fun addContact() {
        val name = binding.etContactName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.error_empty_name), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!BlockedContactsRepository.addContact(this, name)) {
            Snackbar.make(binding.root, getString(R.string.error_name_too_long), Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.etContactName.text?.clear()
        refreshContactList()
    }

    private fun refreshContactList() {
        val contacts = BlockedContactsRepository.getBlockedContacts(this).toList()
        adapter.submitSortedList(contacts)
        binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        binding.rvContacts.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = getString(
            if (enabled) R.string.service_status_active else R.string.service_status_inactive
        )
        binding.tvServiceStatus.setBackgroundColor(
            if (enabled) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt()
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${BlockerAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any {
            it.equals(expectedComponent, ignoreCase = true) ||
                it.equals("$packageName/.BlockerAccessibilityService", ignoreCase = true)
        }
    }
}
