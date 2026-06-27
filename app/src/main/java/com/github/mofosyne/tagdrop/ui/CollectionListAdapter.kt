package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.isOpenable
import com.github.mofosyne.tagdrop.databinding.ItemCollectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectionListAdapter(
    private val onClick: (CollectionItem) -> Unit,
    private val onMap: (Double, Double) -> Unit,
    private val onOpenHome: (CollectionItem) -> Unit
) : ListAdapter<CollectionItem, CollectionListAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Shows/hides the row's "Open" button for [homeCache] — same TagDropLinkResolver.HOME_SLUGS
         *  convention as CollectionDetailActivity's header "🏠 Open homepage" button. */
        private fun bindOpenButton(homeCache: FoundCache?, item: CollectionItem) {
            if (homeCache != null && homeCache.isOpenable) {
                binding.buttonOpen.visibility = View.VISIBLE
                binding.buttonOpen.setOnClickListener { onOpenHome(item) }
            } else {
                binding.buttonOpen.visibility = View.GONE
                binding.buttonOpen.setOnClickListener(null)
            }
        }

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
                    binding.textType.text = ctx.getString(
                        if (item.paper.createdByMe) R.string.collection_type_paper_created else R.string.collection_type_paper
                    )
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
                    if (item.paper.lat != null && item.paper.lng != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(item.paper.lat, item.paper.lng) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                    bindOpenButton(item.homeCache, item)
                }
                is CollectionItem.AdHoc -> {
                    binding.textType.text = ctx.getString(R.string.collection_type_adhoc)
                    binding.textTitle.text = item.label
                        ?: ctx.getString(R.string.collection_adhoc_default_title, item.collectionId.take(8))
                    binding.textSubtitle.text = ctx.getString(R.string.collection_item_count, item.items.size)
                    binding.textMeta.text = buildString {
                        if (item.tags.isNotEmpty()) append(item.tags.joinToString(" ") { "#$it" } + "  ·  ")
                        append(dateFormat().format(Date(item.timestamp)))
                    }
                    val firstLoc = item.items.firstOrNull { it.lat != null && it.lng != null }
                    if (firstLoc != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(firstLoc.lat!!, firstLoc.lng!!) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                    bindOpenButton(item.homeCache, item)
                }
                is CollectionItem.Loose -> {
                    binding.textType.text = ctx.getString(
                        if (item.cache.createdByMe) R.string.collection_type_loose_created else R.string.collection_type_loose
                    )
                    binding.textTitle.text = item.cache.hint
                        ?: item.cache.filename
                        ?: ctx.getString(R.string.collection_untitled)
                    binding.textSubtitle.text = item.cache.mimeType
                    binding.textMeta.text = dateFormat().format(Date(item.cache.discoveredAt))
                    if (item.cache.lat != null && item.cache.lng != null) {
                        binding.buttonMap.visibility = View.VISIBLE
                        binding.buttonMap.setOnClickListener { onMap(item.cache.lat, item.cache.lng) }
                    } else {
                        binding.buttonMap.visibility = View.GONE
                    }
                    binding.buttonOpen.visibility = View.GONE
                    binding.buttonOpen.setOnClickListener(null)
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
        private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
