package com.github.mofosyne.tagdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
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
import com.github.mofosyne.tagdrop.data.db.RetainedKey
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
import kotlinx.coroutines.CompletableDeferred
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
            is TagDropPayload.Single -> handleSingle(payload)
            is TagDropPayload.Manifest -> {
                payload.keyMaterial?.let { key ->
                    lifecycleScope.launch { handleDiscoveredKey(key, payload.retainKey, payload.hint) }
                }
                assembler.add(payload)
                updateDisplay()
                toast(getString(R.string.manifest_scanned, payload.chunkCount))
            }
            is TagDropPayload.Chunk -> {
                lifecycleScope.launch { handleAssemblerState(assembler.add(payload)) }
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
            payload.keyMaterial?.let { handleDiscoveredKey(it, payload.retainKey, payload.label) }
            for (related in payload.related) {
                related.keyMaterial?.let { handleDiscoveredKey(it, related.retainKey, related.hint) }
            }
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
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null,
        pendingOverrideBlob: ByteArray? = null, pendingCompression: Int = 0,
        wasEncrypted: Boolean = false,
        kdfAlg: Int = 0, kdfSalt: ByteArray? = null
    ) {
        val location = getLastKnownLocation()
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
                    lat                = location?.first,
                    lng                = location?.second,
                    icon               = icon,
                    pendingOverrideBlob = pendingOverrideBlob,
                    pendingCompression  = pendingCompression,
                    wasEncrypted        = wasEncrypted,
                    kdfAlg              = kdfAlg,
                    kdfSalt             = kdfSalt
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

    /**
     * Handles a single-code payload (SPEC §9): discovers any carried `key_material` first,
     * then checks any [TagDropPayload.Single.overrideBlob] against every retained key. If one
     * authenticates, the override map's fields replace the clear map's. Otherwise, the clear
     * map's own (decompressed) content is cached as-is — a cover story, decoy, or genuine
     * unremarkable content — with the still-unresolved blob kept for [unlockPending] to retry
     * later. A code that carries only a key (empty content/mimeType) is never cached.
     */
    private fun handleSingle(payload: TagDropPayload.Single) {
        lifecycleScope.launch {
            payload.keyMaterial?.let { handleDiscoveredKey(it, payload.retainKey, payload.hint) }

            if (payload.content.isEmpty() && payload.mimeType.isEmpty()) {
                if (payload.keyMaterial != null) toast(getString(R.string.key_code_scanned))
                return@launch
            }

            val blob = payload.overrideBlob
            val override = blob?.let { b ->
                retainedKeys().firstNotNullOfOrNull { key ->
                    TagDropCodec.tryDecryptOverrideMap(b, key, payload.compression)
                }
            }

            if (override != null) {
                // Override decrypted immediately — blob != null so wasEncrypted = true.
                completeSingle(
                    payload.cacheId.toHex(),
                    override.hint ?: payload.hint,
                    override.filename ?: payload.filename,
                    override.mimeType ?: payload.mimeType,
                    override.content ?: ByteArray(0),
                    payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon,
                    wasEncrypted = true
                )
            } else if (
                blob != null &&
                payload.kdfAlg == TagDropCodec.KDF_PBKDF2_SHA256 &&
                payload.kdfSalt != null
            ) {
                // Passphrase-derived key — show dialog to get the passphrase from the user.
                val passphraseResult = askPassphrase(payload.hint)
                if (passphraseResult != null) {
                    val (passphrase, shouldStore) = passphraseResult
                    val derivedKey = TagDropCodec.deriveKeyFromPassphrase(passphrase, payload.kdfSalt, payload.kdfIters)
                    val passphraseOverride = TagDropCodec.tryDecryptOverrideMap(blob, derivedKey, payload.compression)
                    if (passphraseOverride != null) {
                        if (shouldStore) {
                            val saltHint = payload.kdfSalt.take(4).joinToString("") { "%02x".format(it) }
                            AppDatabase.get(this@ReceiveActivity).keyDao().insert(
                                RetainedKey(
                                    keyHex      = derivedKey.toHex(),
                                    discoveredAt = System.currentTimeMillis(),
                                    hint        = "passphrase (salt: $saltHint…)"
                                )
                            )
                        }
                        completeSingle(
                            payload.cacheId.toHex(),
                            passphraseOverride.hint ?: payload.hint,
                            passphraseOverride.filename ?: payload.filename,
                            passphraseOverride.mimeType ?: payload.mimeType,
                            passphraseOverride.content ?: ByteArray(0),
                            payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon,
                            wasEncrypted = true
                        )
                    } else {
                        toast(getString(R.string.passphrase_wrong))
                        // Fall back to caching the clear-map content with blob + kdf fields pending for retry.
                        val content = TagDropCodec.decompressPayload(payload.content, payload.compression)
                        completeSingle(
                            payload.cacheId.toHex(), payload.hint, payload.filename, payload.mimeType, content,
                            payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon,
                            pendingOverrideBlob = blob, pendingCompression = payload.compression,
                            wasEncrypted = true,
                            kdfAlg = payload.kdfAlg, kdfSalt = payload.kdfSalt
                        )
                    }
                } else {
                    // User cancelled — cache clear-map content with blob + kdf fields pending for retry.
                    val content = TagDropCodec.decompressPayload(payload.content, payload.compression)
                    completeSingle(
                        payload.cacheId.toHex(), payload.hint, payload.filename, payload.mimeType, content,
                        payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon,
                        pendingOverrideBlob = blob, pendingCompression = payload.compression,
                        wasEncrypted = true,
                        kdfAlg = payload.kdfAlg, kdfSalt = payload.kdfSalt
                    )
                }
            } else {
                val content = TagDropCodec.decompressPayload(payload.content, payload.compression)
                completeSingle(
                    payload.cacheId.toHex(), payload.hint, payload.filename, payload.mimeType, content,
                    payload.collectionId?.toHex(), payload.collectionLabel, payload.collectionTag, payload.icon,
                    pendingOverrideBlob = blob, pendingCompression = payload.compression,
                    wasEncrypted = blob != null
                )
            }
        }
    }

    /**
     * Suspends the coroutine while showing a passphrase input dialog (SPEC §10).
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
     * it against everything currently locked: cached ciphertext awaiting a key, and an
     * in-progress encrypted chunk assembly. "Discovery, not declaration": scan order between
     * a key and the content it unlocks doesn't matter.
     */
    private suspend fun handleDiscoveredKey(key: ByteArray, retain: Boolean, hint: String?) {
        if (key.size != KEY_MATERIAL_BYTES) return
        if (retain) {
            AppDatabase.get(this).keyDao().insert(RetainedKey(key.toHex(), System.currentTimeMillis(), hint))
        }
        unlockPending(key)
        val state = runCatching { assembler.tryKey(key) }.getOrElse { assembler.currentState() }
        if (state is ChunkAssembler.State.Complete) completeFromState(state)
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

    /**
     * Handles the result of [ChunkAssembler.add] for a scanned chunk. If assembly finished
     * but is still locked (SPEC §9 [ChunkAssembler.State.AwaitingKey]), tries every retained
     * key before falling back to the "still locked" message.
     */
    private suspend fun handleAssemblerState(initial: ChunkAssembler.State) {
        var state = initial
        if (state is ChunkAssembler.State.AwaitingKey) {
            for (key in retainedKeys()) {
                state = runCatching { assembler.tryKey(key) }.getOrElse { assembler.currentState() }
                if (state !is ChunkAssembler.State.AwaitingKey) break
            }
        }
        when (val s = state) {
            is ChunkAssembler.State.Collecting -> {
                updateDisplay()
                toast(getString(R.string.chunk_progress, s.received, s.total))
            }
            is ChunkAssembler.State.Complete -> completeFromState(s)
            is ChunkAssembler.State.HashMismatch -> {
                toast(getString(R.string.hash_mismatch))
                updateDisplay()
            }
            is ChunkAssembler.State.WaitingForManifest -> {
                toast(getString(R.string.chunk_before_manifest))
                updateDisplay()
            }
            is ChunkAssembler.State.AwaitingKey -> {
                toast(getString(R.string.awaiting_key))
                updateDisplay()
            }
        }
    }

    private fun completeFromState(state: ChunkAssembler.State.Complete) {
        completeSingle(
            state.cacheId.toHex(), state.hint, state.filename, state.mimeType, state.content,
            state.collectionId?.toHex(), state.collectionLabel, state.collectionTag, state.icon,
            pendingOverrideBlob = state.pendingOverrideBlob,
            pendingCompression = state.pendingOverrideCompression,
            wasEncrypted = state.pendingOverrideBlob != null
        )
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
                else -> when (val state = assembler.currentState()) {
                    is ChunkAssembler.State.Collecting ->
                        getString(R.string.status_collecting, state.received, state.total, state.hint ?: "")
                    is ChunkAssembler.State.AwaitingKey -> getString(R.string.awaiting_key)
                    else -> getString(R.string.status_ready)
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
