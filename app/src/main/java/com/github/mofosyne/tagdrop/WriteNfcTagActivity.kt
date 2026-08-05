package com.github.mofosyne.tagdrop

import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ActivityWriteNfcTagBinding
import com.github.mofosyne.tagdrop.util.NfcUtils
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Writes a cached item's content onto a physical NFC NDEF tag (SPEC §12): one MIME record per
 * code (its raw CBOR Record Sequence, no Base41/`tagdrop:` wrapper — NFC stores raw bytes per
 * SPEC §13), optionally followed by an Android Application Record so a tap launches TagDrop
 * directly. Content too large for one tag is split across several ([TagDropCodec.createContentSectors]
 * with a per-tag capacity cap) and written across a sequential "tap next tag" loop, mirroring the
 * multi-code QR cycling in [ShareQrActivity] but driven by taps instead of a timer.
 */
class WriteNfcTagActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWriteNfcTagBinding
    private var nfcAdapter: NfcAdapter? = null

    @Volatile private var cache: FoundCache? = null
    @Volatile private var pendingSectors: List<ByteArray>? = null
    @Volatile private var nextSectorIndex = 0
    @Volatile private var includeAppRecord = true
    @Volatile private var includeStandardRecord = false
    @Volatile private var lastWrittenTagId: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityWriteNfcTagBinding.inflate(layoutInflater)
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
        title = getString(R.string.title_write_nfc)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.textStatus.text = getString(R.string.nfc_not_supported)
            binding.checkIncludeAppRecord.visibility = View.GONE
            binding.checkIncludeStandardRecord.visibility = View.GONE
            binding.textStandardRecordNote.visibility = View.GONE
            return
        }

        // Mutually exclusive (SPEC §12): an AAR overrides dispatch regardless of record order,
        // which would defeat the point of putting a standard record at record 0.
        binding.checkIncludeAppRecord.setOnCheckedChangeListener { _, checked ->
            includeAppRecord = checked
            if (checked && binding.checkIncludeStandardRecord.isChecked) binding.checkIncludeStandardRecord.isChecked = false
        }
        binding.checkIncludeStandardRecord.setOnCheckedChangeListener { _, checked ->
            includeStandardRecord = checked
            if (checked && binding.checkIncludeAppRecord.isChecked) binding.checkIncludeAppRecord.isChecked = false
        }

        val cacheId = intent.getStringExtra(EXTRA_CACHE_ID)
        if (cacheId == null) { finish(); return }
        lifecycleScope.launch {
            val loaded = AppDatabase.get(this@WriteNfcTagActivity).cacheDao().getById(cacheId)
            if (loaded == null || loaded.contentBytes == null) { finish(); return@launch }
            cache = loaded
            binding.textLabel.text = loaded.hint ?: loaded.filename ?: getString(R.string.collection_untitled)
            binding.textStatus.text = getString(R.string.write_nfc_waiting)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this, { tag -> onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /**
     * Runs on a binder thread, not the main thread (per [NfcAdapter.enableReaderMode]'s contract)
     * -- UI updates hop back via [runOnUiThread]. [lastWrittenTagId] is updated synchronously here
     * (not inside the posted UI block) so a tag still lingering in the field when the next poll
     * cycle fires is recognized as "already written" before that next callback can re-enter and
     * write the following sector onto it.
     */
    private fun onTagDiscovered(tag: Tag) {
        val cache = cache ?: return
        if (tag.id != null && lastWrittenTagId?.contentEquals(tag.id) == true) return
        val result = runCatching { writeNextSector(tag, cache, includeAppRecord, includeStandardRecord) }
        if (result.isSuccess) lastWrittenTagId = tag.id
        runOnUiThread {
            result.fold(
                onSuccess = { onSectorWritten() },
                onFailure = { e -> showError(e) }
            )
        }
    }

    private fun writeNextSector(tag: Tag, cache: FoundCache, includeAppRecord: Boolean, includeStandardRecord: Boolean) {
        val ndef = Ndef.get(tag)
        val sectors = pendingSectors
            ?: (if (ndef != null) sectorsFittingTag(cache, ndef.maxSize, includeAppRecord, includeStandardRecord)
                else sectorsFittingTag(cache, BLANK_TAG_CAPACITY_BYTES, includeAppRecord, includeStandardRecord))
            ?: throw IOException(
                if (includeStandardRecord) getString(R.string.write_nfc_standard_record_too_large)
                else getString(R.string.write_nfc_too_large)
            )
        pendingSectors = sectors
        if (nextSectorIndex >= sectors.size) return // already done; ignore stray taps

        val code = sectors[nextSectorIndex]
        val standardRecord = if (includeStandardRecord) NfcUtils.buildStandardRecord(cache.mimeType, cache.contentBytes!!) else null
        val message = NfcUtils.buildNdefMessage(code, packageName, includeAppRecord, standardRecord)

        if (ndef != null) {
            if (!ndef.isWritable) throw IOException(getString(R.string.write_nfc_read_only))
            if (message.toByteArray().size > ndef.maxSize) throw IOException(getString(R.string.write_nfc_tag_too_small))
            ndef.connect()
            try { ndef.writeNdefMessage(message) } finally { ndef.close() }
        } else {
            val formatable = NdefFormatable.get(tag) ?: throw IOException(getString(R.string.write_nfc_unsupported_tag))
            formatable.connect()
            try { formatable.format(message) } finally { formatable.close() }
        }
        nextSectorIndex++
    }

    private fun onSectorWritten() {
        val sectors = pendingSectors ?: return
        binding.checkIncludeAppRecord.isEnabled = false
        binding.checkIncludeStandardRecord.isEnabled = false
        binding.textProgress.text = getString(R.string.share_qr_progress, nextSectorIndex, sectors.size)
        binding.textStatus.text =
            if (nextSectorIndex >= sectors.size) getString(R.string.write_nfc_done, sectors.size)
            else getString(R.string.write_nfc_tap_next)
    }

    private fun showError(e: Throwable) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.write_nfc_failed)
        binding.textStatus.text = message
    }

    /**
     * Finds the smallest sector count whose first sector's real [NdefMessage] byte size fits
     * within [tagCapacity], rebuilding with a smaller per-sector cap each time and re-measuring --
     * mirrors [TagDropCodec.createContentSectorsAutoSized]'s two-pass sizing, generalized to an
     * arbitrary measured tag capacity instead of one fixed URI-length budget.
     *
     * [includeStandardRecord] only ever applies cleanly to a standalone single-sector tag (SPEC
     * §12) -- a slice of a multi-tag sectored stream isn't an openable file -- so when set, this
     * never grows past one sector; it returns null instead of falling back to splitting.
     */
    private fun sectorsFittingTag(
        cache: FoundCache, tagCapacity: Int, includeAppRecord: Boolean, includeStandardRecord: Boolean
    ): List<ByteArray>? {
        val rawContent = cache.contentBytes!!
        val collectionId = cache.collectionId?.hexToBytes()
        val compress = TagDropCodec.compress(rawContent).size < rawContent.size
        val standardRecord = if (includeStandardRecord) NfcUtils.buildStandardRecord(cache.mimeType, rawContent) else null

        fun build(maxSectorDataBytes: Int) = TagDropCodec.createContentSectors(
            cache.hint, cache.filename, cache.mimeType, rawContent, compress,
            collectionId, cache.collectionLabel, cache.collectionTag, cache.icon,
            inReplyTo = cache.inReplyTo?.hexToBytes(), title = cache.title, description = cache.description,
            createdAt = cache.createdAt,
            maxSectorDataBytes = maxSectorDataBytes
        )

        fun fitsCapacity(code: ByteArray) =
            NfcUtils.buildNdefMessage(code, packageName, includeAppRecord, standardRecord)
                .toByteArray().size <= tagCapacity

        val single = build(Int.MAX_VALUE)
        if (fitsCapacity(single.codes.first())) return single.codes
        if (includeStandardRecord) return null

        // Wrapped (post-compress, if applicable) Media Payload size — the actual bytes Split
        // divides — recovered as this single unsplit code's length minus its (always-plain)
        // Content Extension, the 1-byte root Bundle array header wrapping the three top-level
        // items together (QDEF-SPEC.md §2/§3.1 self-delimited root — a definite-length array of
        // exactly 3 items, namespace + Extension + Media Preview, still costs 1 header byte),
        // and the root's own namespace declaration (SPEC.md v14 §2.1a — mandatory on every
        // carrier, including NFC NDEF, as of this version). This formula's own arithmetic is
        // unchanged by SPEC.md v16 (Content Extension no longer carries its own `h''` cascade
        // marker, but `single.extensionRaw.size` is read dynamically here, not hard-coded, so
        // that 1-byte saving is already reflected automatically — the three-item root Bundle
        // shape this subtracts around is otherwise identical). Doesn't account for the 4-byte
        // QDEF magic prefix [NfcUtils.buildNdefMessage] now always adds (SPEC.md v17) either —
        // that's a flat per-code constant, not something this per-code `code` size varies by, so
        // it doesn't change how many sectors are needed, only [fitsCapacity]'s absolute
        // pass/fail per candidate. This is only an estimate feeding the retry loop below, not a
        // byte-exact requirement — [fitsCapacity] re-measures the real rebuilt code (magic prefix
        // included) every iteration.
        val total = single.codes.first().size - single.extensionRaw.size - 1 - TagDropCodec.NAMESPACE_DECLARATION_BYTES
        for (count in 2..MAX_SECTOR_PROBES) {
            val candidate = build((total + count - 1) / count)
            if (fitsCapacity(candidate.codes.first())) return candidate.codes
        }
        return null
    }

    private fun String.hexToBytes(): ByteArray = runCatching {
        ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
    }.getOrElse { this.encodeToByteArray() }

    companion object {
        const val EXTRA_CACHE_ID = "extra_cache_id"
        private const val BLANK_TAG_CAPACITY_BYTES = 128
        private const val MAX_SECTOR_PROBES = 64
    }
}
