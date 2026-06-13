package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.databinding.ItemPageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectionDetailAdapter(
    private val onOpen: (FoundCache) -> Unit,
    private val onDelete: (FoundCache) -> Unit,
    private val onMap: (Double, Double) -> Unit
) : ListAdapter<PageItem, CollectionDetailAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PageItem) {
            val ctx = binding.root.context
            val icon = when (item) {
                is PageItem.PaperFile -> item.cache?.icon
                is PageItem.CacheEntry -> item.cache.icon
            }
            binding.textIcon.text = icon
            binding.textIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
            when (item) {
                is PageItem.PaperFile -> {
                    binding.textTitle.text = item.slug
                    binding.textSubtitle.text = item.mimeType
                    binding.buttonDelete.visibility = View.GONE
                    val cache = item.cache
                    if (cache != null) {
                        binding.textStatus.text = ctx.getString(R.string.status_cached)
                        binding.buttonOpen.isEnabled = cache.contentBytes != null
                        binding.buttonOpen.setOnClickListener { onOpen(cache) }
                        if (cache.lat != null && cache.lng != null) {
                            binding.buttonMap.visibility = View.VISIBLE
                            binding.buttonMap.setOnClickListener { onMap(cache.lat, cache.lng) }
                        } else {
                            binding.buttonMap.visibility = View.GONE
                        }
                    } else {
                        binding.textStatus.text = ctx.getString(R.string.status_not_cached)
                        binding.buttonOpen.isEnabled = false
                        binding.buttonOpen.setOnClickListener(null)
                        binding.buttonMap.visibility = View.GONE
                    }
                }
                is PageItem.CacheEntry -> {
                    val cache = item.cache
                    binding.textTitle.text = cache.hint ?: cache.filename ?: ctx.getString(R.string.collection_untitled)
                    binding.textSubtitle.text = cache.mimeType
                    binding.textStatus.text = DATE_FMT.format(Date(cache.discoveredAt))
                    binding.buttonOpen.isEnabled = cache.contentBytes != null
                    binding.buttonOpen.setOnClickListener { onOpen(cache) }
                    binding.buttonDelete.visibility = View.VISIBLE
                    binding.buttonDelete.setOnClickListener { onDelete(cache) }
                    if (cache.lat != null && cache.lng != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(cache.lat, cache.lng) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<PageItem>() {
        override fun areItemsTheSame(a: PageItem, b: PageItem) = a.key == b.key
        override fun areContentsTheSame(a: PageItem, b: PageItem) = a == b
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
