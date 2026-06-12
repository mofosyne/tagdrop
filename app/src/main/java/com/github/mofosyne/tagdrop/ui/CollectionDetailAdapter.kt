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
    private val onDelete: (FoundCache) -> Unit
) : ListAdapter<PageItem, CollectionDetailAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PageItem) {
            val ctx = binding.root.context
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
                    } else {
                        binding.textStatus.text = ctx.getString(R.string.status_not_cached)
                        binding.buttonOpen.isEnabled = false
                        binding.buttonOpen.setOnClickListener(null)
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
