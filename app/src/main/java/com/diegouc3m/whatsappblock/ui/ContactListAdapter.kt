package com.diegouc3m.whatsappblock.ui

import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.R
import com.diegouc3m.whatsappblock.BlockedContactsRepository
import com.diegouc3m.whatsappblock.BlockMode
import com.diegouc3m.whatsappblock.TimeSlot
import com.diegouc3m.whatsappblock.databinding.ItemContactBinding

class ContactListAdapter(
    private val onDelete: (String) -> Unit
) : ListAdapter<ContactListAdapter.ContactItem, ContactListAdapter.ViewHolder>(ContactDiffCallback()) {

    private companion object {
        /** How often the expanded usage counters refresh from stored usage. */
        const val USAGE_TICK_MS = 1_000L
    }

    data class ContactItem(val name: String)

    private val expandedContacts = mutableSetOf<String>()
    private val visibleHolders = mutableSetOf<ViewHolder>()
    private var usageUpdatesActive = true

    fun pauseUsageUpdates() {
        usageUpdatesActive = false
        visibleHolders.forEach { it.stopUsageTicker() }
    }

    fun resumeUsageUpdates() {
        usageUpdatesActive = true
        visibleHolders.forEach { it.resumeUsageTicker() }
    }

    fun submitSortedList(list: List<ContactItem>) {
        submitList(list.sortedBy { it.name.lowercase() })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        visibleHolders.remove(holder)
        holder.stopUsageTicker()
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        visibleHolders.add(holder)
        holder.resumeUsageTicker()
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        visibleHolders.remove(holder)
        holder.stopUsageTicker()
        super.onViewDetachedFromWindow(holder)
    }

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var groupAdapter: ScheduleGroupAdapter? = null
        private var usageTicker: Runnable? = null
        private var boundContact: String? = null

        fun bind(item: ContactItem) {
            val context = binding.root.context
            val name = item.name
            boundContact = name
            binding.tvContactName.text = name
            binding.btnDelete.setOnClickListener { onDelete(name) }

            // Expand/collapse
            val isExpanded = name in expandedContacts
            binding.layoutContactSchedule.visibility = if (isExpanded) View.VISIBLE else View.GONE

            binding.btnExpandSchedule.setOnClickListener {
                if (name in expandedContacts) {
                    expandedContacts.remove(name)
                } else {
                    expandedContacts.add(name)
                }
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos)
                }
            }

            if (!isExpanded) {
                stopUsageTicker()
                return
            }

            // Blocking on/off toggle
            val blockingEnabled = BlockedContactsRepository.isContactBlockingEnabled(context, name)
            binding.switchContactBlockingEnabled.setOnCheckedChangeListener(null)
            binding.switchContactBlockingEnabled.isChecked = blockingEnabled
            binding.switchContactBlockingEnabled.setOnCheckedChangeListener { _, isChecked ->
                BlockedContactsRepository.setContactBlockingEnabled(context, name, isChecked)
            }

            // Block mode selection
            val blockMode = BlockedContactsRepository.getContactBlockMode(context, name)
            binding.rgBlockMode.setOnCheckedChangeListener(null)
            binding.rbModeSchedule.isChecked = blockMode == BlockMode.SCHEDULE
            binding.rbModeQuota.isChecked = blockMode == BlockMode.QUOTA
            applyModeVisibility(blockMode)
            binding.rgBlockMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = if (checkedId == R.id.rbModeQuota) BlockMode.QUOTA else BlockMode.SCHEDULE
                BlockedContactsRepository.setContactBlockMode(context, name, mode)
                applyModeVisibility(mode)
            }

            // Hourly quota controls
            val quotaMinutes = BlockedContactsRepository.getContactQuotaMinutes(context, name)
            binding.seekQuotaMinutes.setOnSeekBarChangeListener(null)
            binding.seekQuotaMinutes.progress = quotaMinutes
            binding.tvQuotaMinutes.text = context.getString(R.string.quota_minutes_label, quotaMinutes)
            updateUsageCounters(name)
            startUsageTicker(name)
            binding.seekQuotaMinutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.tvQuotaMinutes.text = context.getString(R.string.quota_minutes_label, progress)
                    if (fromUser) {
                        BlockedContactsRepository.setContactQuotaMinutes(context, name, progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            // Schedule switch
            val scheduleEnabled = BlockedContactsRepository.isContactScheduleEnabled(context, name)
            binding.switchContactSchedule.setOnCheckedChangeListener(null)
            binding.switchContactSchedule.isChecked = scheduleEnabled
            binding.layoutContactSlots.visibility = if (scheduleEnabled) View.VISIBLE else View.GONE

            binding.switchContactSchedule.setOnCheckedChangeListener { _, isChecked ->
                BlockedContactsRepository.setContactScheduleEnabled(context, name, isChecked)
                binding.layoutContactSlots.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            // Schedule groups RecyclerView
            if (binding.rvContactGroups.layoutManager == null) {
                binding.rvContactGroups.layoutManager = LinearLayoutManager(context)
            }
            val adapter = ScheduleGroupAdapter(
                onToggleDay = { groupIndex, day, enabled ->
                    BlockedContactsRepository.setContactScheduleGroupDay(context, name, groupIndex, day, enabled)
                    refreshGroups(name)
                },
                onAddSlot = { groupIndex ->
                    BlockedContactsRepository.addContactScheduleGroupSlot(
                        context, name, groupIndex, TimeSlot(0, 0, 23, 59)
                    )
                    refreshGroups(name)
                },
                onEditSlotStart = { groupIndex, slotIndex, slot ->
                    TimePickerDialog(context, { _, hour, minute ->
                        BlockedContactsRepository.updateContactScheduleGroupSlot(
                            context, name, groupIndex, slotIndex,
                            slot.copy(startHour = hour, startMinute = minute)
                        )
                        refreshGroups(name)
                    }, slot.startHour, slot.startMinute, true).show()
                },
                onEditSlotEnd = { groupIndex, slotIndex, slot ->
                    TimePickerDialog(context, { _, hour, minute ->
                        BlockedContactsRepository.updateContactScheduleGroupSlot(
                            context, name, groupIndex, slotIndex,
                            slot.copy(endHour = hour, endMinute = minute)
                        )
                        refreshGroups(name)
                    }, slot.endHour, slot.endMinute, true).show()
                },
                onDeleteSlot = { groupIndex, slotIndex ->
                    BlockedContactsRepository.removeContactScheduleGroupSlot(context, name, groupIndex, slotIndex)
                    refreshGroups(name)
                },
                onDeleteGroup = { groupIndex ->
                    BlockedContactsRepository.removeContactScheduleGroup(context, name, groupIndex)
                    refreshGroups(name)
                }
            )
            groupAdapter = adapter
            binding.rvContactGroups.adapter = adapter
            refreshGroups(name)

            binding.btnAddContactGroup.setOnClickListener {
                BlockedContactsRepository.addContactScheduleGroup(context, name)
                refreshGroups(name)
            }
        }

        private fun applyModeVisibility(mode: BlockMode) {
            binding.layoutModeSchedule.visibility = if (mode == BlockMode.SCHEDULE) View.VISIBLE else View.GONE
            binding.layoutContactQuota.visibility = if (mode == BlockMode.QUOTA) View.VISIBLE else View.GONE
        }

        /** Refreshes the "used this hour" and "used today" counters from stored usage. */
        private fun updateUsageCounters(contact: String) {
            val context = binding.root.context
            val usedMs = BlockedContactsRepository.getContactQuotaUsedMs(context, contact)
            binding.tvQuotaUsed.text = context.getString(
                R.string.quota_used_label,
                (usedMs / 60_000L).toInt(),
                ((usedMs / 1_000L) % 60L).toInt()
            )
            val dailyMs = BlockedContactsRepository.getContactDailyUsedMs(context, contact)
            binding.tvDailyUsed.text = context.getString(
                R.string.daily_used_label,
                (dailyMs / 60_000L).toInt(),
                ((dailyMs / 1_000L) % 60L).toInt()
            )
        }

        /** Starts a once-per-second refresh of the usage counters while the item is expanded. */
        private fun startUsageTicker(contact: String) {
            stopUsageTicker()
            if (!usageUpdatesActive) return
            val runnable = object : Runnable {
                override fun run() {
                    updateUsageCounters(contact)
                    binding.root.postDelayed(this, USAGE_TICK_MS)
                }
            }
            usageTicker = runnable
            binding.root.postDelayed(runnable, USAGE_TICK_MS)
        }

        fun stopUsageTicker() {
            usageTicker?.let { binding.root.removeCallbacks(it) }
            usageTicker = null
        }

        fun resumeUsageTicker() {
            boundContact?.takeIf { it in expandedContacts }?.let { startUsageTicker(it) }
        }

        private fun refreshGroups(contact: String) {
            val context = binding.root.context
            val groups = BlockedContactsRepository.getContactScheduleGroups(context, contact)
            groupAdapter?.submit(groups)
        }
    }

    private class ContactDiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }
    }
}
