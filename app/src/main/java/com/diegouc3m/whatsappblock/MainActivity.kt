package com.diegouc3m.whatsappblock

import android.content.Intent
import android.os.Build
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

        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshContactList()
        showPendingStatusMessage()
    }

    private fun setupRecyclerView() {
        adapter = ContactListAdapter(
            onDelete = { name ->
                BlockedContactsRepository.removeContact(this, name)
                refreshContactList()
            },
            onEnrollAvatar = { name ->
                startAvatarEnrollment(name)
            },
            onDeleteAvatar = { name ->
                BlockedContactsRepository.removeAvatarHashes(this, name)
                refreshContactList()
                Snackbar.make(
                    binding.root,
                    getString(R.string.avatar_hash_removed, name),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        )
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

    private fun addContact() {
        val name = binding.etContactName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.error_empty_name), Snackbar.LENGTH_SHORT).show()
            return
        }
        BlockedContactsRepository.addContact(this, name)
        binding.etContactName.text?.clear()
        refreshContactList()
    }

    private fun startAvatarEnrollment(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Snackbar.make(
                binding.root,
                getString(R.string.avatar_enrollment_requires_api_30),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Snackbar.make(
                binding.root,
                getString(R.string.avatar_enrollment_requires_service),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        BlockedContactsRepository.requestAvatarEnrollment(this, name)
        Snackbar.make(
            binding.root,
            getString(R.string.avatar_enrollment_started, name),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun refreshContactList() {
        val contacts = BlockedContactsRepository.getBlockedContactsWithHashes(this)
        adapter.submitList(contacts)
        binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        binding.rvContacts.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPendingStatusMessage() {
        val message = BlockedContactsRepository.consumePendingStatusMessage(this) ?: return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
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
        val expectedComponent = "$packageName/.BlockerAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any {
            it.equals(expectedComponent, ignoreCase = true)
        }
    }
}
