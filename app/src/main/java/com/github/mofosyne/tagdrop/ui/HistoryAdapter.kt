package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.db.isThumbnailEligible
import com.github.mofosyne.tagdrop.databinding.ItemCollectionBinding
import com.github.mofosyne.tagdrop.util.DEFAULT_COLLECTION_ICON
import com.github.mofosyne.tagdrop.util.iconForMimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (HistoryItem) -> Unit,
    private val onMap: (Double, Double) -> Unit
) : ListAdapter<HistoryItem, HistoryAdapter.ViewHolder>(Diff) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class ViewHolder(private val binding: ItemCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            val ctx = binding.root.context
            val icon = when (item) {
                is HistoryItem.CacheScan -> item.cache.icon
                is HistoryItem.PaperScan -> item.paper.icon
            }
            val thumbnailCache = when (item) {
                is HistoryItem.CacheScan -> item.cache.takeIf { it.isThumbnailEligible }
                is HistoryItem.PaperScan -> item.thumbnailCache
            }
            val fallbackIcon = when (item) {
                is HistoryItem.CacheScan -> iconForMimeType(item.cache.mimeType)
                is HistoryItem.PaperScan -> DEFAULT_COLLECTION_ICON
            }
            binding.imageThumbnail.bindThumbnailOrIcon(binding.textIcon, thumbnailCache, icon, fallbackIcon, scope)
            when (item) {
                is HistoryItem.CacheScan -> {
                    val cache = item.cache
                    binding.textType.text = ctx.getString(
                        if (cache.createdByMe) R.string.collection_type_loose_created else R.string.collection_type_loose
                    )
                    binding.textTitle.text = cache.hint ?: cache.filename ?: ctx.getString(R.string.collection_untitled)
                    binding.textSubtitle.text = cache.mimeType
                    binding.textMeta.text = buildString {
                        if (cache.collectionTag != null) append("#${cache.collectionTag}  ·  ")
                        append(dateFormat().format(Date(cache.discoveredAt)))
                    }
                    if (cache.lat != null && cache.lng != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(cache.lat, cache.lng) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                }
                is HistoryItem.PaperScan -> {
                    val paper = item.paper
                    binding.textType.text = ctx.getString(
                        if (paper.createdByMe) R.string.collection_type_paper_created else R.string.collection_type_paper
                    )
                    binding.textTitle.text = paper.label ?: ctx.getString(R.string.paper_manifest_label)
                    binding.textSubtitle.text = buildString {
                        if (paper.set != null) append(ctx.getString(R.string.paper_set, paper.set))
                        if (paper.slug != null) append(" /${paper.slug}")
                    }
                    binding.textMeta.text = buildString {
                        if (paper.collectionTag != null) append("#${paper.collectionTag}  ·  ")
                        append(dateFormat().format(Date(paper.scannedAt)))
                    }
                    if (paper.lat != null && paper.lng != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(paper.lat, paper.lng) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                }
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = a.key == b.key
        override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
    }

    companion object {
        private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
