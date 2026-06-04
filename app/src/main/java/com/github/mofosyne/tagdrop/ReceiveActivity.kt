package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.ChunkAssembler
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import com.github.mofosyne.tagdrop.databinding.ActivityReceiveBinding
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

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        handleScanResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_scan)

        binding.buttonScan.setOnClickListener   { launchScanner() }
        binding.buttonClear.setOnClickListener  { clearState() }
        binding.buttonLaunch.setOnClickListener { launchLegacyContent() }
        binding.buttonReadme.setOnClickListener {
            startActivity(Intent(this, ReadMeActivity::class.java))
        }

        // Handle tagdrop:// deep-link if started via intent
        intent?.dataString?.let { uri ->
            if (uri.startsWith("tagdrop://")) processScanned(uri)
        }

        updateDisplay()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun launchScanner() {
        scanLauncher.launch(ScanOptions().apply {
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.AZTEC, ScanOptions.DATA_MATRIX)
        })
    }

    private fun handleScanResult(result: ScanIntentResult) {
        val scanned = result.contents ?: return
        processScanned(scanned)
    }

    private fun processScanned(scanned: String) {
        when (val payload = TagDropCodec.decode(scanned)) {
            is TagDropPayload.Single -> {
                val content = TagDropCodec.decompressPayload(payload.content, payload.compression)
                completeSingle(payload.cacheId.toHex(), payload.hint, payload.filename, payload.mimeType, content)
            }
            is TagDropPayload.Manifest -> {
                assembler.add(payload)
                lastPaper = null
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
                            state.mimeType, state.content
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
        lifecycleScope.launch {
            AppDatabase.get(this@ReceiveActivity).paperDao().insert(
                ScannedPaper(
                    rootHash  = payload.rootHash.toHex(),
                    scannedAt = System.currentTimeMillis(),
                    label     = payload.label,
                    set       = payload.set,
                    slug      = payload.slug,
                    cborBytes = cbor
                )
            )
        }
        lastPaper = payload
        updateDisplay()
        toast(getString(R.string.paper_scanned, payload.files.size))
    }

    /** Cache is complete — save to DB, then open. openContent/clearState run after insert. */
    private fun completeSingle(
        cacheId: String, hint: String?, filename: String?,
        mimeType: String, content: ByteArray
    ) {
        lifecycleScope.launch {
            AppDatabase.get(this@ReceiveActivity).cacheDao().insert(
                FoundCache(
                    cacheId      = cacheId,
                    discoveredAt = System.currentTimeMillis(),
                    hint         = hint,
                    filename     = filename,
                    mimeType     = mimeType,
                    contentBytes = content
                )
            )
            openContent(mimeType, content)
            clearState()
        }
    }

    private fun launchLegacyContent() {
        if (legacyChunks.isEmpty()) return
        openDataUri(legacyChunks.joinToString(""))
    }

    private fun openContent(mimeType: String, bytes: ByteArray) {
        val dataUri = "data:$mimeType;base64," +
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        openDataUri(dataUri)
    }

    private fun openDataUri(dataUri: String) {
        startActivity(
            Intent(this, ViewDataUriActivity::class.java)
                .putExtra(ViewDataUriActivity.EXTRA_DATA_URI, dataUri)
        )
    }

    private fun clearState() {
        assembler.reset()
        legacyChunks.clear()
        lastPaper = null
        updateDisplay()
    }

    private fun updateDisplay() {
        val statusText = when {
            legacyChunks.isNotEmpty() -> {
                getString(R.string.status_legacy, legacyChunks.size) + "\n\n" +
                    legacyChunks.joinToString("").take(300)
            }
            lastPaper != null -> buildPaperStatusText(lastPaper!!)
            !assembler.hasManifest -> getString(R.string.status_ready)
            else -> {
                val state = assembler.currentState()
                if (state is ChunkAssembler.State.Collecting)
                    getString(R.string.status_collecting, state.received, state.total, state.hint ?: "")
                else getString(R.string.status_ready)
            }
        }
        binding.textStatus.text = statusText
        binding.buttonLaunch.isEnabled = legacyChunks.isNotEmpty()
    }

    private fun buildPaperStatusText(paper: TagDropPayload.PaperManifest): String = buildString {
        appendLine(paper.label ?: getString(R.string.paper_manifest_label))
        if (paper.set != null) appendLine(getString(R.string.paper_set, paper.set))
        if (paper.slug != null) appendLine("/${paper.slug}")
        appendLine()
        appendLine(getString(R.string.paper_files_header, paper.files.size))
        for (f in paper.files) appendLine("  • ${f.slug}  [${f.mimeType}]")
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
}
