package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Encodes and decodes TagDrop QR payloads.
 *
 * Encoding URI scheme:  tagdrop:<base45-cbor-sequence>
 *   <base45-cbor-sequence> = Base45( CBOR(version) || CBOR(type) || CBOR(payload) )
 *   version = uint, currently 1
 *   type    = uint: 0 = Single, 1 = Manifest, 2 = Chunk, 3 = PaperManifest
 *
 * Navigation links (NOT encoding URIs, NOT put in QR codes):
 *   tagdrop://<rootHash-base45>/<slug>
 *   Disambiguated by "//": a Base45 sequence of 2+ bytes can never start with
 *   '/' (Base45 index 43), so an encoding URI never has "//" right after the scheme.
 *
 * CBOR payload map integer keys (key 1 "version" is retired — it now lives in
 * the envelope above):
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
 *   26 lat         float64, optional — latitude of the related paper
 *   27 lng         float64, optional — longitude of the related paper
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    /** Encoded URI length that reliably fits in one QR code (CreateActivity/CreatePaperActivity warn past this). */
    const val MAX_URI_LENGTH = 2000

    /**
     * Max payload bytes (post-compression) per Chunk so its encoded `tagdrop:` URI stays
     * under [MAX_URI_LENGTH]: ~20 bytes of CBOR/envelope overhead per chunk, and Base45
     * expands 2 bytes to 3 chars.
     */
    private const val MAX_CHUNK_DATA_BYTES = 1300

    private const val SCHEME         = "tagdrop:"
    private const val NAV_LINK_PREFIX = "tagdrop://"

    private const val VERSION = 1L

    private const val TYPE_SINGLE         = 0
    private const val TYPE_MANIFEST       = 1
    private const val TYPE_CHUNK          = 2
    private const val TYPE_PAPER_MANIFEST = 3

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
    private const val K_LAT         = 26
    private const val K_LNG         = 27

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

    /** Number of Chunks needed to keep each chunk's encoded URI under [MAX_URI_LENGTH]. */
    fun chunkCountForBytes(totalBytes: Int): Int =
        maxOf(1, (totalBytes + MAX_CHUNK_DATA_BYTES - 1) / MAX_CHUNK_DATA_BYTES)

    /**
     * Build a Manifest + [chunkCount] Chunks for content too large for a single QR —
     * mirrors the web generator's encodeMultiChunk (tools/examples/index.html). cache_id
     * is content-addressed from [rawContent], so a Single and a Manifest+Chunks encoding
     * of the same content share an ID. The manifest's sha256 covers the assembled
     * (possibly compressed) bytes; chunks split those bytes into equal-sized pieces
     * (the last one may be shorter).
     */
    fun createManifestAndChunks(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false, chunkCount: Int,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null
    ): Pair<TagDropPayload.Manifest, List<TagDropPayload.Chunk>> {
        require(chunkCount >= 1) { "chunkCount must be >= 1" }
        val cacheId = contentId(rawContent)
        val (assembled, compression) = if (compress) {
            compress(rawContent) to COMPRESSION_DEFLATE
        } else {
            rawContent to COMPRESSION_NONE
        }
        val totalBytes = assembled.size
        val sha256 = MessageDigest.getInstance("SHA-256").digest(assembled)

        val manifest = TagDropPayload.Manifest(
            cacheId         = cacheId,
            hint            = hint,
            filename        = filename,
            mimeType        = mimeType,
            compression     = compression,
            chunkCount      = chunkCount,
            totalBytes      = totalBytes,
            sha256          = sha256,
            collectionId    = collectionId,
            collectionLabel = collectionLabel,
            collectionTag   = collectionTag,
            icon            = icon
        )

        val chunkSize = (totalBytes + chunkCount - 1) / chunkCount
        val chunks = (0 until chunkCount).map { i ->
            val start = minOf(i * chunkSize, totalBytes)
            val end = minOf(start + chunkSize, totalBytes)
            TagDropPayload.Chunk(cacheId = cacheId, index = i, data = assembled.copyOfRange(start, end))
        }
        return manifest to chunks
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    fun encode(payload: TagDropPayload): String = when (payload) {
        is TagDropPayload.Single -> SCHEME + Base45.encode(singleCbor(payload))
        is TagDropPayload.Manifest -> SCHEME + Base45.encode(manifestCbor(payload))
        is TagDropPayload.Chunk -> SCHEME + Base45.encode(chunkCbor(payload))
        is TagDropPayload.PaperManifest -> SCHEME + Base45.encode(paperManifestCbor(payload))
        is TagDropPayload.Legacy -> payload.dataUri
    }

    /** Prefixes a payload map with the version/type CBOR-sequence envelope (SPEC §2). */
    private fun envelope(type: Int, pairs: List<Pair<Int, Any?>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MiniCbor.encodeUInt(VERSION))
        out.write(MiniCbor.encodeUInt(type.toLong()))
        out.write(MiniCbor.encodeMap(pairs))
        return out.toByteArray()
    }

    /** Raw CBOR sequence (envelope + payload) for a Single payload — useful for on-device inspection. */
    fun singleCbor(payload: TagDropPayload.Single): ByteArray =
        envelope(TYPE_SINGLE, listOf(
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

    /** Raw CBOR sequence (envelope + payload) for a Manifest payload — useful for on-device inspection. */
    fun manifestCbor(payload: TagDropPayload.Manifest): ByteArray =
        envelope(TYPE_MANIFEST, listOf(
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

    /** Raw CBOR sequence (envelope + payload) for a Chunk payload — useful for on-device inspection. */
    fun chunkCbor(payload: TagDropPayload.Chunk): ByteArray =
        envelope(TYPE_CHUNK, listOf(
            K_CACHE_ID   to payload.cacheId,
            K_CHUNK_IDX  to payload.index,
            K_CHUNK_DATA to payload.data
        ))

    /** Raw CBOR sequence for any payload, or null for [TagDropPayload.Legacy] which has no CBOR form. */
    fun rawCbor(payload: TagDropPayload): ByteArray? = when (payload) {
        is TagDropPayload.Single -> singleCbor(payload)
        is TagDropPayload.Manifest -> manifestCbor(payload)
        is TagDropPayload.Chunk -> chunkCbor(payload)
        is TagDropPayload.PaperManifest -> paperManifestCbor(payload)
        is TagDropPayload.Legacy -> null
    }

    /** Raw CBOR sequence (envelope + payload) for a PaperManifest — use this to compute rootHashOf() and for DB storage. */
    fun paperManifestCbor(payload: TagDropPayload.PaperManifest): ByteArray =
        envelope(TYPE_PAPER_MANIFEST, paperManifestPairs(payload, payload.rootHash))

    /** Builds the PaperManifest payload-map pairs, with [rootHash] standing in for key 2 (null to omit it). */
    private fun paperManifestPairs(payload: TagDropPayload.PaperManifest, rootHash: ByteArray?): List<Pair<Int, Any?>> =
        listOf(
            K_CACHE_ID to rootHash,
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
                    K_PAPER_ID to r.paperId,
                    K_LAT      to r.lat,
                    K_LNG      to r.lng
                ))
            },
            K_COLLECTION_ID    to payload.collectionId,
            K_COLLECTION_LABEL to payload.collectionLabel,
            K_COLLECTION_TAG   to payload.collectionTag,
            K_ICON             to payload.icon
        )

    /**
     * Build a PaperManifest with an auto-computed content-addressed root hash.
     *
     * Two-pass "chicken-and-egg" resolution (SPEC §4.5, same as IPFS CIDs): encode the
     * manifest without the root-hash field (key 2), hash that CBOR sequence, then return
     * the manifest with the computed hash as its rootHash.
     */
    fun createPaperManifest(
        label: String?, set: String?, slug: String?,
        files: List<TagDropPayload.FileEntry>, related: List<TagDropPayload.RelatedPaper> = emptyList(),
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null
    ): TagDropPayload.PaperManifest {
        val draft = TagDropPayload.PaperManifest(
            rootHash = ByteArray(8), label = label, set = set, slug = slug,
            files = files, related = related,
            collectionId = collectionId, collectionLabel = collectionLabel,
            collectionTag = collectionTag, icon = icon
        )
        val cborNoHash = envelope(TYPE_PAPER_MANIFEST, paperManifestPairs(draft, rootHash = null))
        return draft.copy(rootHash = rootHashOf(cborNoHash))
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    fun decode(scanned: String): TagDropPayload? {
        if (scanned.startsWith("data:")) return TagDropPayload.Legacy(scanned)
        // Navigation links (tagdrop://<rootHash>/<slug>) are not encoding URIs (SPEC §2).
        if (!scanned.startsWith(SCHEME) || scanned.startsWith(NAV_LINK_PREFIX)) return null
        val rest = scanned.removePrefix(SCHEME)
        return runCatching {
            val (type, payload) = decodeEnvelope(Base45.decode(rest)) ?: return@runCatching null
            when (type) {
                TYPE_SINGLE         -> decodeSingle(payload)
                TYPE_MANIFEST       -> decodeManifest(payload)
                TYPE_CHUNK          -> decodeChunk(payload)
                TYPE_PAPER_MANIFEST -> decodePaperManifest(payload)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Splits a CBOR sequence (RFC 8742) into (type, payload map), per the version/type
     * envelope in SPEC §2. Returns null for an unsupported version or malformed envelope.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeEnvelope(bytes: ByteArray): Pair<Int, Map<Int, Any>>? {
        val items = MiniCbor.decodeSequence(bytes)
        if (items.size < 3) return null
        val version = items[0] as? Long ?: return null
        if (version != VERSION) return null
        val type = items[1] as? Long ?: return null
        val payload = items[2] as? Map<Int, Any> ?: return null
        return type.toInt() to payload
    }

    /** Decode a PaperManifest from its raw CBOR sequence bytes (e.g. stored in ScannedPaper.cborBytes). */
    fun decodePaperManifestCbor(cbor: ByteArray): TagDropPayload.PaperManifest? =
        runCatching {
            val (type, payload) = decodeEnvelope(cbor) ?: return@runCatching null
            if (type != TYPE_PAPER_MANIFEST) return@runCatching null
            decodePaperManifest(payload)
        }.getOrNull()

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
                paperId = em[K_PAPER_ID] as? ByteArray,
                lat     = em.doubleOrNull(K_LAT),
                lng     = em.doubleOrNull(K_LNG)
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

    private fun Map<Int, Any>.doubleOrNull(key: Int): Double? = get(key) as? Double

    // ── Debug ─────────────────────────────────────────────────────────────────

    /** Human-readable names for TagDrop's CBOR payload-map integer keys, used by [describeCbor]. */
    private val KEY_NAMES = mapOf(
        K_CACHE_ID to "cache_id/root_hash", K_HINT to "hint/label",
        K_MIME to "mime_type", K_CONTENT to "content", K_CHUNK_COUNT to "chunk_count",
        K_TOTAL_BYTES to "total_bytes", K_SHA256 to "sha256", K_CHUNK_IDX to "chunk_index",
        K_CHUNK_DATA to "chunk_data", K_FILENAME to "filename", K_COMPRESSION to "compression",
        K_SET to "set", K_SLUG to "slug", K_FILES to "files", K_RELATED to "related",
        K_COLLECTION_ID to "collection_id", K_COLLECTION_LABEL to "collection_label",
        K_COLLECTION_TAG to "collection_tag", K_FILE_SLUG to "slug", K_FILE_MIME to "mime_type",
        K_FILE_ID to "file_id", K_PAPER_ID to "paper_id", K_ICON to "icon",
        K_LAT to "lat", K_LNG to "lng"
    )

    /** Human-readable names for the envelope's `type` values, used by [describeCbor]. */
    private val TYPE_NAMES = mapOf(
        TYPE_SINGLE to "Single", TYPE_MANIFEST to "Manifest",
        TYPE_CHUNK to "Chunk", TYPE_PAPER_MANIFEST to "PaperManifest"
    )

    /**
     * Pretty-prints a raw TagDrop CBOR sequence for the on-device debug view: a hex dump,
     * the version/type envelope (SPEC §2), and a key-by-key breakdown of the payload map
     * annotated with the field names from the key table above this object.
     */
    fun describeCbor(cbor: ByteArray): String = buildString {
        appendLine("${cbor.size} bytes")
        appendLine(cbor.toHexDump())
        appendLine()
        runCatching {
            val items = MiniCbor.decodeSequence(cbor)
            require(items.size >= 3) { "Expected version, type, payload — got ${items.size} item(s)" }
            val version = items[0] as Long
            val type = items[1] as Long
            @Suppress("UNCHECKED_CAST")
            val payload = items[2] as Map<Int, Any>
            Triple(version, type, payload)
        }.onSuccess { (version, type, payload) ->
            appendLine("version: $version")
            appendLine("type: $type (${TYPE_NAMES[type.toInt()] ?: "unknown"})")
            appendLine()
            describeMap(payload, 0, this)
        }.onFailure { append("Failed to decode as CBOR sequence: ${it.message}") }
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
