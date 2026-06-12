package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.databinding.ItemCollectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectionListAdapter(
    private val onClick: (CollectionItem) -> Unit
) : ListAdapter<CollectionItem, CollectionListAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CollectionItem) {
            val ctx = binding.root.context
            val icon = when (item) {
                is CollectionItem.Paper -> item.paper.icon
                is CollectionItem.AdHoc -> item.icon
                is CollectionItem.Loose -> item.cache.icon
            }
            binding.textIcon.text = icon
            binding.textIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
            when (item) {
                is CollectionItem.Paper -> {
                    binding.textType.text = ctx.getString(R.string.collection_type_paper)
                    binding.textTitle.text = item.paper.label ?: ctx.getString(R.string.paper_manifest_label)
                    binding.textSubtitle.text = ctx.getString(
                        R.string.collection_cached_progress, item.cachedFiles, item.totalFiles
                    )
                    binding.textMeta.text = buildString {
                        if (item.paper.set != null) append(ctx.getString(R.string.paper_set, item.paper.set))
                        if (item.paper.slug != null) append(" /${item.paper.slug}")
                        if (isNotEmpty()) append("  ·  ")
                        append(item.paper.rootHash.take(12))
                    }
                }
                is CollectionItem.AdHoc -> {
                    binding.textType.text = ctx.getString(R.string.collection_type_adhoc)
                    binding.textTitle.text = item.label
                        ?: ctx.getString(R.string.collection_adhoc_default_title, item.collectionId.take(8))
                    binding.textSubtitle.text = ctx.getString(R.string.collection_item_count, item.items.size)
                    binding.textMeta.text = buildString {
                        if (item.tags.isNotEmpty()) append(item.tags.joinToString(" ") { "#$it" } + "  ·  ")
                        append(DATE_FMT.format(Date(item.timestamp)))
                    }
                }
                is CollectionItem.Loose -> {
                    binding.textType.text = ctx.getString(R.string.collection_type_loose)
                    binding.textTitle.text = item.cache.hint
                        ?: item.cache.filename
                        ?: ctx.getString(R.string.collection_untitled)
                    binding.textSubtitle.text = item.cache.mimeType
                    binding.textMeta.text = DATE_FMT.format(Date(item.cache.discoveredAt))
                }
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<CollectionItem>() {
        override fun areItemsTheSame(a: CollectionItem, b: CollectionItem) = a.key == b.key
        override fun areContentsTheSame(a: CollectionItem, b: CollectionItem) = a == b
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
