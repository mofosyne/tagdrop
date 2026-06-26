package com.github.mofosyne.tagdrop

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.RetainedKey
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.Sector
import com.github.mofosyne.tagdrop.data.format.SectorAssembler
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import com.github.mofosyne.tagdrop.data.format.TagDropScan
import com.github.mofosyne.tagdrop.databinding.ActivityReceiveBinding
import com.github.mofosyne.tagdrop.ui.ScanBlock
import com.github.mofosyne.tagdrop.ui.ScanBoardAdapter
import com.github.mofosyne.tagdrop.util.LocationUtils
import com.github.mofosyne.tagdrop.util.QrContentClassifier
import com.github.mofosyne.tagdrop.util.showCborDebugDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultMetadataType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Continuously scans QR/Data Matrix/Aztec codes via an embedded camera preview and
 * reassembles the TagDrop payload as each sector is decoded (SPEC §5).
 *
 * Every `tagdrop:` code is a sector ([SectorAssembler]); a single-sector cache is saved and
 * opened immediately, while a multi-sector cache accumulates until every sector is collected
 * (order-independent — useful for geographic distribution across a trail). Papers are saved
 * as directories and displayed for browsing. Legacy data: URI fragments use dumb-append mode
 * for backward compatibility.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding

    private val assembler = SectorAssembler()
    private val legacyChunks = mutableListOf<String>()
    private var lastPaper: TagDropPayload.Paper? = null

    /** All cached items, used to mark a scanned paper's files as found on the scan board. */
    private var latestCaches: List<FoundCache> = emptyList()

    /** Grid of file "blocks" shown while scanning a paper's directory; fills in as files are found. */
    private val scanBoardAdapter = ScanBoardAdapter(onOpen = { cache ->
        openContent(cache.mimeType, cache.contentBytes!!, cache.cacheId)
    })

    /** The most recently decoded sector, kept for the "Inspect CBOR" diagnostic menu item. */
    private var lastScannedSector: Sector? = null

    /** Debounce so the same code isn't reprocessed on every camera frame it's visible in. */
    private var lastDecodedText: String? = null
    private var lastDecodedAt: Long = 0L

    /** Null on devices with no NFC hardware — every NFC call below is then a no-op via `?.`. */
    private var nfcAdapter: NfcAdapter? = null

    /** Reused (must be the same instance) across enable/disableForegroundDispatch calls. */
    private val nfcPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /** Restricts foreground dispatch to TagDrop's own NDEF MIME type (SPEC §12). */
    private val nfcIntentFilters: Array<IntentFilter> by lazy {
        arrayOf(IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply { addDataType(TagDropCodec.NFC_MIME_TYPE) })
    }

    private val barcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            val now = System.currentTimeMillis()

            // Fully-binary codes carry the raw CBOR sequence directly in the symbol's byte-mode
            // segment, with no `tagdrop:`/Base41 text wrapper (SPEC §13's "raw CBOR sequence
            // form") -- denser than Base41, worthwhile for non-initial sectors since those are
            // always camera-scanned and never hand-typed/shared as text, unlike a `tagdrop:` link.
            val rawBytes = rawByteSegment(result)
            val rawScan = rawBytes?.let { TagDropCodec.decodeRaw(it) }
            if (rawScan != null) {
                val key = "raw:" + rawBytes.toHex()
                if (key == lastDecodedText && (now - lastDecodedAt) < SCAN_COOLDOWN_MS) return
                lastDecodedText = key
                lastDecodedAt = now
                processScan(rawScan)
                return
            }

            val text = result.text ?: return
            if (text == lastDecodedText && (now - lastDecodedAt) < SCAN_COOLDOWN_MS) return
            lastDecodedText = text
            lastDecodedAt = now
            processScanned(text, result.result.barcodeFormat)
        }
        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
    }

    /** Concatenates a decoded symbol's byte-mode segment(s), if any -- see [barcodeCallback]. */
    @Suppress("UNCHECKED_CAST")
    private fun rawByteSegment(result: BarcodeResult): ByteArray? =
        (result.result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>)
            ?.takeIf { it.isNotEmpty() }
            ?.flatMap { it.toList() }
            ?.toByteArray()

    /**
     * A tapped NFC tag delivers its NDEF message(s) via [intent] -- either [onNewIntent] (app
     * already foregrounded, via [nfcPendingIntent]/[nfcIntentFilters]) or a cold start through
     * the manifest's NDEF intent-filter. Each TagDrop MIME record's payload is the raw CBOR
     * sequence with no Base41 wrapper (SPEC §12), the same bytes [TagDropCodec.decodeRaw] already
     * parses for fully-binary QR codes -- so a tag decodes through the identical [processScan]
     * pipeline a camera scan does.
     */
    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val messages = intent.ndefMessages()
        for (message in messages) {
            for (record in message.records) {
                if (record.toMimeType() != TagDropCodec.NFC_MIME_TYPE) continue
                val scan = TagDropCodec.decodeRaw(record.payload) ?: continue
                val now = System.currentTimeMillis()
                val key = "nfc:" + record.payload.toHex()
                if (key == lastDecodedText && (now - lastDecodedAt) < SCAN_COOLDOWN_MS) return
                lastDecodedText = key
                lastDecodedAt = now
                processScan(scan)
                return
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.ndefMessages(): List<NdefMessage> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        })?.filterIsInstance<NdefMessage>() ?: emptyList()

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        handleNfcIntent(intent)

        updateDisplay()
    }

    /** Cold start via the manifest's NDEF intent-filter is just another launch [Intent] (SPEC §12). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_receive, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_inspect_cbor).isEnabled = lastScannedSector != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_inspect_cbor -> { inspectLastScannedCbor(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Shows the raw CBOR of the most recently scanned sector, as decoded — pre-reassembly/storage. */
    private fun inspectLastScannedCbor() {
        val sector = lastScannedSector ?: return
        val title = (sector.partMeta.cacheId?.toHex() ?: "key-only") +
            if (sector.partMeta.sectorCount > 1) " #${sector.partMeta.sectorIndex + 1}" else ""
        showCborDebugDialog(TagDropCodec.sectorCbor(sector), title)
    }

    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, nfcIntentFilters, null)
        if (hasCameraPermission()) {
            restoreScannerUi()
            binding.barcodeScanner.resume()
        } else {
            showCameraPermissionDenied()
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
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
        prefs.edit { putBoolean(PREF_LOCATION_RATIONALE_SHOWN, true) }
        AlertDialog.Builder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_message)
            .setPositiveButton(R.string.location_permission_allow) { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            .setNegativeButton(R.string.location_permission_not_now, null)
            .show()
    }

    private fun processScanned(scanned: String, format: BarcodeFormat = BarcodeFormat.QR_CODE) {
        val scan = TagDropCodec.decode(scanned)
        if (scan != null) { processScan(scan); return }
        when {
            // tagdrop://... is a navigation link meant for use inside a page, not a code to scan.
            scanned.startsWith("tagdrop://") -> toast(getString(R.string.nav_link_scanned))
            // tagdrop:... that failed to decode: unsupported version or corrupted data — not a legacy fragment.
            scanned.startsWith("tagdrop:") -> toast(getString(R.string.unsupported_code))
            // Already mid-accumulating a legacy data: URI split across codes (SPEC §11, started by
            // a prior data:-prefixed scan, handled via TagDropScan.LegacyScan) -- this fragment
            // continues it.
            legacyChunks.isNotEmpty() -> {
                legacyChunks.add(scanned)
                if (!tryCompleteLegacy()) {
                    updateDisplay()
                    toast(getString(R.string.unknown_fragment, legacyChunks.size))
                }
            }
            // A complete, standalone non-TagDrop code (URL, plain text, vCard, Wi-Fi config, ...)
            // -- SPEC.md defines no multi-fragment scheme for these (§11 is data: URIs only), so
            // there's no "more fragments" to wait for. Cache it immediately as content-addressed
            // raw content instead of stranding it in legacyChunks with no way to complete.
            else -> completeRawScan(scanned, format)
        }
    }

    /**
     * Dispatches an already-decoded scan, regardless of whether it arrived as `tagdrop:` URI
     * text (Base41) or a fully-binary code's raw CBOR sequence (SPEC §13) — see [barcodeCallback].
     * A sector is fed to [SectorAssembler]; a legacy data: URI joins the dumb-append buffer.
     */
    private fun processScan(scan: TagDropScan) {
        when (scan) {
            is TagDropScan.SectorScan -> {
                lastScannedSector = scan.sector
                invalidateOptionsMenu()
                handleState(assembler.add(scan.sector))
            }
            is TagDropScan.LegacyScan -> {
                legacyChunks.add(scan.payload.dataUri)
                if (!tryCompleteLegacy()) {
                    updateDisplay()
                    toast(getString(R.string.legacy_fragment, legacyChunks.size))
                }
            }
        }
    }

    /** Routes a freshly-computed [SectorAssembler.State] for the just-scanned sector's payload. */
    private fun handleState(state: SectorAssembler.State) {
        when (state) {
            is SectorAssembler.State.Collecting -> {
                updateDisplay()
                toast(getString(R.string.chunk_progress, state.received, state.total, formatMissingIndices(state.missingIndices)))
            }
            is SectorAssembler.State.ContentReady -> lifecycleScope.launch { handleContentReady(state) }
            is SectorAssembler.State.PaperReady -> handlePaper(state.paper, state.streamBytes)
            is SectorAssembler.State.AwaitingKey -> lifecycleScope.launch { handleAwaitingKey(state) }
            is SectorAssembler.State.HashMismatch -> { toast(getString(R.string.hash_mismatch)); updateDisplay() }
            is SectorAssembler.State.Failed -> { toast(getString(R.string.unsupported_code)); updateDisplay() }
            is SectorAssembler.State.Idle -> updateDisplay()
        }
    }

    private fun handlePaper(paper: TagDropPayload.Paper, streamBytes: ByteArray) {
        val location = getLastKnownLocation()
        val resolved = LocationUtils.resolveLocation(
            paper.lat, paper.lng, paper.radiusM, paper.preferDeclaredLocation,
            location?.first, location?.second
        )
        lifecycleScope.launch {
            AppDatabase.get(this@ReceiveActivity).paperDao().insert(
                ScannedPaper(
                    rootHash        = paper.rootHash.toHex(),
                    scannedAt       = System.currentTimeMillis(),
                    label           = paper.label,
                    set             = paper.set,
                    slug            = paper.slug,
                    cborBytes       = streamBytes,
                    collectionId    = paper.collectionId?.toHex(),
                    collectionLabel = paper.collectionLabel,
                    collectionTag   = paper.collectionTag,
                    lat             = resolved.lat,
                    lng             = resolved.lng,
                    locationRadiusM = resolved.radiusM,
                    icon            = paper.icon,
                    inReplyTo       = paper.inReplyTo?.toHex()
                )
            )
            paper.keyMaterial?.let { handleDiscoveredKey(it, paper.retainKey, paper.label) }
            for (related in paper.related) {
                related.keyMaterial?.let { handleDiscoveredKey(it, related.retainKey, related.hint) }
            }
        }
        lastPaper = paper
        updateDisplay()
        toast(getString(R.string.paper_scanned, paper.files.size))
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
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null,
        pendingOverrideBlob: ByteArray? = null, pendingCompression: Int = 0,
        wasEncrypted: Boolean = false,
        kdfAlg: Int = 0, kdfSalt: ByteArray? = null,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null, title: String? = null, description: String? = null,
        createdAt: Long? = null
    ) {
        val location = getLastKnownLocation()
        val resolved = LocationUtils.resolveLocation(lat, lng, radiusM, preferDeclaredLocation, location?.first, location?.second)
        val paper = lastPaper
        lifecycleScope.launch {
            val cacheDao = AppDatabase.get(this@ReceiveActivity).cacheDao()
            val alreadyFound = cacheDao.getById(cacheId) != null
            cacheDao.insert(
                FoundCache(
                    cacheId            = cacheId,
                    discoveredAt       = System.currentTimeMillis(),
                    hint               = hint,
                    filename           = filename,
                    mimeType           = mimeType,
                    contentBytes       = content,
                    collectionId       = collectionId,
                    collectionLabel    = collectionLabel,
                    collectionTag      = collectionTag,
                    lat                = resolved.lat,
                    lng                = resolved.lng,
                    locationRadiusM    = resolved.radiusM,
                    icon               = icon,
                    pendingOverrideBlob = pendingOverrideBlob,
                    pendingCompression  = pendingCompression,
                    wasEncrypted        = wasEncrypted,
                    kdfAlg              = kdfAlg,
                    kdfSalt             = kdfSalt,
                    inReplyTo           = inReplyTo?.toHex(),
                    title               = title,
                    description         = description,
                    createdAt           = createdAt
                )
            )
            if (paper != null) {
                val slug = paper.files.find { it.fileId.toHex() == cacheId }?.slug
                toast(getString(R.string.file_cached, slug ?: hint ?: filename ?: cacheId.take(8)))
            } else {
                if (alreadyFound) toast(getString(R.string.already_found))
                openContent(mimeType, content, cacheId)
                clearState()
            }
        }
    }

    /**
     * Handles a fully-reassembled Content payload (SPEC §9): discovers any carried `key_material`
     * first, then checks its pending override blob against every retained key. If one
     * authenticates, the override map's fields replace the clear map's. Otherwise the clear
     * map's own content is cached as-is — a cover story, decoy, or genuine unremarkable
     * content — with the still-unresolved blob kept for [unlockPending] to retry later. A code
     * that carries only a key (empty content/mimeType) is never cached.
     */
    private suspend fun handleContentReady(state: SectorAssembler.State.ContentReady) {
        state.keyMaterial?.let { handleDiscoveredKey(it, state.retainKey, state.hint) }

        if (state.content.isEmpty() && state.mimeType.isEmpty()) {
            if (state.keyMaterial != null) toast(getString(R.string.key_code_scanned))
            return
        }

        val cacheId = state.cacheId ?: return
        val blob = state.pendingOverrideBlob

        val override = blob?.let { b ->
            retainedKeys().firstNotNullOfOrNull { key ->
                TagDropCodec.tryDecryptOverrideMap(b, key, state.pendingOverrideCompression)
            }
        }
        if (override != null) {
            completeSingle(
                cacheId.toHex(),
                override.hint ?: state.hint,
                override.filename ?: state.filename,
                override.mimeType ?: state.mimeType,
                override.content ?: ByteArray(0),
                state.collectionId?.toHex(), state.collectionLabel, state.collectionTag, state.icon,
                wasEncrypted = true,
                lat = state.lat, lng = state.lng, radiusM = state.radiusM,
                preferDeclaredLocation = state.preferDeclaredLocation,
                inReplyTo = state.inReplyTo, title = state.title, description = state.description,
                createdAt = state.createdAt
            )
            return
        }

        if (blob != null && state.kdfAlg == TagDropCodec.KDF_PBKDF2_SHA256 && state.kdfSalt != null) {
            unlockWithPassphrase(
                hint = state.hint, cacheIdHex = cacheId.toHex(), blob = blob,
                compression = state.pendingOverrideCompression, kdfSalt = state.kdfSalt, kdfIters = state.kdfIters,
                fallbackContent = state.content, filename = state.filename, mimeType = state.mimeType,
                collectionId = state.collectionId?.toHex(), collectionLabel = state.collectionLabel,
                collectionTag = state.collectionTag, icon = state.icon, kdfAlg = state.kdfAlg,
                lat = state.lat, lng = state.lng, radiusM = state.radiusM,
                preferDeclaredLocation = state.preferDeclaredLocation,
                inReplyTo = state.inReplyTo, title = state.title, description = state.description,
                createdAt = state.createdAt
            )
            return
        }

        completeSingle(
            cacheId.toHex(), state.hint, state.filename, state.mimeType, state.content,
            state.collectionId?.toHex(), state.collectionLabel, state.collectionTag, state.icon,
            pendingOverrideBlob = blob, pendingCompression = state.pendingOverrideCompression,
            wasEncrypted = state.wasEncrypted,
            lat = state.lat, lng = state.lng, radiusM = state.radiusM,
            preferDeclaredLocation = state.preferDeclaredLocation,
            inReplyTo = state.inReplyTo, title = state.title, description = state.description,
            createdAt = state.createdAt
        )
    }

    /**
     * A Content payload assembled but its content slot can't be read as plain content (SPEC §9):
     * try every retained key, then a passphrase-derived key if the code declares one. If nothing
     * unlocks it, leave it in the assembler (uncached) and report "still locked".
     */
    private suspend fun handleAwaitingKey(state: SectorAssembler.State.AwaitingKey) {
        for (key in retainedKeys()) {
            val resolved = assembler.tryKey(key)
            if (resolved.isNotEmpty()) { resolved.forEach { completeContentReady(it) }; return }
        }
        if (state.kdfAlg == TagDropCodec.KDF_PBKDF2_SHA256 && state.kdfSalt != null && state.cacheId != null) {
            unlockWithPassphrase(
                hint = state.hint, cacheIdHex = state.cacheId.toHex(), blob = state.contentSlot,
                compression = state.compression, kdfSalt = state.kdfSalt, kdfIters = state.kdfIters,
                fallbackContent = null, filename = null, mimeType = "", collectionId = null,
                collectionLabel = null, collectionTag = null, icon = null, kdfAlg = state.kdfAlg
            )
            return
        }
        toast(getString(R.string.awaiting_key))
        updateDisplay()
    }

    /**
     * Prompts for a passphrase (SPEC §9), derives the key, and tries it against [blob]. On
     * success, optionally retains the derived key and completes with the override map's fields.
     * On failure/cancel, falls back to caching [fallbackContent] (if any) with the blob kept
     * pending — unless there's nothing to show, in which case the code stays locked.
     */
    private suspend fun unlockWithPassphrase(
        hint: String?, cacheIdHex: String, blob: ByteArray, compression: Int,
        kdfSalt: ByteArray, kdfIters: Int, fallbackContent: ByteArray?, filename: String?, mimeType: String,
        collectionId: String?, collectionLabel: String?, collectionTag: String?, icon: String?, kdfAlg: Int,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null, title: String? = null, description: String? = null,
        createdAt: Long? = null
    ) {
        val result = askPassphrase(hint)
        if (result != null) {
            val (passphrase, shouldStore) = result
            val derivedKey = TagDropCodec.deriveKeyFromPassphrase(passphrase, kdfSalt, kdfIters)
            val override = TagDropCodec.tryDecryptOverrideMap(blob, derivedKey, compression)
            if (override != null) {
                if (shouldStore) {
                    val saltHint = kdfSalt.take(4).joinToString("") { "%02x".format(it) }
                    AppDatabase.get(this).keyDao().insert(
                        RetainedKey(derivedKey.toHex(), System.currentTimeMillis(), "passphrase (salt: $saltHint…)")
                    )
                }
                completeSingle(
                    cacheIdHex, override.hint ?: hint, override.filename ?: filename,
                    override.mimeType ?: mimeType, override.content ?: ByteArray(0),
                    collectionId, collectionLabel, collectionTag, icon, wasEncrypted = true,
                    lat = lat, lng = lng, radiusM = radiusM, preferDeclaredLocation = preferDeclaredLocation,
                    inReplyTo = inReplyTo, title = title, description = description,
                    createdAt = createdAt
                )
                return
            }
            toast(getString(R.string.passphrase_wrong))
        }
        if (fallbackContent != null) {
            completeSingle(
                cacheIdHex, hint, filename, mimeType, fallbackContent,
                collectionId, collectionLabel, collectionTag, icon,
                pendingOverrideBlob = blob, pendingCompression = compression,
                wasEncrypted = true, kdfAlg = kdfAlg, kdfSalt = kdfSalt,
                lat = lat, lng = lng, radiusM = radiusM, preferDeclaredLocation = preferDeclaredLocation,
                inReplyTo = inReplyTo, title = title, description = description,
                createdAt = createdAt
            )
        } else {
            toast(getString(R.string.awaiting_key))
            updateDisplay()
        }
    }

    /**
     * Suspends the coroutine while showing a passphrase input dialog (SPEC §9).
     * Returns a pair of (passphrase, shouldStore) when the user submits, or null if cancelled.
     * Uses [CompletableDeferred] to bridge the AlertDialog callback into the coroutine.
     */
    private suspend fun askPassphrase(contentHint: String?): Pair<String, Boolean>? {
        val deferred = CompletableDeferred<Pair<String, Boolean>?>()
        runOnUiThread {
            val editText = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.passphrase_hint_text)
            }
            val message = contentHint?.let { getString(R.string.passphrase_hint_text) + "\n\n" + it }
                ?: getString(R.string.passphrase_hint_text)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.passphrase_dialog_title))
                .setMessage(message)
                .setView(editText)
                .setPositiveButton(getString(R.string.passphrase_remember_key)) { _, _ ->
                    deferred.complete(editText.text.toString() to true)
                }
                .setNeutralButton(getString(R.string.passphrase_unlock_once)) { _, _ ->
                    deferred.complete(editText.text.toString() to false)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    deferred.complete(null)
                }
                .setOnCancelListener { deferred.complete(null) }
                .show()
        }
        return deferred.await()
    }

    /**
     * A `key_material` (SPEC §9) was just discovered — retain it (if recommended), then try
     * it against everything currently locked: cached ciphertext awaiting a key, and any
     * in-progress encrypted assembly. "Discovery, not declaration": scan order between a key
     * and the content it unlocks doesn't matter.
     */
    private suspend fun handleDiscoveredKey(key: ByteArray, retain: Boolean, hint: String?) {
        if (key.size != KEY_MATERIAL_BYTES) return
        if (retain) {
            AppDatabase.get(this).keyDao().insert(RetainedKey(key.toHex(), System.currentTimeMillis(), hint))
        }
        unlockPending(key)
        runCatching { assembler.tryKey(key) }.getOrDefault(emptyList()).forEach { completeContentReady(it) }
    }

    private fun completeContentReady(state: SectorAssembler.State.ContentReady) {
        val cacheId = state.cacheId ?: return
        completeSingle(
            cacheId.toHex(), state.hint, state.filename, state.mimeType, state.content,
            state.collectionId?.toHex(), state.collectionLabel, state.collectionTag, state.icon,
            pendingOverrideBlob = state.pendingOverrideBlob,
            pendingCompression = state.pendingOverrideCompression,
            wasEncrypted = state.wasEncrypted,
            lat = state.lat, lng = state.lng, radiusM = state.radiusM,
            preferDeclaredLocation = state.preferDeclaredLocation,
            inReplyTo = state.inReplyTo, title = state.title, description = state.description,
            createdAt = state.createdAt
        )
    }

    /**
     * Tries [key] against every cached item with a still-unresolved [FoundCache.pendingOverrideBlob]
     * (SPEC §9), "self-correcting" any whose override map it decrypts: the override's present
     * fields overlay the cached clear-map fields, and the blob is cleared.
     */
    private suspend fun unlockPending(key: ByteArray) {
        val cacheDao = AppDatabase.get(this).cacheDao()
        var unlocked = 0
        for (cache in cacheDao.getPendingOverrides()) {
            val blob = cache.pendingOverrideBlob ?: continue
            val override = TagDropCodec.tryDecryptOverrideMap(blob, key, cache.pendingCompression) ?: continue
            cacheDao.insert(
                cache.copy(
                    hint = override.hint ?: cache.hint,
                    filename = override.filename ?: cache.filename,
                    mimeType = override.mimeType ?: cache.mimeType,
                    contentBytes = override.content ?: cache.contentBytes,
                    pendingOverrideBlob = null,
                    pendingCompression = 0,
                    wasEncrypted = true
                )
            )
            unlocked++
        }
        if (unlocked > 0) toast(getString(R.string.unlocked_items, unlocked))
    }

    /** All `key_material` (SPEC §9) retained from previous discoveries, available to try against new ciphertext. */
    private suspend fun retainedKeys(): List<ByteArray> =
        AppDatabase.get(this).keyDao().getAll().map { it.keyHex.hexToBytes() }

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

    /**
     * After each legacy fragment is appended, try to parse the joined string as a
     * complete `data:` URI. If it parses and the base64 decodes cleanly, save it as
     * a [FoundCache] entry and open it — no button press needed.
     * Returns true if completed (caller should skip the "N fragment(s)" toast).
     */
    private fun tryCompleteLegacy(): Boolean {
        val joined = legacyChunks.joinToString("")
        val parsed = parseLegacyDataUri(joined) ?: return false
        val (mimeType, bytes) = parsed
        legacyChunks.clear()
        completeSingle(
            cacheId    = TagDropCodec.contentId(bytes).toHex(),
            hint       = null,
            filename   = null,
            mimeType   = mimeType,
            content    = bytes
        )
        return true
    }

    /**
     * Caches a complete non-TagDrop, non-legacy scan (a URL, plain text, vCard, Wi-Fi config,
     * ...) as standalone content, the same way any other found item is cached -- content-
     * addressed by [TagDropCodec.contentId] so re-scanning the same code is recognised as
     * "already found" rather than duplicated. [QrContentClassifier] tags recognised content
     * (vCard, calendar event, Wi-Fi config, URL, ...) with a hashtag-style collectionTag, an
     * icon, and -- for vCard/calendar, which are real interchange file formats -- a specific
     * mimeType instead of the generic `text/plain` default. Its derived title (contact name,
     * SSID, event summary, ...) becomes this cache's `hint`, the same field every list/title
     * display already falls back to "Untitled" without -- there's no author-declared hint for
     * non-TagDrop content, so this is the only source of a human-readable title.
     */
    private fun completeRawScan(text: String, format: BarcodeFormat) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val classification = QrContentClassifier.classify(text, format)
        completeSingle(
            cacheId       = TagDropCodec.contentId(bytes).toHex(),
            hint          = classification?.title,
            filename      = null,
            mimeType      = classification?.mimeType ?: "text/plain",
            content       = bytes,
            collectionTag = classification?.tag,
            icon          = classification?.icon
        )
    }

    private fun launchLegacyContent() {
        if (legacyChunks.isEmpty()) return
        val joined = legacyChunks.joinToString("")
        val parsed = parseLegacyDataUri(joined)
        if (parsed != null) {
            val (mimeType, bytes) = parsed
            legacyChunks.clear()
            completeSingle(TagDropCodec.contentId(bytes).toHex(), null, null, mimeType, bytes)
        } else {
            openDataUri(joined)
        }
    }

    private fun parseLegacyDataUri(dataUri: String): Pair<String, ByteArray>? {
        if (!dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        val header = dataUri.substring(5, comma)
        if (!header.endsWith(";base64")) return null
        val bytes = runCatching {
            android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.NO_WRAP)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return header.removeSuffix(";base64") to bytes
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
                !assembler.hasPending -> getString(R.string.status_ready)
                else -> when (val state = assembler.currentState()) {
                    is SectorAssembler.State.Collecting ->
                        getString(
                            R.string.status_collecting,
                            state.received, state.total, state.hint ?: "",
                            formatMissingIndices(state.missingIndices)
                        )
                    is SectorAssembler.State.AwaitingKey -> getString(R.string.awaiting_key)
                    else -> getString(R.string.status_ready)
                }
            }
        }
        binding.buttonLaunch.isEnabled = legacyChunks.isNotEmpty()
    }

    /** Renders 0-based missing sector indices as 1-based "#1, #2" for display — sectors can be scanned in any order, so list all of them, not just the next. */
    private fun formatMissingIndices(missingIndices: List<Int>): String =
        missingIndices.joinToString(", ") { "#${it + 1}" }

    private fun buildPaperStatusText(paper: TagDropPayload.Paper): String = buildString {
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
    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }

    companion object {
        private const val SCAN_BOARD_COLUMNS = 4

        /** Minimum time before the same code can be reprocessed, to avoid re-decoding it every frame. */
        private const val SCAN_COOLDOWN_MS = 1500L

        /** Expected length of a SPEC §9 AES-256-GCM `key_material`. */
        private const val KEY_MATERIAL_BYTES = 32

        private const val PREFS_NAME = "tagdrop_prefs"
        private const val PREF_LOCATION_RATIONALE_SHOWN = "location_rationale_shown"
    }
}
