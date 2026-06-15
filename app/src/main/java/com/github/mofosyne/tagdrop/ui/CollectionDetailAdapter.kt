package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.isOpenable
import com.github.mofosyne.tagdrop.databinding.ItemPageBinding
import com.github.mofosyne.tagdrop.databinding.ItemSectionHeaderBinding
import com.github.mofosyne.tagdrop.openCollectionDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectionDetailAdapter(
    private val onOpen: (FoundCache) -> Unit,
    private val onDelete: (FoundCache) -> Unit,
    private val onMap: (Double, Double) -> Unit,
    private val onInspectCbor: (FoundCache) -> Unit,
    private val onShare: (FoundCache) -> Unit,
    private val onSave: (FoundCache) -> Unit,
    private val onShareQr: (FoundCache) -> Unit
) : ListAdapter<PageItem, RecyclerView.ViewHolder>(Diff) {

    inner class PageViewHolder(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Shows the "⋮" overflow menu for a cached item (Map/Share/Share via QR/Save/CBOR, plus Delete when allowed). */
        private fun showOverflowMenu(cache: FoundCache, canDelete: Boolean) {
            val popup = PopupMenu(binding.root.context, binding.buttonMore)
            popup.menuInflater.inflate(R.menu.menu_page_item, popup.menu)
            popup.menu.findItem(R.id.action_map).isVisible = cache.lat != null && cache.lng != null
            popup.menu.findItem(R.id.action_share).isVisible = cache.isOpenable
            popup.menu.findItem(R.id.action_share_qr).isVisible = cache.isOpenable
            popup.menu.findItem(R.id.action_save).isVisible = cache.isOpenable
            popup.menu.findItem(R.id.action_delete).isVisible = canDelete
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_map -> {
                        val lat = cache.lat
                        val lng = cache.lng
                        if (lat != null && lng != null) onMap(lat, lng)
                        true
                    }
                    R.id.action_share -> { onShare(cache); true }
                    R.id.action_share_qr -> { onShareQr(cache); true }
                    R.id.action_save -> { onSave(cache); true }
                    R.id.action_cbor -> { onInspectCbor(cache); true }
                    R.id.action_delete -> { onDelete(cache); true }
                    else -> false
                }
            }
            popup.show()
        }

        fun bind(item: PageItem.Row) {
            val ctx = binding.root.context
            val icon = when (item) {
                is PageItem.PaperFile -> item.cache?.icon
                is PageItem.CacheEntry -> item.cache.icon
                is PageItem.RelatedHint -> item.scannedPaper?.icon ?: "🧭" // 🧭
            }
            binding.textIcon.text = icon
            binding.textIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
            when (item) {
                is PageItem.PaperFile -> {
                    binding.textTitle.text = item.slug
                    binding.textSubtitle.text = item.mimeType
                    binding.textSubtitle.visibility = View.VISIBLE
                    val cache = item.cache
                    if (cache != null) {
                        binding.textStatus.text = if (cache.encrypted) {
                            ctx.getString(R.string.status_encrypted)
                        } else {
                            ctx.getString(R.string.status_cached)
                        }
                        binding.buttonOpen.isEnabled = cache.isOpenable
                        binding.buttonOpen.setOnClickListener { onOpen(cache) }
                        binding.buttonMap.visibility = View.GONE
                        binding.buttonMore.visibility = View.VISIBLE
                        binding.buttonMore.setOnClickListener { showOverflowMenu(cache, canDelete = false) }
                    } else {
                        binding.textStatus.text = ctx.getString(R.string.status_not_cached)
                        binding.buttonOpen.isEnabled = false
                        binding.buttonOpen.setOnClickListener(null)
                        binding.buttonMap.visibility = View.GONE
                        binding.buttonMore.visibility = View.GONE
                    }
                }
                is PageItem.CacheEntry -> {
                    val cache = item.cache
                    binding.textTitle.text = cache.hint ?: cache.filename ?: ctx.getString(R.string.collection_untitled)
                    binding.textSubtitle.text = cache.mimeType
                    binding.textSubtitle.visibility = View.VISIBLE
                    binding.textStatus.text = if (cache.encrypted) {
                        ctx.getString(R.string.status_encrypted)
                    } else {
                        DATE_FMT.format(Date(cache.discoveredAt))
                    }
                    binding.buttonOpen.isEnabled = cache.isOpenable
                    binding.buttonOpen.setOnClickListener { onOpen(cache) }
                    binding.buttonMap.visibility = View.GONE
                    binding.buttonMore.visibility = View.VISIBLE
                    binding.buttonMore.setOnClickListener { showOverflowMenu(cache, canDelete = true) }
                }
                is PageItem.RelatedHint -> {
                    val related = item.related
                    binding.textTitle.text = related.hint
                    val sub = listOfNotNull(related.set, related.slug).joinToString(" / ")
                    binding.textSubtitle.text = sub
                    binding.textSubtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
                    binding.buttonMore.visibility = View.GONE

                    val paper = item.scannedPaper
                    if (paper != null) {
                        binding.textStatus.text = ctx.getString(R.string.related_found)
                        binding.buttonOpen.isEnabled = true
                        binding.buttonOpen.setOnClickListener { ctx.openCollectionDetail(rootHash = paper.rootHash) }
                        if (paper.lat != null && paper.lng != null) {
                            binding.buttonMap.visibility = View.VISIBLE
                            binding.buttonMap.setOnClickListener { onMap(paper.lat, paper.lng) }
                        } else {
                            binding.buttonMap.visibility = View.GONE
                        }
                    } else {
                        binding.textStatus.text = ctx.getString(R.string.related_not_found)
                        binding.buttonOpen.isEnabled = false
                        binding.buttonOpen.setOnClickListener(null)
                        val lat = related.lat
                        val lng = related.lng
                        if (lat != null && lng != null) {
                            binding.buttonMap.visibility = View.VISIBLE
                            binding.buttonMap.setOnClickListener { onMap(lat, lng) }
                        } else {
                            binding.buttonMap.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    inner class HeaderViewHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PageItem.SectionHeader) {
            binding.textSectionHeader.text = item.title
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is PageItem.SectionHeader -> VIEW_TYPE_HEADER
        is PageItem.Row -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> PageViewHolder(
                ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PageItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is PageItem.Row -> (holder as PageViewHolder).bind(item)
        }
    }

    private object Diff : DiffUtil.ItemCallback<PageItem>() {
        override fun areItemsTheSame(a: PageItem, b: PageItem) = a.key == b.key

        /**
         * [FoundCache.equals] compares only `cacheId` (it holds a `ByteArray`, which can't
         * be structurally `==`-compared), so `a == b` alone can't see e.g. `encrypted`
         * flipping from true to false once a SPEC §9 key unlocks it — check that explicitly
         * so the row re-binds and drops its "Locked" status.
         */
        override fun areContentsTheSame(a: PageItem, b: PageItem): Boolean {
            if (a != b) return false
            return a.cacheOrNull()?.encrypted == b.cacheOrNull()?.encrypted
        }

        private fun PageItem.cacheOrNull(): FoundCache? = when (this) {
            is PageItem.CacheEntry -> cache
            is PageItem.PaperFile -> cache
            else -> null
        }
    }

    companion object {
        private const val VIEW_TYPE_ROW = 0
        private const val VIEW_TYPE_HEADER = 1
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
