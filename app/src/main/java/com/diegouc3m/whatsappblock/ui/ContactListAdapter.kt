package com.diegouc3m.whatsappblock.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.diegouc3m.whatsappblock.BlockedContactsRepository.BlockedContact
import com.diegouc3m.whatsappblock.R
import com.diegouc3m.whatsappblock.databinding.ItemContactBinding

class ContactListAdapter(
    private val onDelete: (String) -> Unit,
    private val onEnrollAvatar: (String) -> Unit,
    private val onDeleteAvatar: (String) -> Unit
) : RecyclerView.Adapter<ContactListAdapter.ViewHolder>() {

    private val items = mutableListOf<BlockedContact>()

    fun submitList(list: List<BlockedContact>) {
        items.clear()
        items.addAll(list.sortedBy { it.name })
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

        fun bind(contact: BlockedContact) {
            val context = binding.root.context
            binding.tvContactName.text = contact.name

            if (contact.avatarHashes.isEmpty()) {
                binding.tvAvatarHash.text = context.getString(R.string.avatar_hash_none)
                binding.btnDeleteAvatar.visibility = View.GONE
            } else {
                binding.tvAvatarHash.text = context.getString(
                    R.string.avatar_hash_label,
                    contact.avatarHashes.joinToString(", ")
                )
                binding.btnDeleteAvatar.visibility = View.VISIBLE
            }

            binding.btnEnrollAvatar.setOnClickListener { onEnrollAvatar(contact.name) }
            binding.btnDeleteAvatar.setOnClickListener { onDeleteAvatar(contact.name) }
            binding.btnDelete.setOnClickListener { onDelete(contact.name) }
        }
    }
}
