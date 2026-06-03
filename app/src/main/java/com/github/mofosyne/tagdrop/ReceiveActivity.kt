package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
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
 * Legacy data: URI fragments use dumb-append mode for backward compatibility.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding

    private val assembler = ChunkAssembler()
    private val legacyChunks = mutableListOf<String>()

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
        val scanned = result.contents ?: return  // user cancelled — do nothing
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
                    else -> updateDisplay()
                }
            }
            is TagDropPayload.Legacy -> {
                legacyChunks.add(payload.dataUri)
                updateDisplay()
                toast(getString(R.string.legacy_fragment, legacyChunks.size))
                launchScanner()
            }
            null -> {
                // Unknown format — fall back to dumb-append (issue #13 backward compat)
                legacyChunks.add(scanned)
                updateDisplay()
                toast(getString(R.string.unknown_fragment, legacyChunks.size))
                launchScanner()
            }
        }
    }

    /** Cache is complete — save to DB and open immediately. */
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
        }
        openContent(mimeType, content)
        clearState()
    }

    private fun launchLegacyContent() {
        if (legacyChunks.isEmpty()) return
        val uri = legacyChunks.joinToString("")
        openDataUri(uri)
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
        updateDisplay()
    }

    private fun updateDisplay() {
        val statusText = when {
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
        binding.textStatus.text = statusText
        binding.buttonLaunch.isEnabled = legacyChunks.isNotEmpty()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
