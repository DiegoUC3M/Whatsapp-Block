package com.diegouc3m.whatsappblock.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.databinding.ItemContactBinding

class ContactListAdapter(
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ContactListAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(list: List<String>) {
        items.clear()
        items.addAll(list.sorted())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String) {
            binding.tvContactName.text = name
            binding.btnDelete.setOnClickListener { onDelete(name) }
        }
    }
}
