package com.github.mofosyne.tagdrop.data.format

/**
 * Represents a decoded TagDrop payload from a single scanned QR code.
 *
 * The TagDrop URI scheme is: tagdrop://v1/<type>/<base45-cbor>
 *   s = single-code cache (complete content in one QR)
 *   m = manifest (header for a multi-code cache)
 *   c = chunk  (one segment of a multi-code cache)
 */
sealed class TagDropPayload {

    /** Complete cache encoded in a single QR. */
    data class Single(
        val cacheId: ByteArray,   // 8 random bytes, unique cache identifier
        val hint: String?,        // human-readable placement hint, optional
        val filename: String?,    // suggested filename, optional
        val mimeType: String,     // MIME type of content (e.g. "text/html")
        val compression: Int,     // 0 = none, 1 = deflate
        val content: ByteArray    // raw (possibly compressed) payload bytes
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Single && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId.contentHashCode()
    }

    /**
     * Manifest for a multi-code cache. Must be scanned before its chunks.
     * Designed for geographic distribution: each chunk can be at a different location.
     */
    data class Manifest(
        val cacheId: ByteArray,
        val hint: String?,
        val filename: String?,
        val mimeType: String,
        val compression: Int,
        val chunkCount: Int,
        val totalBytes: Int,
        val sha256: ByteArray     // SHA-256 of the assembled (uncompressed) content
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Manifest && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId.contentHashCode()
    }

    /** One chunk of a multi-code cache. Order-independent; reassembled by index. */
    data class Chunk(
        val cacheId: ByteArray,   // links back to the Manifest
        val index: Int,           // 0-based chunk index
        val data: ByteArray
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Chunk && cacheId.contentEquals(other.cacheId) && index == other.index
        override fun hashCode() = 31 * cacheId.contentHashCode() + index
    }

    /** Raw data: URI from the original tagdrop format (backward compatibility). */
    data class Legacy(val dataUri: String) : TagDropPayload()
}
