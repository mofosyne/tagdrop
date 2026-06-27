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
import com.github.mofosyne.tagdrop.data.db.hasPendingOverride
import com.github.mofosyne.tagdrop.data.db.isOpenable
import com.github.mofosyne.tagdrop.data.db.showsLockHint
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver
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
    private val onShareQr: (FoundCache) -> Unit,
    private val onWriteNfc: (FoundCache) -> Unit
) : ListAdapter<PageItem, RecyclerView.ViewHolder>(Diff) {

    inner class PageViewHolder(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Shows the "⋮" overflow menu for a cached item (Map/Share/Share via QR/Write NFC/Save/CBOR, plus Delete when allowed). */
        private fun showOverflowMenu(cache: FoundCache, canDelete: Boolean) {
            val popup = PopupMenu(binding.root.context, binding.buttonMore)
            popup.menuInflater.inflate(R.menu.menu_page_item, popup.menu)
            popup.menu.findItem(R.id.action_map).isVisible = cache.lat != null && cache.lng != null
            popup.menu.findItem(R.id.action_share).isVisible = cache.isOpenable
            popup.menu.findItem(R.id.action_share_qr).isVisible = cache.isOpenable
            popup.menu.findItem(R.id.action_write_nfc).isVisible = cache.isOpenable
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
                    R.id.action_write_nfc -> { onWriteNfc(cache); true }
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
            val cacheForBadge = when (item) {
                is PageItem.PaperFile -> item.cache
                is PageItem.CacheEntry -> item.cache
                is PageItem.RelatedHint -> null
            }
            binding.textLockBadge.visibility = if (cacheForBadge?.showsLockHint == true) View.VISIBLE else View.GONE
            val wasEncrypted = when (item) {
                is PageItem.CacheEntry -> item.cache.wasEncrypted
                is PageItem.PaperFile -> item.cache?.wasEncrypted == true
                else -> false
            }
            binding.textUnlockBadge.visibility =
                if (wasEncrypted && cacheForBadge?.hasPendingOverride != true) View.VISIBLE else View.GONE
            // Same homepage convention as a paper's file directory (TagDropLinkResolver.HOME_SLUGS),
            // applied to an ad-hoc collection's filename — collection_id has no slug/manifest of its
            // own, so filename is the nearest equivalent "this is the entry point" signal.
            val homeName = when (item) {
                is PageItem.PaperFile -> item.slug
                is PageItem.CacheEntry -> item.cache.filename
                is PageItem.RelatedHint -> null
            }
            binding.textHomeBadge.visibility =
                if (homeName != null && homeName in TagDropLinkResolver.HOME_SLUGS) View.VISIBLE else View.GONE
            when (item) {
                is PageItem.PaperFile -> {
                    binding.textTitle.text = item.slug
                    binding.textSubtitle.text = subtitleWithLocationLabel(item.mimeType, item.cache?.locationLabel)
                    binding.textSubtitle.visibility = View.VISIBLE
                    val cache = item.cache
                    if (cache != null) {
                        binding.textStatus.text = ctx.getString(R.string.status_cached)
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
                    binding.textSubtitle.text = subtitleWithLocationLabel(cache.mimeType, cache.locationLabel)
                    binding.textSubtitle.visibility = View.VISIBLE
                    binding.textStatus.text = dateFormat().format(Date(cache.discoveredAt))
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
         * be structurally `==`-compared), so `a == b` alone can't see e.g. `pendingOverrideBlob`
         * clearing once a SPEC §9 key unlocks it — check that explicitly so the row re-binds
         * and drops its lock badge and gains its unlock badge. Also check `filename`, since an
         * override can self-correct it to "index"/etc. post-unlock, which should (re)show the
         * 🏠 home badge on an ad-hoc collection entry.
         */
        override fun areContentsTheSame(a: PageItem, b: PageItem): Boolean {
            if (a != b) return false
            val ca = a.cacheOrNull()
            val cb = b.cacheOrNull()
            return ca?.hasPendingOverride == cb?.hasPendingOverride &&
                ca?.pendingOverrideDeclared == cb?.pendingOverrideDeclared &&
                ca?.wasEncrypted == cb?.wasEncrypted &&
                ca?.filename == cb?.filename
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
        private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /** Appends a non-coordinate location description (SPEC §4.2) to a subtitle, since it has no map pin to show instead. */
        private fun subtitleWithLocationLabel(base: String, locationLabel: String?) =
            if (locationLabel != null) "$base · 📍 $locationLabel" else base
    }
}
