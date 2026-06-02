package com.diegouc3m.whatsappblock

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
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContactListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Migrate legacy global schedule to per-contact
        BlockedContactsRepository.migrateGlobalScheduleIfNeeded(this)

        setupRecyclerView()
        setupListeners()
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
        binding.btnEnrollAvatar.setOnClickListener { armAvatarEnrollment() }

        binding.etContactName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addContact()
                true
            } else false
        }
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
        val contactItems = contacts.map { contact ->
            ContactListAdapter.ContactItem(
                name = contact,
                avatarHashes = BlockedContactsRepository.getContactAvatarHashes(this, contact).sorted()
            )
        }
        adapter.submitSortedList(contactItems)
        binding.tvEmpty.visibility = if (contactItems.isEmpty()) View.VISIBLE else View.GONE
        binding.rvContacts.visibility = if (contactItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun armAvatarEnrollment() {
        val name = binding.etContactName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.error_empty_name), Snackbar.LENGTH_SHORT).show()
            return
        }

        val blockedContacts = BlockedContactsRepository.getBlockedContacts(this)
        if (name !in blockedContacts && !BlockedContactsRepository.addContact(this, name)) {
            Snackbar.make(binding.root, getString(R.string.error_name_too_long), Snackbar.LENGTH_SHORT).show()
            return
        }

        BlockedContactsRepository.setPendingAvatarEnrollmentContact(this, name)
        refreshContactList()

        val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")

        if (launchIntent != null) {
            startActivity(launchIntent)
        }

        Snackbar.make(
            binding.root,
            getString(R.string.avatar_enrollment_armed, name),
            Snackbar.LENGTH_LONG
        ).show()
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
