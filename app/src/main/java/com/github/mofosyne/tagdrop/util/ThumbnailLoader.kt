package com.github.mofosyne.tagdrop.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.github.mofosyne.tagdrop.data.db.FoundCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a small downsampled preview [Bitmap] from a [FoundCache]'s resolved `contentBytes`,
 * for use in the list-row icon slot (`textIcon`'s `ImageView` sibling) wherever
 * `mimeType.startsWith("image/")` — see [FoundCache.isThumbnailEligible]. Caches decoded bitmaps
 * in memory so repeated rebinds during scrolling don't re-decode the same image.
 */
object ThumbnailLoader {
    private const val TARGET_PX = 128

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
            decodeSampled(bytes)?.also { cache.put(key, it) }
        }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_PX && height / (sample * 2) >= TARGET_PX) sample *= 2
        return sample
    }
}
