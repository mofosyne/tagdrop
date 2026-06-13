package com.github.mofosyne.tagdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * Scans one or more QR codes and assembles the TagDrop payload.
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

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        handleScanResult(result)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        binding.buttonScan.setOnClickListener   { launchScanner() }
        binding.buttonClear.setOnClickListener  { clearState() }
        binding.buttonLaunch.setOnClickListener { launchLegacyContent() }

        binding.recyclerScanBoard.layoutManager = GridLayoutManager(this, SCAN_BOARD_COLUMNS)
        binding.recyclerScanBoard.adapter = scanBoardAdapter
        AppDatabase.get(this).cacheDao().getAllCaches().observe(this) { caches ->
            latestCaches = caches
            updateDisplay()
        }

        // Handle tagdrop:// deep-link if started via intent
        intent?.dataString?.let { uri ->
            if (uri.startsWith("tagdrop://")) processScanned(uri)
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

    private fun launchScanner() {
        scanLauncher.launch(ScanOptions().apply {
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.DATA_MATRIX, "AZTEC")
        })
    }

    private fun handleScanResult(result: ScanIntentResult) {
        val scanned = result.contents ?: return
        processScanned(scanned)
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
                launchScanner()
            }
            is TagDropPayload.Chunk -> {
                when (val state = assembler.add(payload)) {
                    is ChunkAssembler.State.Collecting -> {
                        updateDisplay()
                        toast(getString(R.string.chunk_progress, state.received, state.total))
                        launchScanner()
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
                launchScanner()
            }
            null -> {
                legacyChunks.add(scanned)
                updateDisplay()
                toast(getString(R.string.unknown_fragment, legacyChunks.size))
                launchScanner()
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
     * to the scan board and the scanner relaunches, so scanning a directory's files doesn't
     * keep bouncing the user into the viewer.
     */
    private fun completeSingle(
        cacheId: String, hint: String?, filename: String?,
        mimeType: String, content: ByteArray, collectionId: String? = null,
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null
    ) {
        val location = getLastKnownLocation()
        val paper = lastPaper
        lifecycleScope.launch {
            AppDatabase.get(this@ReceiveActivity).cacheDao().insert(
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
                launchScanner()
            } else {
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
    }
}
