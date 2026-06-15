package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.hasPendingOverride
import com.github.mofosyne.tagdrop.data.db.isOpenable
import com.github.mofosyne.tagdrop.databinding.ItemScanBlockBinding

/** One file in a scanned paper's directory, shown as a fill-in block on the scan screen. */
data class ScanBlock(val slug: String, val mimeType: String, val cache: FoundCache?)

/**
 * Grid of "blocks" — one per file in a scanned paper's directory — that fill in as each
 * file's QR code is scanned. Lets the user see at a glance which files are still missing,
 * and tap a filled block to open its content without leaving the scan screen.
 */
class ScanBoardAdapter(private val onOpen: (FoundCache) -> Unit) :
    ListAdapter<ScanBlock, ScanBoardAdapter.BlockViewHolder>(Diff) {

    inner class BlockViewHolder(private val binding: ItemScanBlockBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScanBlock) {
            val cache = item.cache
            binding.textSlug.text = item.slug
            binding.textIcon.text = cache?.icon ?: iconForMimeType(item.mimeType)
            binding.textCheck.visibility = if (cache != null) View.VISIBLE else View.GONE
            binding.textCheck.text = if (cache?.hasPendingOverride == true) "🔒" else "✓"
            binding.root.setCardBackgroundColor(
                binding.root.context.getColor(if (cache != null) R.color.scan_block_found else R.color.scan_block_pending)
            )
            binding.root.setOnClickListener {
                cache?.takeIf { it.isOpenable }?.let(onOpen)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        BlockViewHolder(ItemScanBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: BlockViewHolder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<ScanBlock>() {
        override fun areItemsTheSame(a: ScanBlock, b: ScanBlock) = a.slug == b.slug

        // FoundCache.equals() compares only cacheId, so `a == b` alone can't see
        // `pendingOverrideBlob` clearing once a SPEC §9 key unlocks it — check it explicitly.
        override fun areContentsTheSame(a: ScanBlock, b: ScanBlock) =
            a == b && a.cache?.hasPendingOverride == b.cache?.hasPendingOverride
    }

    companion object {
        private fun iconForMimeType(mime: String): String = when {
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("audio/") -> "🎵"
            mime.startsWith("video/") -> "🎬"
            mime == "application/pdf" -> "📕"
            mime.startsWith("text/") -> "📄"
            else -> "📦"
        }
    }
}
