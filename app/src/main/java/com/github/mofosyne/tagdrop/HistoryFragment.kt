package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.fragment.app.activityViewModels
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.databinding.FragmentHistoryBinding
import com.github.mofosyne.tagdrop.ui.HistoryAdapter
import com.github.mofosyne.tagdrop.ui.HistoryItem

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

        fun render() {
            val items = HistoryItem.build(latestPapers, latestCaches)
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
