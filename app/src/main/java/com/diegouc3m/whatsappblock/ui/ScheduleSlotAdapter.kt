package com.diegouc3m.whatsappblock.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.TimeSlot
import com.diegouc3m.whatsappblock.databinding.ItemScheduleSlotBinding

class ScheduleSlotAdapter(
    private val onEditStart: (Int, TimeSlot) -> Unit,
    private val onEditEnd: (Int, TimeSlot) -> Unit,
    private val onDelete: (TimeSlot) -> Unit
) : ListAdapter<TimeSlot, ScheduleSlotAdapter.ViewHolder>(SlotDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleSlotBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemScheduleSlotBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnSlotStart.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEditStart(pos, getItem(pos))
                }
            }
            binding.btnSlotEnd.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEditEnd(pos, getItem(pos))
                }
            }
            binding.btnDeleteSlot.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(getItem(pos))
                }
            }
        }

        fun bind(slot: TimeSlot) {
            binding.btnSlotStart.text = String.format("%02d:%02d", slot.startHour, slot.startMinute)
            binding.btnSlotEnd.text = String.format("%02d:%02d", slot.endHour, slot.endMinute)
        }
    }

    private class SlotDiffCallback : DiffUtil.ItemCallback<TimeSlot>() {
        override fun areItemsTheSame(oldItem: TimeSlot, newItem: TimeSlot) = oldItem == newItem
        override fun areContentsTheSame(oldItem: TimeSlot, newItem: TimeSlot) = oldItem == newItem
    }
}
