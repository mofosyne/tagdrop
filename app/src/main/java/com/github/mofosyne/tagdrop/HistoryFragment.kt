package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.fragment.app.activityViewModels
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.databinding.FragmentHistoryBinding
import com.github.mofosyne.tagdrop.ui.HistoryAdapter
import com.github.mofosyne.tagdrop.ui.HistoryItem
import com.google.android.material.chip.Chip

/** "History" tab: every scan event (cached items and paper manifests), newest first. */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = HistoryAdapter(
            onClick = { item -> openHistoryItem(item) },
            onMap = { lat, lng ->
                viewModel.focusOnMap(lat, lng)
                (activity as? MainActivity)?.switchToMap()
            }
        )
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter

        var latestPapers: List<ScannedPaper> = emptyList()
        var latestCaches: List<FoundCache> = emptyList()
        var query = ""
        var lastTags: List<String> = emptyList()

        fun renderTagChips(tags: List<String>) {
            binding.chipGroupTags.removeAllViews()
            binding.chipGroupTags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            for (tag in tags) {
                val chip = layoutInflater.inflate(R.layout.item_tag_chip, binding.chipGroupTags, false) as Chip
                chip.text = getString(R.string.tag_chip_format, tag)
                chip.setOnClickListener {
                    val tagQuery = "#$tag"
                    val current = binding.editSearch.text?.toString().orEmpty()
                    binding.editSearch.setText(if (current.equals(tagQuery, ignoreCase = true)) "" else tagQuery)
                }
                binding.chipGroupTags.addView(chip)
            }
        }

        fun render() {
            val allItems = HistoryItem.build(latestPapers, latestCaches)
            val items = allItems.filter { it.matches(query) }
            adapter.submitList(items)
            binding.emptyStateContainer.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.textEmpty.text = if (query.isBlank()) getString(R.string.empty_history)
                else getString(R.string.search_no_results, query.trim())
            binding.buttonRestoreEmpty.visibility = if (allItems.isEmpty()) View.VISIBLE else View.GONE

            val tags = allItems.flatMap { it.tags }.distinct().sorted()
            if (tags != lastTags) {
                lastTags = tags
                renderTagChips(tags)
            }
        }

        binding.editSearch.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            render()
        }

        binding.buttonRestoreEmpty.setOnClickListener {
            (activity as? MainActivity)?.triggerRestore()
        }

        val db = AppDatabase.get(requireContext())
        db.paperDao().getAll().observe(viewLifecycleOwner) { papers ->
            latestPapers = papers
            render()
        }
        db.cacheDao().getAllCaches().observe(viewLifecycleOwner) { caches ->
            latestCaches = caches
            render()
        }
    }

    private fun openHistoryItem(item: HistoryItem) {
        when (item) {
            is HistoryItem.PaperScan -> requireContext().openCollectionDetail(rootHash = item.paper.rootHash)
            is HistoryItem.CacheScan -> {
                val collectionId = item.cache.collectionId
                if (collectionId != null) requireContext().openCollectionDetail(collectionId = collectionId)
                else requireContext().openCollectionDetail(cacheId = item.cache.cacheId)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
