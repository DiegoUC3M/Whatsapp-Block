package com.diegouc3m.whatsappblock.ui

import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.R
import com.diegouc3m.whatsappblock.BlockedContactsRepository
import com.diegouc3m.whatsappblock.TimeSlot
import com.diegouc3m.whatsappblock.databinding.ItemContactBinding

class ContactListAdapter(
    private val onDelete: (String) -> Unit
) : ListAdapter<ContactListAdapter.ContactItem, ContactListAdapter.ViewHolder>(ContactDiffCallback()) {

    data class ContactItem(
        val name: String,
        val avatarHashes: List<String>
    )

    private val expandedContacts = mutableSetOf<String>()

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

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var groupAdapter: ScheduleGroupAdapter? = null

        fun bind(item: ContactItem) {
            val context = binding.root.context
            val name = item.name
            binding.tvContactName.text = name
            binding.btnDelete.setOnClickListener { onDelete(name) }
            val avatarHashes = item.avatarHashes
            binding.tvAvatarHashes.text = if (avatarHashes.isEmpty()) {
                context.getString(R.string.avatar_hashes_none)
            } else {
                context.getString(R.string.avatar_hashes_label, avatarHashes.joinToString(", "))
            }

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

            if (!isExpanded) return

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
