package com.github.mofosyne.tagdrop.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.github.mofosyne.tagdrop.data.db.FoundCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Decodes a small preview [Bitmap] from a [FoundCache]'s resolved `contentBytes`, for use in the
 * list-row icon slot (`textIcon`'s `ImageView` sibling) wherever `mimeType.startsWith("image/")`
 * — see [FoundCache.isThumbnailEligible]. Raster formats are downsampled via [BitmapFactory];
 * `image/svg+xml` is rasterized via AndroidSVG, since [BitmapFactory] can't parse XML. Caches
 * decoded bitmaps in memory so repeated rebinds during scrolling don't re-decode the same image.
 */
object ThumbnailLoader {
    private const val TARGET_PX = 128
    private const val SVG_MIME_TYPE = "image/svg+xml"

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Distinguishes a row's `cacheId` from any earlier content at that id (e.g. SPEC §9 unlock replacing `contentBytes`). */
    private fun cacheKey(cache: FoundCache) = "${cache.cacheId}@${cache.contentBytes?.size ?: 0}"

    /** Returns a decoded thumbnail for [cache], or null if it has no eligible/decodable image content. Safe to call repeatedly; cheap on cache hit. */
    suspend fun decode(found: FoundCache): Bitmap? {
        val bytes = found.contentBytes ?: return null
        val key = cacheKey(found)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val bitmap = if (found.mimeType == SVG_MIME_TYPE) decodeSvg(bytes) else decodeSampled(bytes)
            bitmap?.also { cache.put(key, it) }
        }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Renders into a fixed [TARGET_PX] square; AndroidSVG fits the document inside it per the
     * SVG's own `preserveAspectRatio` (default: centered, letterboxed), same as an `<img>` would.
     * Forcing the outer width/height to "100%" makes AndroidSVG size the document to the canvas
     * it's actually given rather than to any fixed pixel `width`/`height` attributes on the root
     * `<svg>` — without this, a document like `<svg width="200" height="200" viewBox="0 0 100
     * 100">` renders at its literal 200×200, and only the top-left [TARGET_PX]² slice of that
     * would land on our canvas instead of the whole, scaled-down document.
     */
    private fun decodeSvg(bytes: ByteArray): Bitmap? = runCatching {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(TARGET_PX, TARGET_PX, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        bitmap
    }.getOrNull()

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_PX && height / (sample * 2) >= TARGET_PX) sample *= 2
        return sample
    }
}
