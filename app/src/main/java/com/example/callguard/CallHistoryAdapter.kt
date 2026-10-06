package com.example.callguard

import android.telecom.Call
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallHistoryAdapter(
    private var records: List<CallRecord> = emptyList(),
    private val onCallClick: (String) -> Unit,
) : RecyclerView.Adapter<CallHistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

    fun submitList(newRecords: List<CallRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount(): Int = records.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivDirection: ImageView = itemView.findViewById(R.id.ivDirection)
        private val tvPhoneNumber: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val btnCallBack: ImageButton = itemView.findViewById(R.id.btnCallBack)

        fun bind(record: CallRecord) {
            tvPhoneNumber.text = record.phoneNumber
            tvTimestamp.text = dateFormat.format(Date(record.timestampMs))

            val isIncoming = record.direction == Call.Details.DIRECTION_INCOMING
            ivDirection.setImageResource(if (isIncoming) R.drawable.ic_call_incoming else R.drawable.ic_call_outgoing)

            val mins = record.durationSeconds / 60
            val secs = record.durationSeconds % 60
            val durText = if (record.autoDisconnected) {
                "%02d:%02d (Ended)".format(mins, secs)
            } else {
                "%02d:%02d".format(mins, secs)
            }
            tvDuration.text = durText

            btnCallBack.setOnClickListener { onCallClick(record.phoneNumber) }
            itemView.setOnClickListener { onCallClick(record.phoneNumber) }
        }
    }
}
