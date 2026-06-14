package com.github.mofosyne.tagdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.ChunkAssembler
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import com.github.mofosyne.tagdrop.databinding.ActivityReceiveBinding
import com.github.mofosyne.tagdrop.ui.ScanBlock
import com.github.mofosyne.tagdrop.ui.ScanBoardAdapter
import com.github.mofosyne.tagdrop.util.showCborDebugDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.launch

/**
 * Continuously scans QR/Data Matrix/Aztec codes via an embedded camera preview and
 * assembles the TagDrop payload as each code is decoded.
 *
 * Single-code caches are saved and opened immediately.
 * Multi-code caches accumulate until all chunks + manifest are received
 * (order-independent — useful for geographic distribution across a trail).
 * Paper manifests are saved as directories and displayed for browsing.
 * Legacy data: URI fragments use dumb-append mode for backward compatibility.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding

    private val assembler = ChunkAssembler()
    private val legacyChunks = mutableListOf<String>()
    private var lastPaper: TagDropPayload.PaperManifest? = null

    /** All cached items, used to mark a scanned paper's files as found on the scan board. */
    private var latestCaches: List<FoundCache> = emptyList()

    /** Grid of file "blocks" shown while scanning a paper's directory; fills in as files are found. */
    private val scanBoardAdapter = ScanBoardAdapter(onOpen = { cache ->
        openContent(cache.mimeType, cache.contentBytes!!, cache.cacheId)
    })

    /** The most recently decoded payload, kept for the "Inspect CBOR" diagnostic menu item. */
    private var lastScannedPayload: TagDropPayload? = null

    /** Debounce so the same code isn't reprocessed on every camera frame it's visible in. */
    private var lastDecodedText: String? = null
    private var lastDecodedAt: Long = 0L

    private val barcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            val text = result.text ?: return
            val now = System.currentTimeMillis()
            if (text == lastDecodedText && now - lastDecodedAt < SCAN_COOLDOWN_MS) return
            lastDecodedText = text
            lastDecodedAt = now
            processScanned(text)
        }
        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                restoreScannerUi()
                binding.barcodeScanner.resume()
            } else {
                showCameraPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_scan)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
        }

        binding.buttonClear.setOnClickListener  { clearState() }
        binding.buttonLaunch.setOnClickListener { launchLegacyContent() }

        binding.barcodeScanner.statusView.visibility = View.GONE
        binding.barcodeScanner.barcodeView.decoderFactory =
            DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC))
        binding.barcodeScanner.decodeContinuous(barcodeCallback)
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.recyclerScanBoard.layoutManager = GridLayoutManager(this, SCAN_BOARD_COLUMNS)
        binding.recyclerScanBoard.adapter = scanBoardAdapter
        AppDatabase.get(this).cacheDao().getAllCaches().observe(this) { caches ->
            latestCaches = caches
            updateDisplay()
        }

        // Handle tagdrop: encoding-URI deep-link if started via intent (tagdrop://... nav links have no decoder here)
        intent?.dataString?.let { uri ->
            if (uri.startsWith("tagdrop:") && !uri.startsWith("tagdrop://")) processScanned(uri)
        }

        updateDisplay()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_receive, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_inspect_cbor).isEnabled =
            lastScannedPayload?.let { TagDropCodec.rawCbor(it) } != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_inspect_cbor -> { inspectLastScannedCbor(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Shows the raw CBOR of the most recently scanned QR code, as decoded — pre-decompression/storage. */
    private fun inspectLastScannedCbor() {
        val payload = lastScannedPayload ?: return
        val cbor = TagDropCodec.rawCbor(payload) ?: return
        val title = when (payload) {
            is TagDropPayload.Single -> payload.cacheId.toHex()
            is TagDropPayload.Manifest -> payload.cacheId.toHex()
            is TagDropPayload.Chunk -> "${payload.cacheId.toHex()} #${payload.index}"
            is TagDropPayload.PaperManifest -> payload.rootHash.toHex()
            is TagDropPayload.Legacy -> return
        }
        showCborDebugDialog(cbor, title)
    }

    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            restoreScannerUi()
            binding.barcodeScanner.resume()
        } else {
            showCameraPermissionDenied()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }

    /** Shown when camera access is denied: hides the (otherwise blank) preview and explains how to fix it. */
    private fun showCameraPermissionDenied() {
        binding.barcodeScanner.visibility = View.GONE
        binding.recyclerScanBoard.visibility = View.GONE
        binding.buttonLaunch.visibility = View.GONE
        binding.buttonClear.visibility = View.GONE
        binding.textStatus.text = getString(R.string.camera_permission_denied)
        binding.textStatus.setOnClickListener { openAppSettings() }
    }

    /** Restores the normal scanning UI once camera access is granted. */
    private fun restoreScannerUi() {
        binding.barcodeScanner.visibility = View.VISIBLE
        binding.buttonLaunch.visibility = View.VISIBLE
        binding.buttonClear.visibility = View.VISIBLE
        binding.textStatus.setOnClickListener(null)
        updateDisplay()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    /**
     * Explains why TagDrop wants location access before showing the system permission
     * dialog. The explanation is shown only once per install — after that, re-requests
     * (e.g. on later scans) go straight to the system dialog as before.
     */
    private fun requestLocationPermission() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_LOCATION_RATIONALE_SHOWN, false)) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        prefs.edit().putBoolean(PREF_LOCATION_RATIONALE_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_message)
            .setPositiveButton(R.string.location_permission_allow) { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            .setNegativeButton(R.string.location_permission_not_now, null)
            .show()
    }

    private fun processScanned(scanned: String) {
        val decoded = TagDropCodec.decode(scanned)
        lastScannedPayload = decoded
        invalidateOptionsMenu()
        when (val payload = decoded) {
            is TagDropPayload.Single -> {
                val content = TagDropCodec.decompressPayload(payload.content, payload.compression)
                completeSingle(
                    payload.cacheId.toHex(), payload.hint, payload.filename, payload.mimeType, content,
                    payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon
                )
            }
            is TagDropPayload.Manifest -> {
                assembler.add(payload)
                updateDisplay()
                toast(getString(R.string.manifest_scanned, payload.chunkCount))
            }
            is TagDropPayload.Chunk -> {
                when (val state = assembler.add(payload)) {
                    is ChunkAssembler.State.Collecting -> {
                        updateDisplay()
                        toast(getString(R.string.chunk_progress, state.received, state.total))
                    }
                    is ChunkAssembler.State.Complete -> {
                        completeSingle(
                            state.cacheId.toHex(), state.hint, state.filename,
                            state.mimeType, state.content, state.collectionId?.toHex(),
                            state.collectionLabel, state.collectionTag, state.icon
                        )
                    }
                    is ChunkAssembler.State.HashMismatch -> {
                        toast(getString(R.string.hash_mismatch))
                        updateDisplay()
                    }
                    is ChunkAssembler.State.WaitingForManifest -> {
                        toast(getString(R.string.chunk_before_manifest))
                        updateDisplay()
                    }
                    else -> updateDisplay()
                }
            }
            is TagDropPayload.PaperManifest -> handlePaperManifest(payload)
            is TagDropPayload.Legacy -> {
                legacyChunks.add(payload.dataUri)
                updateDisplay()
                toast(getString(R.string.legacy_fragment, legacyChunks.size))
            }
            null -> when {
                // tagdrop://... is a navigation link meant for use inside a page, not a code to scan.
                scanned.startsWith("tagdrop://") -> toast(getString(R.string.nav_link_scanned))
                // tagdrop:... that failed to decode: unsupported version or corrupted data — not a legacy fragment.
                scanned.startsWith("tagdrop:") -> toast(getString(R.string.unsupported_code))
                else -> {
                    legacyChunks.add(scanned)
                    updateDisplay()
                    toast(getString(R.string.unknown_fragment, legacyChunks.size))
                }
            }
        }
    }

    private fun handlePaperManifest(payload: TagDropPayload.PaperManifest) {
        val cbor = TagDropCodec.paperManifestCbor(payload)
        val location = getLastKnownLocation()
        lifecycleScope.launch {
            AppDatabase.get(this@ReceiveActivity).paperDao().insert(
                ScannedPaper(
                    rootHash        = payload.rootHash.toHex(),
                    scannedAt       = System.currentTimeMillis(),
                    label           = payload.label,
                    set             = payload.set,
                    slug            = payload.slug,
                    cborBytes       = cbor,
                    collectionId    = payload.collectionId?.toHex(),
                    collectionLabel = payload.collectionLabel,
                    collectionTag   = payload.collectionTag,
                    lat             = location?.first,
                    lng             = location?.second,
                    icon            = payload.icon
                )
            )
        }
        lastPaper = payload
        updateDisplay()
        toast(getString(R.string.paper_scanned, payload.files.size))
    }

    /**
     * Cache is complete — save to DB. Outside paper mode this opens the content immediately
     * (openContent/clearState). While scanning a paper's directory, the file is simply added
     * to the scan board — the camera keeps scanning continuously, so this doesn't keep
     * bouncing the user into the viewer.
     */
    private fun completeSingle(
        cacheId: String, hint: String?, filename: String?,
        mimeType: String, content: ByteArray, collectionId: String? = null,
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null
    ) {
        val location = getLastKnownLocation()
        val paper = lastPaper
        lifecycleScope.launch {
            val cacheDao = AppDatabase.get(this@ReceiveActivity).cacheDao()
            val alreadyFound = cacheDao.getById(cacheId) != null
            cacheDao.insert(
                FoundCache(
                    cacheId         = cacheId,
                    discoveredAt    = System.currentTimeMillis(),
                    hint            = hint,
                    filename        = filename,
                    mimeType        = mimeType,
                    contentBytes    = content,
                    collectionId    = collectionId,
                    collectionLabel = collectionLabel,
                    collectionTag   = collectionTag,
                    lat             = location?.first,
                    lng             = location?.second,
                    icon            = icon
                )
            )
            if (paper != null) {
                assembler.reset()
                val slug = paper.files.find { it.fileId.toHex() == cacheId }?.slug
                toast(getString(R.string.file_cached, slug ?: hint ?: filename ?: cacheId.take(8)))
            } else {
                if (alreadyFound) toast(getString(R.string.already_found))
                openContent(mimeType, content, cacheId)
                clearState()
            }
        }
    }

    /** Best-known device location at scan time, or null if unavailable/permission not granted. */
    private fun getLastKnownLocation(): Pair<Double, Double>? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val locationManager = getSystemService<LocationManager>() ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }

    private fun launchLegacyContent() {
        if (legacyChunks.isEmpty()) return
        openDataUri(legacyChunks.joinToString(""))
    }

    private fun openContent(mimeType: String, bytes: ByteArray, cacheId: String? = null) {
        val dataUri = "data:$mimeType;base64," +
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        openDataUri(dataUri, cacheId)
    }

    private fun openDataUri(dataUri: String, cacheId: String? = null) {
        startActivity(
            Intent(this, ViewDataUriActivity::class.java)
                .putExtra(ViewDataUriActivity.EXTRA_DATA_URI, dataUri)
                .apply { cacheId?.let { putExtra(ViewDataUriActivity.EXTRA_CACHE_ID, it) } }
        )
    }

    private fun clearState() {
        assembler.reset()
        legacyChunks.clear()
        lastPaper = null
        updateDisplay()
    }

    private fun updateDisplay() {
        val paper = lastPaper
        if (paper != null) {
            binding.textStatus.text = buildPaperStatusText(paper)
            binding.recyclerScanBoard.visibility = View.VISIBLE
            val cachesById = latestCaches.associateBy { it.cacheId }
            scanBoardAdapter.submitList(paper.files.map { f -> ScanBlock(f.slug, f.mimeType, cachesById[f.fileId.toHex()]) })
        } else {
            binding.recyclerScanBoard.visibility = View.GONE
            binding.textStatus.text = when {
                legacyChunks.isNotEmpty() -> {
                    getString(R.string.status_legacy, legacyChunks.size) + "\n\n" +
                        legacyChunks.joinToString("").take(300)
                }
                !assembler.hasManifest -> getString(R.string.status_ready)
                else -> {
                    val state = assembler.currentState()
                    if (state is ChunkAssembler.State.Collecting)
                        getString(R.string.status_collecting, state.received, state.total, state.hint ?: "")
                    else getString(R.string.status_ready)
                }
            }
        }
        binding.buttonLaunch.isEnabled = legacyChunks.isNotEmpty()
    }

    private fun buildPaperStatusText(paper: TagDropPayload.PaperManifest): String = buildString {
        appendLine(paper.label ?: getString(R.string.paper_manifest_label))
        if (paper.set != null) appendLine(getString(R.string.paper_set, paper.set))
        if (paper.slug != null) appendLine("/${paper.slug}")
        if (paper.related.isNotEmpty()) {
            appendLine()
            appendLine(getString(R.string.paper_related_header))
            for (r in paper.related) appendLine("  → ${r.hint}")
        }
        appendLine()
        appendLine(getString(R.string.paper_scan_hint))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val SCAN_BOARD_COLUMNS = 4

        /** Minimum time before the same code can be reprocessed, to avoid re-decoding it every frame. */
        private const val SCAN_COOLDOWN_MS = 1500L

        private const val PREFS_NAME = "tagdrop_prefs"
        private const val PREF_LOCATION_RATIONALE_SHOWN = "location_rationale_shown"
    }
}
