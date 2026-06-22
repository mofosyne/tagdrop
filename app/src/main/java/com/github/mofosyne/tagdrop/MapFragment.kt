package com.github.mofosyne.tagdrop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.matchesScannedPaper
import com.github.mofosyne.tagdrop.databinding.FragmentMapBinding
import com.github.mofosyne.tagdrop.util.LocationUtils
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** "Map" tab: pins for every scan that has a captured location. */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var hasCentered = false
    private var deviceLocation: GeoPoint? = null
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private val markerFolder = org.osmdroid.views.overlay.FolderOverlay()
    private var latestPapers: List<ScannedPaper> = emptyList()
    private var latestCaches: List<FoundCache> = emptyList()
    private val markerInfos = mutableListOf<MarkerInfo>()
    private var labelsShown = false

    /** True once a location permission request has been made this fragment instance — used to tell
     *  "never asked yet" apart from "permanently denied" when offering the recovery prompt. */
    private var locationRequested = false

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            locationRequested = true
            if (results.any { it.value }) {
                setupMyLocation()
                deviceLocation = LocationUtils.lastKnownLocation(requireContext())
                render()
            }
            updateLocationPrompt()
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
        binding.map.overlays.add(markerFolder)
        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean = false
            override fun onZoom(event: ZoomEvent): Boolean {
                val shouldShow = event.zoomLevel >= LABEL_ZOOM_THRESHOLD
                if (shouldShow != labelsShown) {
                    labelsShown = shouldShow
                    markerInfos.forEach { applyMarkerIcon(it, labelsShown) }
                    binding.map.invalidate()
                }
                return false
            }
        })

        val hasCoarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasCoarse || hasFine) {
            setupMyLocation()
            deviceLocation = LocationUtils.lastKnownLocation(requireContext())
        } else {
            locationRequested = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
        updateLocationPrompt()

        val db = AppDatabase.get(requireContext())
        db.paperDao().getAll().observe(viewLifecycleOwner) { papers ->
            latestPapers = papers
            render()
        }
        db.cacheDao().getAllCaches().observe(viewLifecycleOwner) { caches ->
            latestCaches = caches
            render()
        }

        viewModel.mapFocusPoint.observe(viewLifecycleOwner) { point ->
            if (point != null) {
                binding.map.controller.setZoom(18.0)
                binding.map.controller.animateTo(point)
                viewModel.clearFocus()
            }
        }
    }

    /** Draws all known pins and, on first load, focuses the view on the user's area. */
    private fun render() {
        if (_binding == null) return
        markerFolder.items.clear()
        markerInfos.clear()
        val points = mutableListOf<GeoPoint>()

        for (cache in latestCaches) {
            val lat = cache.lat
            val lng = cache.lng
            if (lat == null || lng == null) continue
            val point = GeoPoint(lat, lng)
            points += point
            addUncertaintyCircle(point, cache.locationRadiusM)
            val label = cache.hint ?: cache.filename ?: getString(R.string.collection_untitled)
            val marker = Marker(binding.map).apply {
                position = point
                title = label
                setOnMarkerClickListener { _, _ ->
                    val collectionId = cache.collectionId
                    if (collectionId != null) requireContext().openCollectionDetail(collectionId = collectionId)
                    else requireContext().openCollectionDetail(cacheId = cache.cacheId)
                    true
                }
            }
            markerFolder.add(marker)
            markerInfos += MarkerInfo(marker, cache.icon, label)
        }

        for (paper in latestPapers) {
            val lat = paper.lat
            val lng = paper.lng
            if (lat == null || lng == null) continue
            val point = GeoPoint(lat, lng)
            points += point
            addUncertaintyCircle(point, paper.locationRadiusM)
            val label = paper.label ?: getString(R.string.paper_manifest_label)
            val marker = Marker(binding.map).apply {
                position = point
                title = label
                setOnMarkerClickListener { _, _ ->
                    requireContext().openCollectionDetail(rootHash = paper.rootHash)
                    true
                }
            }
            markerFolder.add(marker)
            markerInfos += MarkerInfo(marker, paper.icon, label)
        }

        // Placeholder pins for related papers with a known location that haven't been scanned yet.
        val seenRelatedKeys = mutableSetOf<String>()
        for (paper in latestPapers) {
            val related = TagDropCodec.decodePaperStream(paper.cborBytes)?.related.orEmpty()
            for (r in related) {
                val lat = r.lat
                val lng = r.lng
                if (lat == null || lng == null) continue
                if (latestPapers.any { r.matchesScannedPaper(it) }) continue
                if (!seenRelatedKeys.add(r.paperId?.toHex() ?: "$lat,$lng,${r.hint}")) continue
                val point = GeoPoint(lat, lng)
                points += point
                addUncertaintyCircle(point, r.radiusM)
                val marker = Marker(binding.map).apply {
                    position = point
                    title = r.hint
                    setOnMarkerClickListener { clickedMarker, _ ->
                        if (clickedMarker.isInfoWindowShown) clickedMarker.closeInfoWindow() else clickedMarker.showInfoWindow()
                        true
                    }
                }
                markerFolder.add(marker)
                markerInfos += MarkerInfo(marker, "❓", r.hint)
            }
        }

        labelsShown = binding.map.zoomLevelDouble >= LABEL_ZOOM_THRESHOLD
        markerInfos.forEach { applyMarkerIcon(it, labelsShown) }

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
                // Pad the fit: the extreme points define the bounding box edges, so
                // without padding their pins would be drawn right at — or clipped past —
                // the viewport edge (e.g. under the bottom nav bar).
                val paddingPx = (MAP_FIT_PADDING_DP * resources.displayMetrics.density).toInt()
                binding.map.post {
                    binding.map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, paddingPx)
                }
            }
        }
        binding.map.invalidate()
    }

    /** Draws a translucent circle around [point] showing its declared circle-of-uncertainty radius, if any. */
    private fun addUncertaintyCircle(point: GeoPoint, radiusM: Double?) {
        if (radiusM == null || radiusM <= 0.0) return
        val circle = Polygon(binding.map).apply {
            setPoints(Polygon.pointsAsCircle(point, radiusM))
            fillColor = Color.argb(40, 33, 150, 243)
            strokeColor = Color.argb(160, 33, 150, 243)
            strokeWidth = 2f
        }
        markerFolder.add(circle)
    }

    /**
     * Shows a recovery button at the top of the map when location access isn't granted,
     * so the map isn't stuck centered on the ocean with no way to fix it. Re-requests the
     * permission if it can still be asked for, otherwise sends the user to app Settings
     * (where permanently-denied permissions must be re-enabled).
     */
    private fun updateLocationPrompt() {
        if (_binding == null) return
        val hasCoarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasCoarse || hasFine) {
            binding.buttonEnableLocation.visibility = View.GONE
            return
        }
        val canRequest = !locationRequested ||
            ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.ACCESS_COARSE_LOCATION)
        binding.buttonEnableLocation.text = getString(if (canRequest) R.string.button_enable_location else R.string.button_open_settings)
        binding.buttonEnableLocation.setOnClickListener {
            if (canRequest) {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                openAppSettings()
            }
        }
        binding.buttonEnableLocation.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
        )
    }

    private fun setupMyLocation() {
        if (_binding == null) return
        if (!::myLocationOverlay.isInitialized) {
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map)
            myLocationOverlay.enableMyLocation()
            binding.map.overlays.add(myLocationOverlay)
            myLocationOverlay.runOnFirstFix {
                activity?.runOnUiThread {
                    if (!hasCentered && _binding != null) {
                        deviceLocation = myLocationOverlay.myLocation ?: LocationUtils.lastKnownLocation(requireContext())
                        render()
                    }
                }
            }
        }
    }

    /**
     * Sets a marker's visual icon based on its emoji (if any) and whether the zoom-dependent
     * text label should currently be shown. Falls back to osmdroid's default pin when there's
     * nothing custom to draw.
     */
    private fun applyMarkerIcon(info: MarkerInfo, showLabel: Boolean) {
        val label = if (showLabel && info.label.isNotBlank()) info.label else null
        if (info.icon.isNullOrBlank() && label == null) {
            info.marker.setDefaultIcon()
            return
        }
        val (drawable, anchorV) = buildMarkerDrawable(info.icon, label)
        info.marker.setIcon(drawable)
        info.marker.setAnchor(0.5f, anchorV)
    }

    /**
     * Draws an emoji icon and/or a text label (on a white pill background) onto a single
     * bitmap. When both are present the emoji is stacked above the label, and the returned
     * anchor keeps the emoji — not the label — centered on the marker's geo point.
     */
    private fun buildMarkerDrawable(icon: String?, label: String?): Pair<BitmapDrawable, Float> {
        val density = resources.displayMetrics.density
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 32f * density
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * density
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
        }
        val padding = 4f * density

        val hasIcon = !icon.isNullOrBlank()
        val iconHeight = if (hasIcon) iconPaint.descent() - iconPaint.ascent() else 0f
        val iconWidth = if (hasIcon) iconPaint.measureText(icon) else 0f
        val labelHeight = if (label != null) (labelPaint.descent() - labelPaint.ascent()) + padding * 2 else 0f
        val labelWidth = if (label != null) labelPaint.measureText(label) + padding * 2 else 0f

        val width = maxOf(iconWidth, labelWidth, 20f * density)
        val height = maxOf(iconHeight + labelHeight, 20f * density)

        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hasIcon) {
            canvas.drawText(icon!!, width / 2f, -iconPaint.ascent(), iconPaint)
        }
        if (label != null) {
            canvas.drawRoundRect(0f, iconHeight, width, iconHeight + labelHeight, padding, padding, labelBgPaint)
            canvas.drawText(label, width / 2f, iconHeight + padding - labelPaint.ascent(), labelPaint)
        }

        val anchorV = if (hasIcon && label != null) (iconHeight / 2f) / height else 0.5f
        return BitmapDrawable(resources, bitmap) to anchorV
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** A placed marker plus the data needed to rebuild its icon when zoom crosses [LABEL_ZOOM_THRESHOLD]. */
    private data class MarkerInfo(val marker: Marker, val icon: String?, val label: String)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        // Roughly a regional/state-sized view (e.g. Melbourne/Victoria, Australia).
        private const val DEFAULT_ZOOM = 8.0
        private const val NEARBY_RADIUS_METERS = 50_000.0
        // Show text labels once zoomed in to roughly street level.
        private const val LABEL_ZOOM_THRESHOLD = 15.0
        // Margin kept between the fitted points and the viewport edge when framing
        // multiple pins, so edge-most pins aren't drawn at/past the edge.
        private const val MAP_FIT_PADDING_DP = 48
    }
}
