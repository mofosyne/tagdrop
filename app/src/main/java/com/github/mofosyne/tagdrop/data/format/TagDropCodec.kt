package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
 *   28 encryption    uint, optional   0=none 1=AES-256-GCM (Single, Manifest) — optional cosmetic
 *                    hint only, NOT a precondition — SPEC §9
 *   29 reserved and unused — see SPEC §9 (an override map's nonce travels embedded in its blob)
 *   30 key_material  bytes(32), optional — a decryption key for OTHER content (Single, Manifest, PaperManifest) — SPEC §9
 *   31 retain_key    bool, optional, default true — wherever key_material appears — SPEC §9
 *
 * A Single's raw trailing bytes (after its 3-item CBOR sequence), if >= 28 bytes, or a
 * Manifest's assembled chunk bytes (§5), if >= 28 bytes, are a candidate self-contained
 * `nonce(12) || ciphertext || tag(16)` blob — an encrypted "override map" using key numbers
 * 3 (hint), 4 (mime_type), 5 (content, Single only), 11 (filename) that overlays this map
 * once decrypted by a matching key_material. See SPEC §9.
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
 *   30 key_material bytes(32), optional — decryption key for the related paper — SPEC §9
 *   31 retain_key   bool, optional, default true — SPEC §9
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    const val ENCRYPTION_NONE      = 0
    const val ENCRYPTION_AES256GCM = 1

    private const val AES_KEY_BYTES   = 32
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS    = 128
    private const val GCM_TAG_BYTES   = GCM_TAG_BITS / 8

    /** Minimum size of a self-contained `nonce(12) || ciphertext || tag(16)` override-map blob (SPEC §9). */
    const val OVERRIDE_BLOB_MIN_BYTES = GCM_NONCE_BYTES + GCM_TAG_BYTES

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
    private const val K_ENCRYPTION    = 28
    // 29 reserved and unused — see SPEC §9
    private const val K_KEY_MATERIAL  = 30
    private const val K_RETAIN_KEY    = 31

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

    /**
     * 8 random bytes — `cache_id` for encrypted content (SPEC §9). Encrypted content
     * uses a random ID rather than [contentId] so the ID itself can't be used as a
     * content-equality oracle against a known plaintext.
     */
    fun randomCacheId(): ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }

    // ── Encryption (SPEC §9) ──────────────────────────────────────────────────

    /** Generates a fresh 32-byte AES-256-GCM key (`key_material`, SPEC §9). */
    fun generateKeyMaterial(): ByteArray = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** Generates a fresh 12-byte AES-GCM nonce. MUST be unique per encryption under a given key (SPEC §9). */
    fun generateNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    /** AES-256-GCM encrypt; returns `ciphertext || 16-byte tag` (SPEC §9). */
    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == AES_KEY_BYTES) { "AES-256-GCM key_material must be $AES_KEY_BYTES bytes" }
        require(nonce.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be $GCM_NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM decrypt of `ciphertext || tag`. Returns null if [key]/[nonce] don't
     * authenticate — per SPEC §9 this is the "discovery" match test: a failed auth tag
     * just means this candidate key doesn't apply to this content, not an error.
     */
    fun decryptAesGcm(ciphertextAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
        require(key.size == AES_KEY_BYTES) { "AES-256-GCM key_material must be $AES_KEY_BYTES bytes" }
        require(nonce.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be $GCM_NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return runCatching { cipher.doFinal(ciphertextAndTag) }.getOrNull()
    }

    /**
     * Encrypts [override] as a self-contained `nonce(12) || ciphertext || tag(16)` blob
     * (SPEC §9): the override map's CBOR bytes are compressed per [compression] (the same
     * value the clear map declares for its own `content`/assembled bytes), then
     * AES-256-GCM-encrypted under a fresh nonce.
     */
    fun encryptOverrideMap(override: TagDropPayload.OverrideMap, key: ByteArray, compression: Int): ByteArray {
        val cbor = MiniCbor.encodeMap(listOf(
            K_HINT     to override.hint,
            K_MIME     to override.mimeType,
            K_CONTENT  to override.content,
            K_FILENAME to override.filename
        ))
        val plaintext = if (compression == COMPRESSION_DEFLATE) compress(cbor) else cbor
        val nonce = generateNonce()
        return nonce + encryptAesGcm(plaintext, key, nonce)
    }

    /**
     * Tries [key] against [blob] as `nonce(12) || ciphertext || tag(16)` (SPEC §9). Returns
     * the decoded override map on success, or null if [blob] is too short, [key] doesn't
     * authenticate it, or the plaintext doesn't decode as a CBOR map — any of which just
     * means this candidate doesn't apply here (SPEC §9, "Discovery, not declaration").
     */
    fun tryDecryptOverrideMap(blob: ByteArray, key: ByteArray, compression: Int): TagDropPayload.OverrideMap? {
        if (blob.size < OVERRIDE_BLOB_MIN_BYTES) return null
        val nonce = blob.copyOfRange(0, GCM_NONCE_BYTES)
        val ciphertextAndTag = blob.copyOfRange(GCM_NONCE_BYTES, blob.size)
        val plaintext = decryptAesGcm(ciphertextAndTag, key, nonce) ?: return null
        val cbor = if (compression == COMPRESSION_DEFLATE) {
            runCatching { decompress(plaintext) }.getOrNull() ?: return null
        } else {
            plaintext
        }
        val map = runCatching { MiniCbor.decodeMap(cbor) }.getOrNull() ?: return null
        return TagDropPayload.OverrideMap(
            hint     = map.text(K_HINT),
            mimeType = map.text(K_MIME),
            content  = map.bytesOrNull(K_CONTENT),
            filename = map.text(K_FILENAME)
        )
    }

    /** [resolveSingle]'s result: a Single's final hint/mime_type/filename/content after any override merge (SPEC §9). */
    data class ResolvedSingle(val hint: String?, val mimeType: String, val filename: String?, val content: ByteArray)

    /**
     * Resolves a Single's final view (SPEC §9). If [payload] carries an [TagDropPayload.Single.overrideBlob]
     * and [key] decrypts it, the override map's present fields replace the clear map's —
     * hint/mime_type/content/filename "self-correct" to their real values. Otherwise (no
     * blob, no key, or [key] doesn't authenticate), returns the clear map's own
     * (decompressed) content and fields.
     */
    fun resolveSingle(payload: TagDropPayload.Single, key: ByteArray? = null): ResolvedSingle {
        val blob = payload.overrideBlob
        val override = if (blob != null && key != null) tryDecryptOverrideMap(blob, key, payload.compression) else null
        return if (override != null) {
            ResolvedSingle(
                hint     = override.hint ?: payload.hint,
                mimeType = override.mimeType ?: payload.mimeType,
                filename = override.filename ?: payload.filename,
                content  = override.content ?: ByteArray(0)
            )
        } else {
            ResolvedSingle(
                hint     = payload.hint,
                mimeType = payload.mimeType,
                filename = payload.filename,
                content  = decompressPayload(payload.content, payload.compression)
            )
        }
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /**
     * Build a Single payload with an auto-computed content-addressed ID.
     *
     * [hint]/[filename]/[mimeType]/[rawContent] become the **clear map**'s own fields —
     * shown (and used) until a hidden override map, if any, is unlocked (SPEC §9). They
     * may be a cover story, a decoy, or genuine unremarkable content with no relation to
     * [override].
     *
     * If [override] is given (with [encryptionKey], 32 bytes — see [generateKeyMaterial]),
     * it's AES-256-GCM-encrypted into a self-contained blob (SPEC §9) carried as raw
     * trailing bytes after this payload's CBOR sequence — see [TagDropPayload.Single.overrideBlob].
     * `cacheId` becomes random rather than content-addressed (see [randomCacheId]), and
     * `encryption` is set to the AES-256-GCM hint unless [declareEncryption] is false
     * (the hint is cosmetic only — SPEC §9).
     */
    fun createSingle(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false, collectionId: ByteArray? = null,
        collectionLabel: String? = null, collectionTag: String? = null, icon: String? = null,
        override: TagDropPayload.OverrideMap? = null, encryptionKey: ByteArray? = null,
        declareEncryption: Boolean = true
    ): TagDropPayload.Single {
        val (compressed, compression) = if (compress) {
            compress(rawContent) to COMPRESSION_DEFLATE
        } else {
            rawContent to COMPRESSION_NONE
        }
        val cacheId: ByteArray
        val overrideBlob: ByteArray?
        val encryption: Int
        if (override != null) {
            requireNotNull(encryptionKey) { "encryptionKey is required when override is provided" }
            overrideBlob = encryptOverrideMap(override, encryptionKey, compression)
            cacheId = randomCacheId()
            encryption = if (declareEncryption) ENCRYPTION_AES256GCM else ENCRYPTION_NONE
        } else {
            overrideBlob = null
            cacheId = contentId(rawContent)
            encryption = ENCRYPTION_NONE
        }
        return TagDropPayload.Single(
            cacheId         = cacheId,
            hint            = hint,
            filename        = filename,
            mimeType        = mimeType,
            compression     = compression,
            content         = compressed,
            overrideBlob    = overrideBlob,
            encryption      = encryption,
            collectionId    = collectionId,
            collectionLabel = collectionLabel,
            collectionTag   = collectionTag,
            icon            = icon
        )
    }

    /**
     * Build a "key-only" Single payload (SPEC §9): carries [keyMaterial] for other
     * content, with no `content`/`mime_type` of its own. `cacheId` is random — there's
     * no content to address.
     */
    fun createKeyCode(keyMaterial: ByteArray, retainKey: Boolean = true, hint: String? = null): TagDropPayload.Single {
        require(keyMaterial.size == AES_KEY_BYTES) { "key_material must be $AES_KEY_BYTES bytes" }
        return TagDropPayload.Single(
            cacheId     = randomCacheId(),
            hint        = hint,
            filename    = null,
            mimeType    = "",
            compression = COMPRESSION_NONE,
            content     = ByteArray(0),
            keyMaterial = keyMaterial,
            retainKey   = retainKey
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
     *
     * If [override] is given (with [encryptionKey] — see [generateKeyMaterial]), the
     * assembled chunk bytes ARE the self-contained encrypted override-map blob (SPEC
     * §9, §4.2) rather than [rawContent] — [hint]/[filename]/[mimeType] still describe
     * the manifest's own clear map (a cover story or genuine unremarkable metadata).
     * `cacheId` becomes random rather than content-addressed (see [randomCacheId]), and
     * `encryption` is set to the AES-256-GCM hint unless [declareEncryption] is false.
     */
    fun createManifestAndChunks(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false, chunkCount: Int,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        override: TagDropPayload.OverrideMap? = null, encryptionKey: ByteArray? = null,
        declareEncryption: Boolean = true
    ): Pair<TagDropPayload.Manifest, List<TagDropPayload.Chunk>> {
        require(chunkCount >= 1) { "chunkCount must be >= 1" }
        val (compressed, compression) = if (compress) {
            compress(rawContent) to COMPRESSION_DEFLATE
        } else {
            rawContent to COMPRESSION_NONE
        }
        val cacheId: ByteArray
        val assembled: ByteArray
        val encryption: Int
        if (override != null) {
            requireNotNull(encryptionKey) { "encryptionKey is required when override is provided" }
            assembled = encryptOverrideMap(override, encryptionKey, compression)
            cacheId = randomCacheId()
            encryption = if (declareEncryption) ENCRYPTION_AES256GCM else ENCRYPTION_NONE
        } else {
            assembled = compressed
            encryption = ENCRYPTION_NONE
            cacheId = contentId(rawContent)
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
            encryption      = encryption,
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

    /**
     * Raw CBOR sequence (envelope + payload) for a Single payload — useful for on-device
     * inspection. If [payload] carries an [TagDropPayload.Single.overrideBlob], it's
     * appended as raw trailing bytes after the 3-item sequence, not as a 4th CBOR item
     * (SPEC §9).
     */
    fun singleCbor(payload: TagDropPayload.Single): ByteArray {
        // A key-only code (SPEC §9) omits content/mime_type entirely rather than encoding them empty.
        val keyOnly = payload.keyMaterial != null && payload.content.isEmpty() && payload.mimeType.isEmpty()
        val seq = envelope(TYPE_SINGLE, listOf(
            K_CACHE_ID    to payload.cacheId,
            K_HINT        to payload.hint,
            K_FILENAME    to payload.filename,
            K_MIME        to payload.mimeType.takeIf { !keyOnly },
            K_COMPRESSION to payload.compression.takeIf { it != COMPRESSION_NONE },
            K_CONTENT     to payload.content.takeIf { !keyOnly },
            K_ENCRYPTION  to payload.encryption.takeIf { it != ENCRYPTION_NONE },
            K_COLLECTION_ID    to payload.collectionId,
            K_COLLECTION_LABEL to payload.collectionLabel,
            K_COLLECTION_TAG   to payload.collectionTag,
            K_ICON             to payload.icon,
            K_KEY_MATERIAL to payload.keyMaterial,
            K_RETAIN_KEY   to false.takeIf { payload.keyMaterial != null && !payload.retainKey }
        ))
        return payload.overrideBlob?.let { seq + it } ?: seq
    }

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
            K_ENCRYPTION  to payload.encryption.takeIf { it != ENCRYPTION_NONE },
            K_COLLECTION_ID    to payload.collectionId,
            K_COLLECTION_LABEL to payload.collectionLabel,
            K_COLLECTION_TAG   to payload.collectionTag,
            K_ICON             to payload.icon,
            K_KEY_MATERIAL to payload.keyMaterial,
            K_RETAIN_KEY   to false.takeIf { payload.keyMaterial != null && !payload.retainKey }
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
                    K_LNG      to r.lng,
                    K_KEY_MATERIAL to r.keyMaterial,
                    K_RETAIN_KEY   to false.takeIf { r.keyMaterial != null && !r.retainKey }
                ))
            },
            K_COLLECTION_ID    to payload.collectionId,
            K_COLLECTION_LABEL to payload.collectionLabel,
            K_COLLECTION_TAG   to payload.collectionTag,
            K_ICON             to payload.icon,
            K_KEY_MATERIAL to payload.keyMaterial,
            K_RETAIN_KEY   to false.takeIf { payload.keyMaterial != null && !payload.retainKey }
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
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true
    ): TagDropPayload.PaperManifest {
        val draft = TagDropPayload.PaperManifest(
            rootHash = ByteArray(8), label = label, set = set, slug = slug,
            files = files, related = related,
            collectionId = collectionId, collectionLabel = collectionLabel,
            collectionTag = collectionTag, icon = icon,
            keyMaterial = keyMaterial, retainKey = retainKey
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
            val (type, payload, trailing) = decodeEnvelope(Base45.decode(rest)) ?: return@runCatching null
            when (type) {
                TYPE_SINGLE         -> decodeSingle(payload, trailing)
                TYPE_MANIFEST       -> decodeManifest(payload)
                TYPE_CHUNK          -> decodeChunk(payload)
                TYPE_PAPER_MANIFEST -> decodePaperManifest(payload)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Splits a CBOR sequence (RFC 8742) into (type, payload map, trailing bytes), per the
     * version/type envelope in SPEC §2. Trailing bytes after the 3-item sequence are a
     * candidate hidden override-map blob for a Single (SPEC §9) — decoders MUST NOT treat
     * them as an error. Returns null for an unsupported version or malformed envelope.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeEnvelope(bytes: ByteArray): Triple<Int, Map<Int, Any>, ByteArray>? {
        val (items, trailing) = MiniCbor.decodeSequencePrefix(bytes, 3)
        val version = items[0] as? Long ?: return null
        if (version != VERSION) return null
        val type = items[1] as? Long ?: return null
        val payload = items[2] as? Map<Int, Any> ?: return null
        return Triple(type.toInt(), payload, trailing)
    }

    /** Decode a PaperManifest from its raw CBOR sequence bytes (e.g. stored in ScannedPaper.cborBytes). */
    fun decodePaperManifestCbor(cbor: ByteArray): TagDropPayload.PaperManifest? =
        runCatching {
            val (type, payload, _) = decodeEnvelope(cbor) ?: return@runCatching null
            if (type != TYPE_PAPER_MANIFEST) return@runCatching null
            decodePaperManifest(payload)
        }.getOrNull()

    /** [trailing] is this Single's raw bytes after its 3-item CBOR sequence — see [TagDropPayload.Single.overrideBlob]. */
    private fun decodeSingle(m: Map<Int, Any>, trailing: ByteArray) = TagDropPayload.Single(
        cacheId         = m.bytes(K_CACHE_ID),
        hint            = m.text(K_HINT),
        filename        = m.text(K_FILENAME),
        mimeType        = m.text(K_MIME) ?: "",
        compression     = m.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
        content         = m.bytesOrNull(K_CONTENT) ?: ByteArray(0),
        overrideBlob    = trailing.takeIf { it.size >= OVERRIDE_BLOB_MIN_BYTES },
        encryption      = m.uint(K_ENCRYPTION)?.toInt() ?: ENCRYPTION_NONE,
        keyMaterial     = m.bytesOrNull(K_KEY_MATERIAL),
        retainKey       = m.boolOrNull(K_RETAIN_KEY) ?: true,
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
        encryption      = m.uint(K_ENCRYPTION)?.toInt() ?: ENCRYPTION_NONE,
        keyMaterial     = m.bytesOrNull(K_KEY_MATERIAL),
        retainKey       = m.boolOrNull(K_RETAIN_KEY) ?: true,
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
                hint        = em.text(K_HINT) ?: return@mapNotNull null,
                set         = em.text(K_SET),
                slug        = em.text(K_SLUG),
                paperId     = em[K_PAPER_ID] as? ByteArray,
                lat         = em.doubleOrNull(K_LAT),
                lng         = em.doubleOrNull(K_LNG),
                keyMaterial = em.bytesOrNull(K_KEY_MATERIAL),
                retainKey   = em.boolOrNull(K_RETAIN_KEY) ?: true
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
            icon            = m.text(K_ICON),
            keyMaterial     = m.bytesOrNull(K_KEY_MATERIAL),
            retainKey       = m.boolOrNull(K_RETAIN_KEY) ?: true
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

    private fun Map<Int, Any>.boolOrNull(key: Int): Boolean? = get(key) as? Boolean

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
        K_LAT to "lat", K_LNG to "lng",
        K_ENCRYPTION to "encryption",
        K_KEY_MATERIAL to "key_material", K_RETAIN_KEY to "retain_key"
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
            val (items, trailing) = MiniCbor.decodeSequencePrefix(cbor, 3)
            val version = items[0] as Long
            val type = items[1] as Long
            @Suppress("UNCHECKED_CAST")
            val payload = items[2] as Map<Int, Any>
            Triple(version, type, payload) to trailing
        }.onSuccess { (envelope, trailing) ->
            val (version, type, payload) = envelope
            appendLine("version: $version")
            appendLine("type: $type (${TYPE_NAMES[type.toInt()] ?: "unknown"})")
            appendLine()
            describeMap(payload, 0, this)
            if (trailing.isNotEmpty()) {
                appendLine()
                val note = if (trailing.size >= OVERRIDE_BLOB_MIN_BYTES) {
                    "candidate encrypted override map, SPEC §9"
                } else {
                    "too short to be an override map, SPEC §9"
                }
                appendLine("trailing bytes: ${trailing.toHexDump()} (${trailing.size} bytes, $note)")
            }
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
