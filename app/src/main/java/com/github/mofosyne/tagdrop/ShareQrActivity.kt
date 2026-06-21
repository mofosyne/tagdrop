package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ActivityShareQrBinding
import com.github.mofosyne.tagdrop.util.QrUtils
import kotlinx.coroutines.launch

/**
 * Cycles through a cached item's content as one or more QR codes for another device to
 * scan — an offline alternative to the Share sheet when the receiver only has a camera.
 *
 * Content that fits in one QR is shown as a single static code. Larger content is split
 * into multiple sectors ([TagDropCodec.createContentSectors]) and cycled through on a timer;
 * the receiver's continuous scan loop (ReceiveActivity / web reader) reassembles them in any
 * order via [com.github.mofosyne.tagdrop.data.format.SectorAssembler] (SPEC §5).
 */
class ShareQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareQrBinding
    private val handler = Handler(Looper.getMainLooper())

    private var frames: List<String> = emptyList()
    private var frameIndex = 0
    private var cycling = true

    private val advance = Runnable {
        frameIndex = (frameIndex + 1) % frames.size
        showFrame()
        scheduleNext()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_share_qr)

        binding.buttonPause.setOnClickListener { toggleCycling() }

        val cacheId = intent.getStringExtra(EXTRA_CACHE_ID)
        if (cacheId == null) { finish(); return }
        lifecycleScope.launch {
            val cache = AppDatabase.get(this@ShareQrActivity).cacheDao().getById(cacheId)
            if (cache == null || cache.contentBytes == null) { finish(); return@launch }
            setUp(cache)
        }
    }

    private fun setUp(cache: FoundCache) {
        binding.textLabel.text = cache.hint ?: cache.filename ?: getString(R.string.collection_untitled)
        frames = buildFrames(cache)
        binding.buttonPause.visibility = if (frames.size > 1) View.VISIBLE else View.GONE
        showFrame()
        scheduleNext()
    }

    /** One URI if [cache]'s content fits in a single QR, otherwise one URI per sector (SPEC §4.1, §5). */
    private fun buildFrames(cache: FoundCache): List<String> {
        val rawContent = cache.contentBytes!!
        val collectionId = cache.collectionId?.hexToBytes()

        // Only compress if it actually shrinks the payload (DEFLATE can grow already-compact
        // or binary content), mirroring the manual checkbox in CreateActivity/CreatePaperActivity.
        val compress = TagDropCodec.compress(rawContent).size < rawContent.size

        val single = TagDropCodec.encode(
            TagDropCodec.createContentSectors(
                cache.hint, cache.filename, cache.mimeType, rawContent, compress,
                collectionId, cache.collectionLabel, cache.collectionTag, cache.icon
            ).first()
        )
        if (single.length <= TagDropCodec.MAX_URI_LENGTH) return listOf(single)

        return TagDropCodec.createContentSectors(
            cache.hint, cache.filename, cache.mimeType, rawContent, compress,
            collectionId, cache.collectionLabel, cache.collectionTag, cache.icon,
            maxSectorDataBytes = TagDropCodec.MAX_SECTOR_DATA_BYTES
        ).map { TagDropCodec.encode(it) }
    }

    private fun showFrame() {
        binding.imageQr.setImageBitmap(QrUtils.encodeQr(frames[frameIndex], 640))
        binding.textProgress.text = getString(R.string.share_qr_progress, frameIndex + 1, frames.size)
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (cycling && frames.size > 1) handler.postDelayed(advance, FRAME_INTERVAL_MS)
    }

    private fun toggleCycling() {
        cycling = !cycling
        binding.buttonPause.setText(if (cycling) R.string.button_pause else R.string.button_resume)
        if (cycling) scheduleNext() else handler.removeCallbacks(advance)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(advance)
    }

    override fun onResume() {
        super.onResume()
        scheduleNext()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun String.hexToBytes(): ByteArray = runCatching {
        ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
    }.getOrElse { this.encodeToByteArray() }

    companion object {
        const val EXTRA_CACHE_ID = "extra_cache_id"
        private const val FRAME_INTERVAL_MS = 1200L
    }
}
