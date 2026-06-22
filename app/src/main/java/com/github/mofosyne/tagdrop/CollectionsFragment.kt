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
import com.github.mofosyne.tagdrop.databinding.FragmentCollectionsBinding
import com.github.mofosyne.tagdrop.ui.CollectionItem
import com.github.mofosyne.tagdrop.ui.CollectionListAdapter

/** "Collections" tab: papers, ad-hoc groups, and loose scans, one card per collection. */
class CollectionsFragment : Fragment() {

    private var _binding: FragmentCollectionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CollectionListAdapter(
            onClick = { item -> openCollection(item) },
            onMap = { lat, lng ->
                viewModel.focusOnMap(lat, lng)
                (activity as? MainActivity)?.switchToMap()
            }
        )
        binding.recyclerCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCollections.adapter = adapter

        var latestPapers: List<ScannedPaper> = emptyList()
        var latestCaches: List<FoundCache> = emptyList()
        var query = ""

        fun render() {
            val items = CollectionItem.build(latestPapers, latestCaches).filter { it.matches(query) }
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.textEmpty.text = if (query.isBlank()) getString(R.string.empty_collection)
                else getString(R.string.search_no_results, query.trim())
        }

        binding.editSearch.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            render()
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

    private fun openCollection(item: CollectionItem) {
        when (item) {
            is CollectionItem.Paper -> requireContext().openCollectionDetail(rootHash = item.paper.rootHash)
            is CollectionItem.AdHoc -> requireContext().openCollectionDetail(collectionId = item.collectionId)
            is CollectionItem.Loose -> requireContext().openCollectionDetail(cacheId = item.cache.cacheId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
