package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.databinding.ItemCacheBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CacheListAdapter(
    private val onOpen: (FoundCache) -> Unit,
    private val onDelete: (FoundCache) -> Unit
) : ListAdapter<FoundCache, CacheListAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemCacheBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(cache: FoundCache) {
            binding.textHint.text  = cache.hint ?: cache.filename ?: "(no hint)"
            binding.textCacheId.text = cache.cacheId.take(12) + "…"
            binding.textMime.text  = cache.mimeType
            binding.textDate.text  = DATE_FMT.format(Date(cache.discoveredAt))
            binding.buttonOpen.isEnabled = cache.contentBytes != null
            binding.buttonOpen.setOnClickListener  { onOpen(cache) }
            binding.buttonDelete.setOnClickListener { onDelete(cache) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCacheBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<FoundCache>() {
        override fun areItemsTheSame(a: FoundCache, b: FoundCache) = a.cacheId == b.cacheId
        override fun areContentsTheSame(a: FoundCache, b: FoundCache) = a == b
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
