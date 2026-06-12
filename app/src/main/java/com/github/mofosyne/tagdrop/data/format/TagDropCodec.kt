package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Encodes and decodes TagDrop QR payloads.
 *
 * Encoding URI scheme:  tagdrop://v1/<type>/<base45-cbor>
 *   s = Single  — complete cache in one QR
 *   m = Manifest — header for a multi-QR cache
 *   c = Chunk   — one geographic fragment
 *   p = PaperManifest — directory of files on a physical paper
 *
 * Navigation links (NOT encoding URIs, NOT put in QR codes):
 *   tagdrop://<rootHash-base45>/<slug>
 *   'v' is lowercase — not in the Base45 alphabet — so "v1/" is unambiguous.
 *
 * CBOR map integer keys:
 *   1  version       uint
 *   2  cache_id      bytes(8)  also root_hash for PaperManifest
 *   3  hint          text, optional  also label for PaperManifest
 *   4  mime_type     text
 *   5  content       bytes    (Single only)
 *   6  chunk_count   uint     (Manifest only)
 *   7  total_bytes   uint     (Manifest only)
 *   8  sha256        bytes(32)(Manifest only)
 *   9  chunk_index   uint     (Chunk only)
 *   10 chunk_data    bytes    (Chunk only)
 *   11 filename      text, optional
 *   12 compression   uint 0=none 1=deflate
 *   13 set           text, optional  (PaperManifest)
 *   14 slug          text, optional  (PaperManifest)
 *   15 files         array    (PaperManifest)
 *   16 related       array    (PaperManifest)
 *   17 collection_id    bytes(8), optional  (Single, Manifest, PaperManifest)
 *   18 collection_label text, optional      (Single, Manifest, PaperManifest)
 *   19 collection_tag   text, optional      (Single, Manifest, PaperManifest)
 *   24 icon              text, optional      (Single, Manifest, PaperManifest) — emoji icon
 *   25 reserved for future image icon (bytes — small embedded icon image)
 *
 * File entry sub-keys (within key 15 elements):
 *   20 slug        text
 *   21 mime_type   text
 *   22 file_id     bytes(8)
 *
 * Related paper sub-keys (within key 16 elements):
 *   3  hint        text
 *   13 set         text, optional
 *   14 slug        text, optional
 *   23 paper_id    bytes(8), optional
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    private const val SCHEME   = "tagdrop://"
    private const val PATH_S   = "v1/s/"
    private const val PATH_M   = "v1/m/"
    private const val PATH_C   = "v1/c/"
    private const val PATH_P   = "v1/p/"

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
    private const val K_SET         = 13
    private const val K_SLUG        = 14
    private const val K_FILES       = 15
    private const val K_RELATED     = 16
    private const val K_COLLECTION_ID    = 17
    private const val K_COLLECTION_LABEL = 18
    private const val K_COLLECTION_TAG   = 19
    private const val K_FILE_SLUG   = 20
    private const val K_FILE_MIME   = 21
    private const val K_FILE_ID     = 22
    private const val K_PAPER_ID    = 23
    private const val K_ICON        = 24
    // K_ICON_IMAGE = 25 — reserved for a future small embedded image icon (bytes)

    // ── Content addressing (IPFS-inspired) ───────────────────────────────────

    /** SHA-256(uncompressed content)[0:8] — same bytes, same ID, everywhere. */
    fun contentId(content: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(content).copyOf(8)

    /**
     * SHA-256(paperManifestCbor)[0:8] — the paper's permanent root hash.
     * Compute this AFTER finalizing the manifest; store it as root_hash inside the CBOR,
     * then re-encode. (Same chicken-and-egg resolution as IPFS CIDs.)
     */
    fun rootHashOf(paperManifestCbor: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(paperManifestCbor).copyOf(8)

    // ── Factory helpers ───────────────────────────────────────────────────────

    /** Build a Single payload with an auto-computed content-addressed ID. */
    fun createSingle(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false, collectionId: ByteArray? = null,
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null
    ): TagDropPayload.Single {
        val (content, compression) = if (compress) {
            compress(rawContent) to COMPRESSION_DEFLATE
        } else {
            rawContent to COMPRESSION_NONE
        }
        return TagDropPayload.Single(
            cacheId         = contentId(rawContent),
            hint            = hint,
            filename        = filename,
            mimeType        = mimeType,
            compression     = compression,
            content         = content,
            collectionId    = collectionId,
            collectionLabel = collectionLabel,
            collectionTag   = collectionTag,
            icon            = icon
        )
    }

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
                K_CONTENT     to payload.content,
                K_COLLECTION_ID    to payload.collectionId,
                K_COLLECTION_LABEL to payload.collectionLabel,
                K_COLLECTION_TAG   to payload.collectionTag,
                K_ICON             to payload.icon
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
                K_SHA256      to payload.sha256,
                K_COLLECTION_ID    to payload.collectionId,
                K_COLLECTION_LABEL to payload.collectionLabel,
                K_COLLECTION_TAG   to payload.collectionTag,
                K_ICON             to payload.icon
            ))
        )
        is TagDropPayload.Chunk -> SCHEME + PATH_C + Base45.encode(
            MiniCbor.encodeMap(listOf(
                K_CACHE_ID   to payload.cacheId,
                K_CHUNK_IDX  to payload.index,
                K_CHUNK_DATA to payload.data
            ))
        )
        is TagDropPayload.PaperManifest -> SCHEME + PATH_P + Base45.encode(
            paperManifestCbor(payload)
        )
        is TagDropPayload.Legacy -> payload.dataUri
    }

    /** Raw CBOR for a PaperManifest — use this to compute rootHashOf() and for DB storage. */
    fun paperManifestCbor(payload: TagDropPayload.PaperManifest): ByteArray =
        MiniCbor.encodeMap(listOf(
            K_VERSION  to 1,
            K_CACHE_ID to payload.rootHash,
            K_HINT     to payload.label,
            K_SET      to payload.set,
            K_SLUG     to payload.slug,
            K_FILES    to payload.files.map { f ->
                MiniCbor.CborMap(listOf(
                    K_FILE_SLUG to f.slug,
                    K_FILE_MIME to f.mimeType,
                    K_FILE_ID   to f.fileId
                ))
            },
            K_RELATED  to payload.related.map { r ->
                MiniCbor.CborMap(listOf(
                    K_HINT     to r.hint,
                    K_SET      to r.set,
                    K_SLUG     to r.slug,
                    K_PAPER_ID to r.paperId
                ))
            },
            K_COLLECTION_ID    to payload.collectionId,
            K_COLLECTION_LABEL to payload.collectionLabel,
            K_COLLECTION_TAG   to payload.collectionTag,
            K_ICON             to payload.icon
        ))

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
                rest.startsWith(PATH_P) -> decodePaperManifest(MiniCbor.decodeMap(Base45.decode(rest.removePrefix(PATH_P))))
                else -> null
            }
        }.getOrNull()
    }

    /** Decode a PaperManifest from its raw CBOR bytes (e.g. stored in ScannedPaper.cborBytes). */
    fun decodePaperManifestCbor(cbor: ByteArray): TagDropPayload.PaperManifest? =
        runCatching { decodePaperManifest(MiniCbor.decodeMap(cbor)) }.getOrNull()

    private fun decodeSingle(m: Map<Int, Any>) = TagDropPayload.Single(
        cacheId         = m.bytes(K_CACHE_ID),
        hint            = m.text(K_HINT),
        filename        = m.text(K_FILENAME),
        mimeType        = m.text(K_MIME)!!,
        compression     = m.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
        content         = m.bytes(K_CONTENT),
        collectionId    = m.bytesOrNull(K_COLLECTION_ID),
        collectionLabel = m.text(K_COLLECTION_LABEL),
        collectionTag   = m.text(K_COLLECTION_TAG),
        icon            = m.text(K_ICON)
    )

    private fun decodeManifest(m: Map<Int, Any>) = TagDropPayload.Manifest(
        cacheId         = m.bytes(K_CACHE_ID),
        hint            = m.text(K_HINT),
        filename        = m.text(K_FILENAME),
        mimeType        = m.text(K_MIME)!!,
        compression     = m.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
        chunkCount      = m.uint(K_CHUNK_COUNT)!!.toInt(),
        totalBytes      = m.uint(K_TOTAL_BYTES)!!.toInt(),
        sha256          = m.bytes(K_SHA256),
        collectionId    = m.bytesOrNull(K_COLLECTION_ID),
        collectionLabel = m.text(K_COLLECTION_LABEL),
        collectionTag   = m.text(K_COLLECTION_TAG),
        icon            = m.text(K_ICON)
    )

    private fun decodeChunk(m: Map<Int, Any>) = TagDropPayload.Chunk(
        cacheId = m.bytes(K_CACHE_ID),
        index   = m.uint(K_CHUNK_IDX)!!.toInt(),
        data    = m.bytes(K_CHUNK_DATA)
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodePaperManifest(m: Map<Int, Any>): TagDropPayload.PaperManifest {
        val files = (m[K_FILES] as? List<*>)?.mapNotNull { entry ->
            val em = entry as? Map<Int, Any> ?: return@mapNotNull null
            TagDropPayload.FileEntry(
                slug     = em.text(K_FILE_SLUG) ?: return@mapNotNull null,
                mimeType = em.text(K_FILE_MIME) ?: return@mapNotNull null,
                fileId   = (em[K_FILE_ID] as? ByteArray) ?: return@mapNotNull null
            )
        } ?: emptyList()

        val related = (m[K_RELATED] as? List<*>)?.mapNotNull { entry ->
            val em = entry as? Map<Int, Any> ?: return@mapNotNull null
            TagDropPayload.RelatedPaper(
                hint    = em.text(K_HINT) ?: return@mapNotNull null,
                set     = em.text(K_SET),
                slug    = em.text(K_SLUG),
                paperId = em[K_PAPER_ID] as? ByteArray
            )
        } ?: emptyList()

        return TagDropPayload.PaperManifest(
            rootHash        = m.bytes(K_CACHE_ID),
            label           = m.text(K_HINT),
            set             = m.text(K_SET),
            slug            = m.text(K_SLUG),
            files           = files,
            related         = related,
            collectionId    = m.bytesOrNull(K_COLLECTION_ID),
            collectionLabel = m.text(K_COLLECTION_LABEL),
            collectionTag   = m.text(K_COLLECTION_TAG),
            icon            = m.text(K_ICON)
        )
    }

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

    fun decompressPayload(content: ByteArray, compression: Int): ByteArray =
        if (compression == COMPRESSION_DEFLATE) decompress(content) else content

    // ── Map helpers ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun Map<Int, Any>.bytes(key: Int): ByteArray =
        get(key) as? ByteArray ?: throw IllegalArgumentException("Missing required byte-string key $key")

    private fun Map<Int, Any>.bytesOrNull(key: Int): ByteArray? = get(key) as? ByteArray

    private fun Map<Int, Any>.text(key: Int): String? = get(key) as? String

    private fun Map<Int, Any>.uint(key: Int): Long? = get(key) as? Long

    // ── Debug ─────────────────────────────────────────────────────────────────

    /** Human-readable names for TagDrop's CBOR integer keys, used by [describeCbor]. */
    private val KEY_NAMES = mapOf(
        K_VERSION to "version", K_CACHE_ID to "cache_id/root_hash", K_HINT to "hint/label",
        K_MIME to "mime_type", K_CONTENT to "content", K_CHUNK_COUNT to "chunk_count",
        K_TOTAL_BYTES to "total_bytes", K_SHA256 to "sha256", K_CHUNK_IDX to "chunk_index",
        K_CHUNK_DATA to "chunk_data", K_FILENAME to "filename", K_COMPRESSION to "compression",
        K_SET to "set", K_SLUG to "slug", K_FILES to "files", K_RELATED to "related",
        K_COLLECTION_ID to "collection_id", K_COLLECTION_LABEL to "collection_label",
        K_COLLECTION_TAG to "collection_tag", K_FILE_SLUG to "slug", K_FILE_MIME to "mime_type",
        K_FILE_ID to "file_id", K_PAPER_ID to "paper_id", K_ICON to "icon"
    )

    /**
     * Pretty-prints raw TagDrop CBOR for the on-device debug view: a hex dump followed by a
     * key-by-key breakdown annotated with the field names from the key table above this object.
     */
    fun describeCbor(cbor: ByteArray): String = buildString {
        appendLine("${cbor.size} bytes")
        appendLine(cbor.toHexDump())
        appendLine()
        runCatching { MiniCbor.decodeMap(cbor) }
            .onSuccess { describeMap(it, 0, this) }
            .onFailure { append("Failed to decode as CBOR map: ${it.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun describeMap(map: Map<Int, Any>, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        for ((key, value) in map.toSortedMap()) {
            val label = KEY_NAMES[key]?.let { "$key ($it)" } ?: "$key"
            when (value) {
                is ByteArray -> out.appendLine("$pad$label: ${value.toHexDump()} (${value.size} bytes)")
                is String    -> out.appendLine("$pad$label: \"$value\"")
                is List<*>   -> {
                    out.appendLine("$pad$label: [")
                    for (item in value) {
                        if (item is Map<*, *>) {
                            out.appendLine("$pad  {")
                            describeMap(item as Map<Int, Any>, indent + 2, out)
                            out.appendLine("$pad  }")
                        } else {
                            out.appendLine("$pad  $item")
                        }
                    }
                    out.appendLine("$pad]")
                }
                else -> out.appendLine("$pad$label: $value")
            }
        }
    }

    private fun ByteArray.toHexDump(): String = joinToString(" ") { "%02x".format(it) }
}
