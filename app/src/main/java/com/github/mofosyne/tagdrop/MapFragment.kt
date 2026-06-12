package com.github.mofosyne.tagdrop

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.databinding.FragmentMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.io.File

/** "Map" tab: pins for every scan that has a captured location. */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private var hasCentered = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val osmConf = Configuration.getInstance()
        osmConf.load(requireContext(), requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        osmConf.userAgentValue = requireContext().packageName
        osmConf.osmdroidBasePath = File(requireContext().cacheDir, "osmdroid")
        osmConf.osmdroidTileCache = File(osmConf.osmdroidBasePath, "tiles")

        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(2.0)
        binding.map.controller.setCenter(GeoPoint(20.0, 0.0))

        var latestPapers: List<ScannedPaper> = emptyList()
        var latestCaches: List<FoundCache> = emptyList()

        fun render() {
            binding.map.overlays.clear()
            val points = mutableListOf<GeoPoint>()

            for (cache in latestCaches) {
                val lat = cache.lat
                val lng = cache.lng
                if (lat == null || lng == null) continue
                val point = GeoPoint(lat, lng)
                points += point
                binding.map.overlays.add(Marker(binding.map).apply {
                    position = point
                    title = cache.hint ?: cache.filename ?: getString(R.string.collection_untitled)
                    setOnMarkerClickListener { _, _ ->
                        val collectionId = cache.collectionId
                        if (collectionId != null) requireContext().openCollectionDetail(collectionId = collectionId)
                        else requireContext().openCollectionDetail(cacheId = cache.cacheId)
                        true
                    }
                })
            }

            for (paper in latestPapers) {
                val lat = paper.lat
                val lng = paper.lng
                if (lat == null || lng == null) continue
                val point = GeoPoint(lat, lng)
                points += point
                binding.map.overlays.add(Marker(binding.map).apply {
                    position = point
                    title = paper.label ?: getString(R.string.paper_manifest_label)
                    setOnMarkerClickListener { _, _ ->
                        requireContext().openCollectionDetail(rootHash = paper.rootHash)
                        true
                    }
                })
            }

            binding.textEmpty.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
            if (!hasCentered && points.isNotEmpty()) {
                hasCentered = true
                val north = points.maxOf { it.latitude }
                val south = points.minOf { it.latitude }
                val east = points.maxOf { it.longitude }
                val west = points.minOf { it.longitude }
                binding.map.post {
                    binding.map.zoomToBoundingBox(BoundingBox(north, east, south, west), true)
                }
            }
            binding.map.invalidate()
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

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
