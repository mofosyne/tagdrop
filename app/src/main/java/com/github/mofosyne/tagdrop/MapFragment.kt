package com.github.mofosyne.tagdrop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
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
    private var deviceLocation: GeoPoint? = null
    private var latestPapers: List<ScannedPaper> = emptyList()
    private var latestCaches: List<FoundCache> = emptyList()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                deviceLocation = lastKnownLocation()
                render()
            }
        }

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
        binding.map.controller.setZoom(DEFAULT_ZOOM)
        binding.map.controller.setCenter(GeoPoint(20.0, 0.0))

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            deviceLocation = lastKnownLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
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

    /** Draws all known pins and, on first load, focuses the view on the user's area. */
    private fun render() {
        if (_binding == null) return
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

        if (!hasCentered) {
            // Frame the user's own area plus any tags within it; ignore far-away tags
            // (e.g. someone else's drop) so the initial view doesn't zoom out to fit them.
            val nearbyPoints = deviceLocation?.let { here ->
                points.filter { it.distanceToAsDouble(here) <= NEARBY_RADIUS_METERS }
            } ?: points
            val focusPoints = nearbyPoints + listOfNotNull(deviceLocation)
            if (focusPoints.size == 1) {
                hasCentered = true
                binding.map.controller.setZoom(DEFAULT_ZOOM)
                binding.map.controller.setCenter(focusPoints.single())
            } else if (focusPoints.size > 1) {
                hasCentered = true
                val north = focusPoints.maxOf { it.latitude }
                val south = focusPoints.minOf { it.latitude }
                val east = focusPoints.maxOf { it.longitude }
                val west = focusPoints.minOf { it.longitude }
                binding.map.post {
                    binding.map.zoomToBoundingBox(BoundingBox(north, east, south, west), true)
                }
            }
        }
        binding.map.invalidate()
    }

    /** Best-known device location, or null if unavailable/permission not granted. */
    private fun lastKnownLocation(): GeoPoint? {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val locationManager = requireContext().getSystemService<LocationManager>() ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
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

    companion object {
        // Roughly a regional/state-sized view (e.g. Melbourne/Victoria, Australia).
        private const val DEFAULT_ZOOM = 8.0
        private const val NEARBY_RADIUS_METERS = 50_000.0
    }
}
