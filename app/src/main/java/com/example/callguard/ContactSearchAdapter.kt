package com.example.callguard

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactSearchAdapter(
    private var contacts: List<ContactHelper.ContactItem> = emptyList(),
    private val onContactClick: (String) -> Unit,
) : RecyclerView.Adapter<ContactSearchAdapter.ViewHolder>() {

    fun submitList(newContacts: List<ContactHelper.ContactItem>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount(): Int = contacts.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivSearchAvatar: ImageView = itemView.findViewById(R.id.ivSearchAvatar)
        private val tvSearchInitials: TextView = itemView.findViewById(R.id.tvSearchInitials)
        private val tvSearchName: TextView = itemView.findViewById(R.id.tvSearchName)
        private val tvSearchNumber: TextView = itemView.findViewById(R.id.tvSearchNumber)
        private val btnSearchCall: ImageButton = itemView.findViewById(R.id.btnSearchCall)

        fun bind(item: ContactHelper.ContactItem) {
            tvSearchName.text = item.name
            tvSearchNumber.text = item.phoneNumber

            if (!item.photoUri.isNullOrBlank()) {
                ivSearchAvatar.visibility = View.VISIBLE
                tvSearchInitials.visibility = View.GONE
                try {
                    ivSearchAvatar.setImageURI(Uri.parse(item.photoUri))
                } catch (_: Exception) {
                    showInitials(item.name)
                }
            } else {
                showInitials(item.name)
            }

            btnSearchCall.setOnClickListener { onContactClick(item.phoneNumber) }
            itemView.setOnClickListener { onContactClick(item.phoneNumber) }
        }

        private fun showInitials(name: String) {
            ivSearchAvatar.visibility = View.GONE
            tvSearchInitials.visibility = View.VISIBLE
            val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            tvSearchInitials.text = initial
        }
    }
}
