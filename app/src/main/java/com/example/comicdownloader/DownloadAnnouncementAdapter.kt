package com.example.comicdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadAnnouncementAdapter(private val imageUrls: List<DownloadAnnouncement>) :
    RecyclerView.Adapter<DownloadAnnouncementAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = imageUrls[position].url
        holder.textView.setTextColor(android.graphics.Color.WHITE) // Set text color to white
    }

    override fun getItemCount() = imageUrls.size
}