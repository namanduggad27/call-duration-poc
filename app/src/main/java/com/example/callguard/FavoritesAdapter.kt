package com.example.callguard

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FavoritesAdapter(
    private var favorites: List<ContactHelper.ContactItem> = emptyList(),
    private val onContactClick: (String) -> Unit,
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    fun submitList(newFavorites: List<ContactHelper.ContactItem>) {
        favorites = newFavorites
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(favorites[position])
    }

    override fun getItemCount(): Int = favorites.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFavAvatar: ImageView = itemView.findViewById(R.id.ivFavAvatar)
        private val tvFavInitials: TextView = itemView.findViewById(R.id.tvFavInitials)
        private val tvFavName: TextView = itemView.findViewById(R.id.tvFavName)

        fun bind(item: ContactHelper.ContactItem) {
            tvFavName.text = item.name

            if (!item.photoUri.isNullOrBlank()) {
                ivFavAvatar.visibility = View.VISIBLE
                tvFavInitials.visibility = View.GONE
                try {
                    ivFavAvatar.setImageURI(Uri.parse(item.photoUri))
                } catch (_: Exception) {
                    showInitials(item.name)
                }
            } else {
                showInitials(item.name)
            }

            itemView.setOnClickListener { onContactClick(item.phoneNumber) }
        }

        private fun showInitials(name: String) {
            ivFavAvatar.visibility = View.GONE
            tvFavInitials.visibility = View.VISIBLE
            val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            tvFavInitials.text = initial
        }
    }
}
