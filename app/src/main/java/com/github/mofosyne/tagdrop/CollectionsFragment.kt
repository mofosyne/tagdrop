package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CollectionListAdapter { item -> openCollection(item) }
        binding.recyclerCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCollections.adapter = adapter

        var latestPapers: List<ScannedPaper> = emptyList()
        var latestCaches: List<FoundCache> = emptyList()

        fun render() {
            val items = CollectionItem.build(latestPapers, latestCaches)
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
