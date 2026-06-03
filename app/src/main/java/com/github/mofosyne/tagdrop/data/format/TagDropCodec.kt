package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Encodes and decodes TagDrop QR payloads.
 *
 * URI scheme:  tagdrop://v1/<type>/<base45-cbor>
 *   s = Single  — complete cache in one QR
 *   m = Manifest — header for a multi-QR cache
 *   c = Chunk   — one geographic fragment
 *
 * CBOR map integer keys:
 *   1  version       uint
 *   2  cache_id      bytes (8)
 *   3  hint          text, optional
 *   4  mime_type     text
 *   5  content       bytes    (Single only)
 *   6  chunk_count   uint     (Manifest only)
 *   7  total_bytes   uint     (Manifest only)
 *   8  sha256        bytes(32)(Manifest only)
 *   9  chunk_index   uint     (Chunk only)
 *   10 chunk_data    bytes    (Chunk only)
 *   11 filename      text, optional
 *   12 compression   uint 0=none 1=deflate
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    private const val SCHEME   = "tagdrop://"
    private const val PATH_S   = "v1/s/"
    private const val PATH_M   = "v1/m/"
    private const val PATH_C   = "v1/c/"

    private const val K_VERSION     = 1
    private const val K_CACHE_ID    = 2
    private const val K_HINT        = 3
    private const val K_MIME        = 4
    private const val K_CONTENT     = 5
    private const val K_CHUNK_COUNT = 6
    private const val K_TOTAL_BYTES = 7
    private const val K_SHA256      = 8
    private const val K_CHUNK_IDX   = 9
    private const val K_CHUNK_DATA  = 10
    private const val K_FILENAME    = 11
    private const val K_COMPRESSION = 12

    // ── Encoding ──────────────────────────────────────────────────────────────

    fun encode(payload: TagDropPayload): String = when (payload) {
        is TagDropPayload.Single -> SCHEME + PATH_S + Base45.encode(
            MiniCbor.encodeMap(listOf(
                K_VERSION     to 1,
                K_CACHE_ID    to payload.cacheId,
                K_HINT        to payload.hint,
                K_FILENAME    to payload.filename,
                K_MIME        to payload.mimeType,
                K_COMPRESSION to payload.compression.takeIf { it != COMPRESSION_NONE },
                K_CONTENT     to payload.content
            ))
        )
        is TagDropPayload.Manifest -> SCHEME + PATH_M + Base45.encode(
            MiniCbor.encodeMap(listOf(
                K_VERSION     to 1,
                K_CACHE_ID    to payload.cacheId,
                K_HINT        to payload.hint,
                K_FILENAME    to payload.filename,
                K_MIME        to payload.mimeType,
                K_COMPRESSION to payload.compression.takeIf { it != COMPRESSION_NONE },
                K_CHUNK_COUNT to payload.chunkCount,
                K_TOTAL_BYTES to payload.totalBytes,
                K_SHA256      to payload.sha256
            ))
        )
        is TagDropPayload.Chunk -> SCHEME + PATH_C + Base45.encode(
            MiniCbor.encodeMap(listOf(
                K_CACHE_ID   to payload.cacheId,
                K_CHUNK_IDX  to payload.index,
                K_CHUNK_DATA to payload.data
            ))
        )
        is TagDropPayload.Legacy -> payload.dataUri
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    fun decode(scanned: String): TagDropPayload? {
        if (scanned.startsWith("data:")) return TagDropPayload.Legacy(scanned)
        if (!scanned.startsWith(SCHEME)) return null
        val rest = scanned.removePrefix(SCHEME)
        return runCatching {
            when {
                rest.startsWith(PATH_S) -> decodeSingle(MiniCbor.decodeMap(Base45.decode(rest.removePrefix(PATH_S))))
                rest.startsWith(PATH_M) -> decodeManifest(MiniCbor.decodeMap(Base45.decode(rest.removePrefix(PATH_M))))
                rest.startsWith(PATH_C) -> decodeChunk(MiniCbor.decodeMap(Base45.decode(rest.removePrefix(PATH_C))))
                else -> null
            }
        }.getOrNull()
    }

    private fun decodeSingle(m: Map<Int, Any>) = TagDropPayload.Single(
        cacheId     = m.bytes(K_CACHE_ID),
        hint        = m.text(K_HINT),
        filename    = m.text(K_FILENAME),
        mimeType    = m.text(K_MIME)!!,
        compression = m.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
        content     = m.bytes(K_CONTENT)
    )

    private fun decodeManifest(m: Map<Int, Any>) = TagDropPayload.Manifest(
        cacheId     = m.bytes(K_CACHE_ID),
        hint        = m.text(K_HINT),
        filename    = m.text(K_FILENAME),
        mimeType    = m.text(K_MIME)!!,
        compression = m.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
        chunkCount  = m.uint(K_CHUNK_COUNT)!!.toInt(),
        totalBytes  = m.uint(K_TOTAL_BYTES)!!.toInt(),
        sha256      = m.bytes(K_SHA256)
    )

    private fun decodeChunk(m: Map<Int, Any>) = TagDropPayload.Chunk(
        cacheId = m.bytes(K_CACHE_ID),
        index   = m.uint(K_CHUNK_IDX)!!.toInt(),
        data    = m.bytes(K_CHUNK_DATA)
    )

    // ── Compression helpers ───────────────────────────────────────────────────

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(data)).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** Decompress payload content if compression field says so. */
    fun decompressPayload(content: ByteArray, compression: Int): ByteArray =
        if (compression == COMPRESSION_DEFLATE) decompress(content) else content

    // ── Map helpers ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun Map<Int, Any>.bytes(key: Int): ByteArray =
        get(key) as? ByteArray ?: throw IllegalArgumentException("Missing required byte-string key $key")

    private fun Map<Int, Any>.text(key: Int): String? = get(key) as? String

    private fun Map<Int, Any>.uint(key: Int): Long? = get(key) as? Long
}
