package com.diegouc3m.whatsappblock

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.diegouc3m.whatsappblock.databinding.ActivityMainBinding
import com.diegouc3m.whatsappblock.ui.ContactListAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContactListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        BlockedContactsRepository.migrateGlobalScheduleIfNeeded(this)
        BlockedContactsRepository.removeLegacyAvatarData(this)
        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshContactList()
        adapter.resumeUsageUpdates()
    }

    override fun onStop() {
        adapter.pauseUsageUpdates()
        super.onStop()
    }

    private fun setupRecyclerView() {
        adapter = ContactListAdapter { name ->
            BlockedContactsRepository.removeContact(this, name)
            refreshContactList()
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvContacts.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnOpenSettings.setOnClickListener {
            if (ConsentStore.hasConsent(this)) openAccessibilitySettings() else showConsentDisclosure()
        }
        binding.btnPrivacy.setOnClickListener { startActivity(Intent(this, PrivacyActivity::class.java)) }
        binding.btnRevoke.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.revoke_title)
                .setMessage(R.string.revoke_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.btn_revoke) { _, _ ->
                    ConsentStore.revoke(this)
                    refreshServiceStatus()
                    Snackbar.make(binding.root, R.string.consent_revoked, Snackbar.LENGTH_LONG).show()
                }.show()
        }
        binding.btnDeleteData.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_data_title)
                .setMessage(R.string.delete_data_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.btn_delete_data) { _, _ ->
                    ConsentStore.revoke(this)
                    BlockedContactsRepository.clearAll(this)
                    binding.etContactName.text?.clear()
                    refreshContactList()
                    refreshServiceStatus()
                    Snackbar.make(binding.root, R.string.data_deleted, Snackbar.LENGTH_LONG).show()
                }.show()
        }
        binding.btnAdd.setOnClickListener { addContact() }
        binding.etContactName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addContact(); true } else false
        }
    }

    private fun showConsentDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setNegativeButton(R.string.disclosure_reject) { _, _ ->
                ConsentStore.revoke(this)
                refreshServiceStatus()
            }
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                ConsentStore.grant(this)
                refreshServiceStatus()
                openAccessibilitySettings()
            }.show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun addContact() {
        val name = binding.etContactName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Snackbar.make(binding.root, R.string.error_empty_name, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!BlockedContactsRepository.addContact(this, name)) {
            Snackbar.make(binding.root, R.string.error_name_too_long, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.etContactName.text?.clear()
        refreshContactList()
    }

    private fun refreshContactList() {
        val contacts = BlockedContactsRepository.getBlockedContacts(this)
            .map { ContactListAdapter.ContactItem(name = it) }
        adapter.submitSortedList(contacts)
        binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        binding.rvContacts.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshServiceStatus() {
        val consent = ConsentStore.hasConsent(this)
        val enabled = isAccessibilityServiceEnabled()
        val paused = ConsentStore.isPaused(this)
        val status = when {
            !consent -> R.string.service_status_no_consent
            !enabled -> R.string.service_status_inactive
            paused -> R.string.service_status_paused
            else -> R.string.service_status_active
        }
        binding.tvServiceStatus.setText(status)
        binding.tvServiceStatus.setBackgroundColor(
            if (consent && enabled && !paused) 0xFF283593.toInt() else 0xFF455A64.toInt()
        )
        // Do not let this programmatic update overwrite the stored pause preference.
        binding.switchPause.setOnCheckedChangeListener(null)
        binding.switchPause.isChecked = paused
        binding.switchPause.isEnabled = consent
        binding.switchPause.setOnCheckedChangeListener { _, checked ->
            ConsentStore.setPaused(this, checked)
            refreshServiceStatus()
        }
        binding.btnRevoke.isEnabled = consent || enabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, BlockerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
