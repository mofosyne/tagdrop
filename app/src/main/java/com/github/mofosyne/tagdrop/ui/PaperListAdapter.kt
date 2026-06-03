package com.github.mofosyne.tagdrop.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ItemPaperBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaperListAdapter(
    private val onDelete: (ScannedPaper) -> Unit
) : ListAdapter<ScannedPaper, PaperListAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemPaperBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(paper: ScannedPaper) {
            binding.textLabel.text   = paper.label ?: "(no label)"
            binding.textRootHash.text = paper.rootHash.take(12) + "…"
            binding.textSet.text     = paper.set?.let { "[$it]" } ?: ""
            binding.textDate.text    = DATE_FMT.format(Date(paper.scannedAt))

            // Decode file list from stored CBOR for display
            val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes)
            binding.textFiles.text = manifest?.files?.joinToString("\n") { "• ${it.slug}  [${it.mimeType}]" }
                ?: "(${paper.rootHash.take(8)})"

            binding.buttonDelete.setOnClickListener { onDelete(paper) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPaperBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<ScannedPaper>() {
        override fun areItemsTheSame(a: ScannedPaper, b: ScannedPaper) = a.rootHash == b.rootHash
        override fun areContentsTheSame(a: ScannedPaper, b: ScannedPaper) = a == b
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
