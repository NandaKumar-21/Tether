package com.tether.app

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** User bubbles right and green-tinted, AI bubbles left and neutral. Plain, no avatars. */
class ChatAdapter(private val items: List<ChatDb.Message>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val bubble: TextView = view.findViewById(R.id.bubbleText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val isUser = m.role == "user"
        holder.root.gravity = if (isUser) Gravity.END else Gravity.START
        holder.bubble.text = if (isUser) m.content else MarkdownLite.render(m.content)
        holder.bubble.backgroundTintList = ColorStateList.valueOf(
            if (isUser) 0xFF1B3A28.toInt() else 0xFF12161A.toInt()
        )
    }
}
