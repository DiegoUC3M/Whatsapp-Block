package com.diegouc3m.whatsappblock.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.ScheduleGroup
import com.diegouc3m.whatsappblock.TimeSlot
import com.diegouc3m.whatsappblock.databinding.ItemScheduleGroupBinding
import com.google.android.material.chip.Chip

/**
 * Displays the schedule groups of a contact. Each group exposes a weekday selector
 * (chips) and its own list of time slots. Weekdays already assigned to another group
 * are shown disabled so that a day can only belong to a single group.
 */
class ScheduleGroupAdapter(
    private val onToggleDay: (groupIndex: Int, day: Int, enabled: Boolean) -> Unit,
    private val onAddSlot: (groupIndex: Int) -> Unit,
    private val onEditSlotStart: (groupIndex: Int, slotIndex: Int, slot: TimeSlot) -> Unit,
    private val onEditSlotEnd: (groupIndex: Int, slotIndex: Int, slot: TimeSlot) -> Unit,
    private val onDeleteSlot: (groupIndex: Int, slotIndex: Int) -> Unit,
    private val onDeleteGroup: (groupIndex: Int) -> Unit
) : RecyclerView.Adapter<ScheduleGroupAdapter.ViewHolder>() {

    private var groups: List<ScheduleGroup> = emptyList()

    fun submit(newGroups: List<ScheduleGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    inner class ViewHolder(private val binding: ItemScheduleGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dayChips: List<Chip> = listOf(
            binding.chipDay0,
            binding.chipDay1,
            binding.chipDay2,
            binding.chipDay3,
            binding.chipDay4,
            binding.chipDay5,
            binding.chipDay6
        )

        private val slotAdapter = ScheduleSlotAdapter(
            onEditStart = { slotIndex, slot ->
                val gp = bindingAdapterPosition
                if (gp != RecyclerView.NO_POSITION) onEditSlotStart(gp, slotIndex, slot)
            },
            onEditEnd = { slotIndex, slot ->
                val gp = bindingAdapterPosition
                if (gp != RecyclerView.NO_POSITION) onEditSlotEnd(gp, slotIndex, slot)
            },
            onDelete = { slotIndex, _ ->
                val gp = bindingAdapterPosition
                if (gp != RecyclerView.NO_POSITION) onDeleteSlot(gp, slotIndex)
            }
        )

        init {
            binding.rvGroupSlots.layoutManager = LinearLayoutManager(binding.root.context)
            binding.rvGroupSlots.adapter = slotAdapter

            binding.btnAddGroupSlot.setOnClickListener {
                val gp = bindingAdapterPosition
                if (gp != RecyclerView.NO_POSITION) onAddSlot(gp)
            }
            binding.btnDeleteGroup.setOnClickListener {
                val gp = bindingAdapterPosition
                if (gp != RecyclerView.NO_POSITION) onDeleteGroup(gp)
            }
        }

        fun bind(group: ScheduleGroup) {
            // Days used by other groups must be disabled to keep each day in one group.
            val daysUsedByOthers = mutableSetOf<Int>()
            groups.forEachIndexed { index, other ->
                if (index != bindingAdapterPosition) daysUsedByOthers.addAll(other.days)
            }

            dayChips.forEachIndexed { day, chip ->
                chip.setOnCheckedChangeListener(null)
                chip.isChecked = day in group.days
                chip.isEnabled = day in group.days || day !in daysUsedByOthers
                chip.setOnCheckedChangeListener { _, isChecked ->
                    val gp = bindingAdapterPosition
                    if (gp != RecyclerView.NO_POSITION) onToggleDay(gp, day, isChecked)
                }
            }

            slotAdapter.submitList(group.slots.toList())
        }
    }
}
