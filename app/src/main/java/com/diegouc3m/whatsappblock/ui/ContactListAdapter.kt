package com.diegouc3m.whatsappblock.ui

import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.BlockedContactsRepository
import com.diegouc3m.whatsappblock.TimeSlot
import com.diegouc3m.whatsappblock.databinding.ItemContactBinding

class ContactListAdapter(
    private val onDelete: (String) -> Unit
) : ListAdapter<String, ContactListAdapter.ViewHolder>(StringDiffCallback()) {

    private val expandedContacts = mutableSetOf<String>()

    fun submitSortedList(list: List<String>) {
        submitList(list.sorted())
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

        private var slotAdapter: ScheduleSlotAdapter? = null

        fun bind(name: String) {
            val context = binding.root.context
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

            // Time slots RecyclerView - only set up adapter if not already configured
            if (binding.rvContactSlots.adapter == null) {
                binding.rvContactSlots.layoutManager = LinearLayoutManager(context)
            }
            val adapter = ScheduleSlotAdapter(
                onEditStart = { slotPosition, slot ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val slots = BlockedContactsRepository.getContactScheduleSlots(context, name).toMutableList()
                        if (slotPosition in slots.indices) {
                            slots[slotPosition] = slot.copy(startHour = hour, startMinute = minute)
                            BlockedContactsRepository.setContactScheduleSlots(context, name, slots)
                            refreshSlots(name)
                        }
                    }, slot.startHour, slot.startMinute, true).show()
                },
                onEditEnd = { slotPosition, slot ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val slots = BlockedContactsRepository.getContactScheduleSlots(context, name).toMutableList()
                        if (slotPosition in slots.indices) {
                            slots[slotPosition] = slot.copy(endHour = hour, endMinute = minute)
                            BlockedContactsRepository.setContactScheduleSlots(context, name, slots)
                            refreshSlots(name)
                        }
                    }, slot.endHour, slot.endMinute, true).show()
                },
                onDelete = { slot ->
                    BlockedContactsRepository.removeContactScheduleSlot(context, name, slot)
                    refreshSlots(name)
                }
            )
            slotAdapter = adapter
            binding.rvContactSlots.adapter = adapter
            refreshSlots(name)

            binding.btnAddContactSlot.setOnClickListener {
                BlockedContactsRepository.addContactScheduleSlot(context, name, TimeSlot(0, 0, 23, 59))
                refreshSlots(name)
            }
        }

        private fun refreshSlots(contact: String) {
            val context = binding.root.context
            val slots = BlockedContactsRepository.getContactScheduleSlots(context, contact)
            slotAdapter?.submitList(slots.toList())
        }
    }

    private class StringDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
