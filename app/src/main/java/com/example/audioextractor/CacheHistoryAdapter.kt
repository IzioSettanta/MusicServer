package com.example.audioextractor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class CacheHistoryAdapter(private var cacheEntries: List<CacheEntry>) :
    RecyclerView.Adapter<CacheHistoryAdapter.CacheViewHolder>() {

    class CacheViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val videoName: TextView = view.findViewById(R.id.cacheVideoName)
        val expireTime: TextView = view.findViewById(R.id.cacheExpireTime)
        val videoId: TextView = view.findViewById(R.id.cacheVideoId)
        val accessCount: TextView = view.findViewById(R.id.cacheAccessCount) // 👈 nuovo campo
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CacheViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cache_entry, parent, false)
        return CacheViewHolder(view)
    }

    override fun onBindViewHolder(holder: CacheViewHolder, position: Int) {
        val entry = cacheEntries[position]
        holder.videoName.text = entry.videoName
        holder.videoId.text = "ID: ${entry.videoId}"
        holder.accessCount.text = "Accessi: ${entry.accessCount}" // 👈 nuova riga

        val remainingMs = entry.getRemainingTime()
        val remainingText = formatRemainingTime(remainingMs)
        holder.expireTime.text = remainingText

        // Cambia colore in base al tempo rimanente
        holder.expireTime.setTextColor(
            when {
                remainingMs > TimeUnit.MINUTES.toMillis(15) -> 0xFF4CAF50.toInt() // Verde (>15 min)
                remainingMs > TimeUnit.MINUTES.toMillis(5) -> 0xFFFF9800.toInt()  // Arancione (>5 min)
                remainingMs > 0 -> 0xFFF44336.toInt()              // Rosso (0-5 min)
                else -> 0xFF9E9E9E.toInt()                         // Grigio (scaduto)
            }
        )
    }

    override fun getItemCount() = cacheEntries.size

    fun updateData(newEntries: List<CacheEntry>) {
        cacheEntries = newEntries
        notifyDataSetChanged()
    }

    private fun formatRemainingTime(millis: Long): String {
        if (millis <= 0) return "Scaduto"

        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val days = TimeUnit.MILLISECONDS.toDays(millis)

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
