package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encodes and decodes TagDrop codes — the wire-format codec (SPEC §2–§5, §9).
 *
 * Encoding URI scheme:  tagdrop:<base41-cbor-sequence>
 *   <base41-cbor-sequence> = Base41( CBOR(version) || CBOR(type) || CBOR(part_meta) || CBOR(sector_bytes) )
 *   version = uint, currently 1
 *   type    = uint: 0 = Content, 1 = Paper
 *
 * Every code — standalone or one of several pieces — is a [Sector]: a four-item CBOR
 * Sequence (RFC 8742) carrying its slice of a payload's **reassembled stream** in
 * `sector_bytes`, addressed by `part_meta` (§4.1). [decode]/[decodeRaw] return a
 * [TagDropScan]; feed each [Sector] to [SectorAssembler] to reassemble and parse the
 * payload it belongs to (§5).
 *
 * Reassembled stream (concatenated `sector_bytes` of sectors 0..sector_count-1, §4.2):
 *   CBOR(core_meta_item) || CBOR(bulky_meta_item) || content
 *   - core_meta_item: always plain/small — identity/preview fields + declarations
 *   - bulky_meta_item: directories / large fields; may be compressed (key 45/46)
 *   - content: raw cache bytes (Content), empty (Paper), or a hidden override map (§9)
 *
 * Navigation links (NOT encoding URIs, NOT put in QR codes):
 *   tagdrop://<rootHash-hex>/<slug>
 *   Disambiguated by "//": Base41's alphabet has no '/' at all, so an encoding
 *   URI can never have "//" right after the scheme.
 *
 * CBOR map integer keys — see SPEC §3 for the full table and where each lives:
 *   part_meta:        2 cache_id/root_hash, 7 total_bytes, 42 sector_index,
 *                     43 sector_count, 44 parity_scheme
 *   core_meta_item:   3 hint/label, 4 mime_type, 8 content_sha256, 11 filename,
 *                     12 content_compression, 13 set, 14 slug, 17/18/19 collection_*,
 *                     24 icon, 28 encryption, 30 key_material, 31 retain_key,
 *                     37/38/39 kdf_*, 40 description, 45 bulky_meta_compression,
 *                     46 bulky_meta_compressed_bytes, 47 bulky_meta_sha256, 50 in_reply_to
 *   bulky_meta_item:  15 files, 16 related (Paper); large fixed fields
 *   override map (§9, inside the content slot once decrypted): 3 hint, 4 mime_type,
 *                     5 content, 11 filename
 *   file entry (within key 15):   20 slug, 21 mime_type, 22 file_id, 41 description
 *   related paper (within key 16): 3 hint, 13 set, 14 slug, 23 paper_id, 26 lat,
 *                     27 lng, 30 key_material, 31 retain_key, 48 radius_m
 *   core_meta_item also carries 26 lat / 27 lng (author-declared location of THIS
 *                     Content/Paper, distinct from a related paper's hint location),
 *                     48 radius_m (circle of uncertainty, shared wherever lat/lng
 *                     appears), 49 prefer_declared_location
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    const val ENCRYPTION_NONE      = 0
    const val ENCRYPTION_AES256GCM = 1

    /** parity_scheme = 1: a single full-XOR parity sector recovers one lost data sector (SPEC §5). */
    const val PARITY_XOR = 1

    private const val AES_KEY_BYTES   = 32
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS    = 128
    private const val GCM_TAG_BYTES   = GCM_TAG_BITS / 8

    /** Minimum size of a self-contained `nonce(12) || ciphertext || tag(16)` override-map blob (SPEC §9). */
    const val OVERRIDE_BLOB_MIN_BYTES = GCM_NONCE_BYTES + GCM_TAG_BYTES

    /** Encoded URI length that reliably fits in one QR code (CreateActivity/CreatePaperActivity warn past this). */
    const val MAX_URI_LENGTH = 2000

    /**
     * Max `sector_bytes` per sector so its encoded `tagdrop:` URI stays under [MAX_URI_LENGTH]:
     * ~20 bytes of CBOR/envelope overhead per sector, and Base41 expands 2 bytes to 3 chars.
     * [createContentSectors] splits a larger reassembled stream into this many bytes per sector.
     */
    const val MAX_SECTOR_DATA_BYTES = 1300

    /** NDEF MIME type for a sector's raw CBOR sequence on an NFC tag (SPEC §12/§13) — see [sectorCbor]/[decodeRaw]. */
    const val NFC_MIME_TYPE = "application/vnd.tagdrop"

    private const val SCHEME          = "tagdrop:"
    private const val NAV_LINK_PREFIX = "tagdrop://"

    private const val VERSION = 1L

    const val TYPE_CONTENT = 0
    const val TYPE_PAPER   = 1

    // part_meta (§4.1)
    private const val K_CACHE_ID     = 2   // cache_id / root_hash
    private const val K_TOTAL_BYTES  = 7
    private const val K_SECTOR_INDEX = 42
    private const val K_SECTOR_COUNT = 43
    private const val K_PARITY       = 44

    // core_meta_item / bulky_meta_item / override map
    private const val K_HINT        = 3   // hint / label
    private const val K_MIME        = 4
    private const val K_CONTENT     = 5   // override map only (§9)
    private const val K_CONTENT_SHA = 8   // content_sha256
    private const val K_FILENAME    = 11
    private const val K_COMPRESSION = 12  // content_compression
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
    private const val K_ENCRYPTION  = 28
    // 29 reserved and unused — see SPEC §9
    private const val K_KEY_MATERIAL = 30
    private const val K_RETAIN_KEY   = 31
    private const val K_KDF_ALG      = 37
    private const val K_KDF_SALT     = 38
    private const val K_KDF_ITERS    = 39
    private const val K_PAPER_DESCRIPTION = 40
    private const val K_FILE_DESCRIPTION  = 41
    private const val K_BULKY_COMPRESSION      = 45
    private const val K_BULKY_COMPRESSED_BYTES = 46
    private const val K_BULKY_SHA              = 47
    private const val K_RADIUS_M               = 48  // circle-of-uncertainty radius in meters, wherever lat/lng appears
    private const val K_PREFER_DECLARED_LOCATION = 49  // core_meta_item only — lat/lng wins over live GPS when true
    private const val K_IN_REPLY_TO = 50  // core_meta_item only — cache_id/root_hash of the single parent being replied to (SPEC §7)

    const val KDF_NONE          = 0
    const val KDF_PBKDF2_SHA256 = 1

    private const val DEFAULT_KDF_ITERS = 100000

    // ── Content addressing (IPFS-inspired, SPEC §4.4) ─────────────────────────

    /** `cache_id` = SHA-256(uncompressed content)[0:8] — same bytes, same ID, everywhere. */
    fun contentId(content: ByteArray): ByteArray = sha256(content).copyOf(8)

    /**
     * 8 random bytes — `cache_id` for a Content code carrying a hidden override map (SPEC §9),
     * so the ID itself can't be used as a content-equality oracle against a known plaintext.
     */
    fun randomCacheId(): ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

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
     * value the clear map declares for its own content slot), then AES-256-GCM-encrypted
     * under a fresh nonce.
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

    /**
     * Derives a 32-byte AES-256 key from [passphrase] using PBKDF2-SHA256 with the given
     * [salt] (16 bytes) and [iterations] count (SPEC §9). The resulting key can be used
     * with [tryDecryptOverrideMap] just like a [generateKeyMaterial]-produced random key.
     */
    fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /**
     * Builds the sector(s) of a Content payload (`type` 0). The reassembled stream is
     * `CBOR(core_meta_item) || CBOR(bulky_meta_item) || content`, split into sectors of at
     * most [maxSectorDataBytes] each — one sector for content that fits, more for larger
     * payloads (SPEC §4.2, §5). `content_sha256` (key 8) is added whenever more than one
     * sector is needed (required for `sector_count > 1`, SPEC §3) — without it, a decoder
     * has no way to detect a substituted/forged sector during reassembly.
     *
     * [hint]/[filename]/[mimeType]/[rawContent] become the **clear** view — shown until a
     * hidden override map, if any, is unlocked (SPEC §9). They may be a cover story, a
     * decoy, or genuine unremarkable content.
     *
     * If [override] is given (with [encryptionKey], 32 bytes — see [generateKeyMaterial]),
     * the content slot IS the AES-256-GCM-encrypted override blob (SPEC §9) rather than
     * [rawContent]; `cacheId` becomes random (see [randomCacheId]), and `encryption` is set
     * to the AES-256-GCM hint unless [declareEncryption] is false (the hint is cosmetic).
     *
     * [lat]/[lng] are the author's own declared coordinates for this content's physical
     * location (distinct from a [TagDropPayload.RelatedPaper]'s hint about a *different*
     * paper) — useful when the author knows where they're placing this code but the finder's
     * device may lack a GPS lock. [radiusM] is an optional circle-of-uncertainty radius in
     * meters. [preferDeclaredLocation] defaults to false (live GPS wins when available,
     * declared location is a fallback); set true to make the declared location win even over
     * an available live GPS fix.
     *
     * [inReplyTo] is the `cache_id`/`root_hash` of a single parent this content is replying to
     * (SPEC §7, "Replies and threading") — omit for a new, unprompted message.
     */
    fun createContentSectors(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true,
        override: TagDropPayload.OverrideMap? = null, encryptionKey: ByteArray? = null,
        declareEncryption: Boolean = true,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null,
        maxSectorDataBytes: Int = Int.MAX_VALUE
    ): List<Sector> {
        val (compressedContent, compression) =
            if (compress) compress(rawContent) to COMPRESSION_DEFLATE else rawContent to COMPRESSION_NONE

        val cacheId: ByteArray?
        val contentSlot: ByteArray
        val encryption: Int
        if (override != null) {
            requireNotNull(encryptionKey) { "encryptionKey is required when override is provided" }
            contentSlot = encryptOverrideMap(override, encryptionKey, compression)
            cacheId = randomCacheId()
            encryption = if (declareEncryption) ENCRYPTION_AES256GCM else ENCRYPTION_NONE
        } else {
            contentSlot = compressedContent
            cacheId = contentId(rawContent)
            encryption = ENCRYPTION_NONE
        }

        fun core(withSha: Boolean) = listOf(
            K_HINT         to hint,
            K_FILENAME     to filename,
            K_MIME         to mimeType,
            K_COMPRESSION  to compression.takeIf { it != COMPRESSION_NONE },
            K_CONTENT_SHA  to (sha256(contentSlot).takeIf { withSha }),
            K_ENCRYPTION   to encryption.takeIf { it != ENCRYPTION_NONE },
            K_COLLECTION_ID    to collectionId,
            K_COLLECTION_LABEL to collectionLabel,
            K_COLLECTION_TAG   to collectionTag,
            K_ICON             to icon,
            K_KEY_MATERIAL to keyMaterial,
            K_RETAIN_KEY   to false.takeIf { keyMaterial != null && !retainKey },
            K_LAT     to lat,
            K_LNG     to lng,
            K_RADIUS_M to radiusM,
            K_PREFER_DECLARED_LOCATION to true.takeIf { preferDeclaredLocation },
            K_IN_REPLY_TO to inReplyTo
        )

        // Single-sector when the stream fits; otherwise re-build with content_sha256 added.
        val single = buildStream(core(withSha = false), emptyList(), contentSlot)
        val stream = if (single.size <= maxSectorDataBytes) single
                     else buildStream(core(withSha = true), emptyList(), contentSlot)
        return sectorize(TYPE_CONTENT, cacheId, stream, maxSectorDataBytes)
    }

    /**
     * Two-pass auto-sizing wrapper around [createContentSectors] (mirrors the web generator's
     * createContentSectorsAutoSized): builds with an unbounded sector size first; if the single
     * resulting sector's `tagdrop:` URI fits under [MAX_URI_LENGTH], uses it as-is; otherwise
     * rebuilds the whole payload with [MAX_SECTOR_DATA_BYTES] forced, producing several uniform
     * sectors.
     */
    fun createContentSectorsAutoSized(
        hint: String?, filename: String?, mimeType: String,
        rawContent: ByteArray, compress: Boolean = false,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true,
        override: TagDropPayload.OverrideMap? = null, encryptionKey: ByteArray? = null,
        declareEncryption: Boolean = true,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null
    ): List<Sector> {
        val first = createContentSectors(
            hint, filename, mimeType, rawContent, compress,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey, override, encryptionKey, declareEncryption,
            lat, lng, radiusM, preferDeclaredLocation, inReplyTo,
            maxSectorDataBytes = Int.MAX_VALUE
        )
        if (encode(first.first()).length <= MAX_URI_LENGTH) return first
        return createContentSectors(
            hint, filename, mimeType, rawContent, compress,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey, override, encryptionKey, declareEncryption,
            lat, lng, radiusM, preferDeclaredLocation, inReplyTo,
            maxSectorDataBytes = MAX_SECTOR_DATA_BYTES
        )
    }

    /**
     * Builds a single "key-only" Content sector (SPEC §9): carries [keyMaterial] for other
     * content, with no content/mime_type of its own and — referencing no content to address —
     * no `cache_id` in its `part_meta` either.
     */
    fun createKeyCodeSector(keyMaterial: ByteArray, retainKey: Boolean = true, hint: String? = null): Sector {
        require(keyMaterial.size == AES_KEY_BYTES) { "key_material must be $AES_KEY_BYTES bytes" }
        val core = listOf(
            K_HINT         to hint,
            K_KEY_MATERIAL to keyMaterial,
            K_RETAIN_KEY   to false.takeIf { !retainKey }
        )
        val stream = buildStream(core, emptyList(), ByteArray(0))
        return sectorize(TYPE_CONTENT, cacheId = null, stream, Int.MAX_VALUE).single()
    }

    /**
     * Builds a Paper payload (`type` 1) and its sector(s). `root_hash` is content-addressed
     * over the reassembled stream (SPEC §4.4) — `content` is always empty for a Paper, and
     * because `root_hash` lives in `part_meta`, *outside* the bytes it's computed over, there's
     * no placeholder/re-encode pass: build the stream once, hash it, copy the hash into every
     * sector. The app keeps `bulky_meta_item` (files/related) uncompressed, so the stream is
     * exactly the bytes the root hash covers.
     */
    fun createPaper(
        label: String?, set: String?, slug: String?,
        files: List<TagDropPayload.FileEntry>, related: List<TagDropPayload.RelatedPaper> = emptyList(),
        description: String? = null,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null,
        maxSectorDataBytes: Int = Int.MAX_VALUE
    ): Pair<TagDropPayload.Paper, List<Sector>> {
        val draft = TagDropPayload.Paper(
            rootHash = ByteArray(8), label = label, set = set, slug = slug,
            files = files, related = related, description = description,
            collectionId = collectionId, collectionLabel = collectionLabel,
            collectionTag = collectionTag, icon = icon,
            keyMaterial = keyMaterial, retainKey = retainKey,
            lat = lat, lng = lng, radiusM = radiusM, preferDeclaredLocation = preferDeclaredLocation,
            inReplyTo = inReplyTo
        )
        val stream = buildPaperStream(draft)
        val rootHash = sha256(stream).copyOf(8)
        val paper = draft.copy(rootHash = rootHash)
        return paper to sectorize(TYPE_PAPER, rootHash, stream, maxSectorDataBytes)
    }

    /** Two-pass auto-sizing wrapper around [createPaper] — see [createContentSectorsAutoSized]. */
    fun createPaperAutoSized(
        label: String?, set: String?, slug: String?,
        files: List<TagDropPayload.FileEntry>, related: List<TagDropPayload.RelatedPaper> = emptyList(),
        description: String? = null,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false,
        inReplyTo: ByteArray? = null
    ): Pair<TagDropPayload.Paper, List<Sector>> {
        val first = createPaper(
            label, set, slug, files, related, description,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey,
            lat, lng, radiusM, preferDeclaredLocation, inReplyTo,
            maxSectorDataBytes = Int.MAX_VALUE
        )
        if (encode(first.second.first()).length <= MAX_URI_LENGTH) return first
        return createPaper(
            label, set, slug, files, related, description,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey,
            lat, lng, radiusM, preferDeclaredLocation, inReplyTo,
            maxSectorDataBytes = MAX_SECTOR_DATA_BYTES
        )
    }

    /** The reassembled-stream bytes for [paper] — what the root hash covers, and what gets stored as `ScannedPaper.cborBytes`. */
    fun paperStreamBytes(paper: TagDropPayload.Paper): ByteArray = buildPaperStream(paper)

    /**
     * `bulky_meta_item` is encoded first so its hash can be included in `core_meta_item` as
     * `bulky_meta_sha256` (key 47, SPEC §3) — required whenever the paper ends up spanning more
     * than one sector, since that's the only integrity check over `files`/`related` (the
     * slug → file_id directory) once a multi-sector paper is reassembled from independently
     * scanned codes. Always including it (rather than only above the sector-count threshold,
     * as [createContentSectors] does for `content_sha256`) keeps this byte-reproducible from
     * [paper] alone, with no hidden state to replay — Paper payloads are directory structures
     * where a constant ~36-byte cost is negligible next to the cost of an unverified directory.
     */
    private fun buildPaperStream(paper: TagDropPayload.Paper): ByteArray {
        val bulky = listOf<Pair<Int, Any?>>(
            K_FILES   to paper.files.map { f ->
                MiniCbor.CborMap(listOf(
                    K_FILE_SLUG to f.slug,
                    K_FILE_MIME to f.mimeType,
                    K_FILE_ID   to f.fileId,
                    K_FILE_DESCRIPTION to f.description
                ))
            },
            K_RELATED to paper.related.map { r ->
                MiniCbor.CborMap(listOf(
                    K_HINT     to r.hint,
                    K_SET      to r.set,
                    K_SLUG     to r.slug,
                    K_PAPER_ID to r.paperId,
                    K_LAT      to r.lat,
                    K_LNG      to r.lng,
                    K_RADIUS_M to r.radiusM,
                    K_KEY_MATERIAL to r.keyMaterial,
                    K_RETAIN_KEY   to false.takeIf { r.keyMaterial != null && !r.retainKey }
                ))
            }
        )
        val bulkyBytes = MiniCbor.encodeMap(bulky)
        val core = listOf(
            K_HINT     to paper.label,
            K_PAPER_DESCRIPTION to paper.description,
            K_SET      to paper.set,
            K_SLUG     to paper.slug,
            K_COLLECTION_ID    to paper.collectionId,
            K_COLLECTION_LABEL to paper.collectionLabel,
            K_COLLECTION_TAG   to paper.collectionTag,
            K_ICON             to paper.icon,
            K_KEY_MATERIAL to paper.keyMaterial,
            K_RETAIN_KEY   to false.takeIf { paper.keyMaterial != null && !paper.retainKey },
            K_LAT      to paper.lat,
            K_LNG      to paper.lng,
            K_RADIUS_M to paper.radiusM,
            K_PREFER_DECLARED_LOCATION to true.takeIf { paper.preferDeclaredLocation },
            K_IN_REPLY_TO to paper.inReplyTo,
            K_BULKY_SHA to sha256(bulkyBytes)
        )
        val out = ByteArrayOutputStream()
        out.write(MiniCbor.encodeMap(core))
        out.write(bulkyBytes)
        return out.toByteArray()
    }

    /** Concatenates `CBOR(core_meta_item) || CBOR(bulky_meta_item) || content` (SPEC §4.2). */
    private fun buildStream(
        corePairs: List<Pair<Int, Any?>>, bulkyPairs: List<Pair<Int, Any?>>, content: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MiniCbor.encodeMap(corePairs))
        out.write(MiniCbor.encodeMap(bulkyPairs))
        out.write(content)
        return out.toByteArray()
    }

    /**
     * Slices a reassembled [stream] into data sectors of at most [maxSectorDataBytes] bytes,
     * each stamped with [cacheId] (null for a key-only code) and its `sector_index` /
     * `sector_count` / `total_bytes` in `part_meta` (SPEC §4.1). Every sector but the last
     * is the same length.
     */
    private fun sectorize(type: Int, cacheId: ByteArray?, stream: ByteArray, maxSectorDataBytes: Int): List<Sector> {
        val total = stream.size
        val count =
            if (maxSectorDataBytes <= 0 || total <= maxSectorDataBytes) 1
            else (total + maxSectorDataBytes - 1) / maxSectorDataBytes
        val sectorSize = (total + count - 1) / count
        return (0 until count).map { i ->
            val start = minOf(i * sectorSize, total)
            val end = minOf(start + sectorSize, total)
            Sector(type, PartMeta(cacheId, i, count, total), stream.copyOfRange(start, end))
        }
    }

    /**
     * Builds the single full-XOR parity sector (`parity_scheme` 1, SPEC §5) for [dataSectors] —
     * the byte-wise XOR of every data sector's `sector_bytes`, each zero-padded to the longest
     * before XOR-ing. Placed at `sector_index == sector_count`, it recovers any one lost data
     * sector. [dataSectors] must be a complete `0..sector_count-1` run of one payload.
     */
    fun paritySector(dataSectors: List<Sector>): Sector {
        require(dataSectors.isNotEmpty()) { "need at least one data sector for parity" }
        val first = dataSectors.first().partMeta
        val width = dataSectors.maxOf { it.sectorBytes.size }
        val parity = ByteArray(width)
        for (sector in dataSectors) {
            val b = sector.sectorBytes
            for (j in b.indices) parity[j] = (parity[j].toInt() xor b[j].toInt()).toByte()
        }
        return Sector(
            dataSectors.first().type,
            PartMeta(first.cacheId, first.sectorCount, first.sectorCount, first.totalBytes, PARITY_XOR),
            parity
        )
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    /** A [Sector]'s `tagdrop:` encoding URI: `tagdrop:` + Base41 of its four-item CBOR sequence. */
    fun encode(sector: Sector): String = SCHEME + Base41.encode(sectorCbor(sector))

    /** A [Sector]'s raw four-item CBOR sequence (SPEC §2) — Base41-encoded for `tagdrop:`, or stored raw on byte carriers (§13). */
    fun sectorCbor(sector: Sector): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MiniCbor.encodeUInt(VERSION))
        out.write(MiniCbor.encodeUInt(sector.type.toLong()))
        out.write(MiniCbor.encodeMap(partMetaPairs(sector.partMeta)))
        out.write(MiniCbor.encodeBytes(sector.sectorBytes))
        return out.toByteArray()
    }

    private fun partMetaPairs(pm: PartMeta): List<Pair<Int, Any?>> = listOf(
        K_CACHE_ID     to pm.cacheId,
        K_TOTAL_BYTES  to pm.totalBytes,
        K_SECTOR_INDEX to pm.sectorIndex,
        K_SECTOR_COUNT to pm.sectorCount,
        K_PARITY       to pm.paritySchemeRaw
    )

    /**
     * The four-item CBOR sequence for a single-sector Content payload reconstructed from a
     * cached page's resolved fields — used by the on-device "Inspect CBOR" diagnostic only.
     */
    fun inspectableContentCbor(
        hint: String?, filename: String?, mimeType: String, content: ByteArray,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null
    ): ByteArray = sectorCbor(
        createContentSectors(
            hint, filename, mimeType, content, compress = false,
            collectionId, collectionLabel, collectionTag, icon
        ).first()
    )

    // ── Decoding ──────────────────────────────────────────────────────────────

    /**
     * Decodes one scanned string into a [TagDropScan]: a `tagdrop:` encoding URI becomes a
     * [TagDropScan.SectorScan]; a raw `data:` URI becomes a [TagDropScan.LegacyScan] (§11).
     * Navigation links (`tagdrop://`, §2) and anything else return null.
     */
    fun decode(scanned: String): TagDropScan? {
        if (scanned.startsWith("data:")) return TagDropScan.LegacyScan(TagDropPayload.Legacy(scanned))
        if (!scanned.startsWith(SCHEME) || scanned.startsWith(NAV_LINK_PREFIX)) return null
        val bytes = runCatching { Base41.decode(scanned.removePrefix(SCHEME)) }.getOrNull() ?: return null
        return decodeRaw(bytes)
    }

    /**
     * Decodes a [Sector] straight from its raw CBOR sequence, with no `tagdrop:`/Base41 text
     * wrapper — the carrier already supports raw bytes (SPEC §13: NFC NDEF, a byte-mode 2D
     * barcode segment, etc.). Returns null for an unsupported version or malformed envelope.
     */
    fun decodeRaw(bytes: ByteArray): TagDropScan? =
        decodeSector(bytes)?.let { TagDropScan.SectorScan(it) }

    /**
     * Parses the four-item envelope (`version`/`type`/`part_meta`/`sector_bytes`, SPEC §2)
     * into a [Sector]. Trailing bytes after the four items are tolerated, never an error — a
     * stacked independent envelope for a separate hidden layer (SPEC §9) — and ignored here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeSector(bytes: ByteArray): Sector? = runCatching {
        val (items, _) = MiniCbor.decodeSequencePrefix(bytes, 4)
        val version = items[0] as? Long ?: return@runCatching null
        if (version != VERSION) return@runCatching null
        val type = (items[1] as? Long ?: return@runCatching null).toInt()
        val pm = items[2] as? Map<Int, Any> ?: return@runCatching null
        val sectorBytes = items[3] as? ByteArray ?: return@runCatching null
        Sector(
            type = type,
            partMeta = PartMeta(
                cacheId      = pm.bytesOrNull(K_CACHE_ID),
                sectorIndex  = pm.uint(K_SECTOR_INDEX)?.toInt() ?: 0,
                sectorCount  = pm.uint(K_SECTOR_COUNT)?.toInt() ?: 1,
                totalBytes   = pm.uint(K_TOTAL_BYTES)?.toInt() ?: sectorBytes.size,
                paritySchemeRaw = pm.uint(K_PARITY)?.toInt()
            ),
            sectorBytes = sectorBytes
        )
    }.getOrNull()

    // ── Reassembled-stream parsing (SPEC §4.2, §5) ─────────────────────────────

    /**
     * Structural split of a reassembled stream into its three items (SPEC §4.2).
     * [bulkyRawBytes] is `bulky_meta_item` exactly as transmitted (compressed bytes if
     * `bulky_meta_compression` was set, else its raw CBOR encoding) — what
     * `bulky_meta_sha256` (key 47) is computed over (SPEC §3, §5 step 4).
     */
    data class StreamParts(
        val core: Map<Int, Any>,
        val bulky: Map<Int, Any>,
        val bulkyRawBytes: ByteArray,
        val content: ByteArray
    )

    /**
     * Splits a reassembled [stream] into `core_meta_item`, `bulky_meta_item`, and `content`
     * (SPEC §4.2). `bulky_meta_item` is read per `core`'s `bulky_meta_compression` declaration:
     * if absent, CBOR's self-delimiting structure marks its end; if present, exactly
     * `bulky_meta_compressed_bytes` are read and decompressed (SPEC §3, §5 step 4). Returns
     * null if the stream isn't two CBOR maps followed by content.
     */
    @Suppress("UNCHECKED_CAST")
    fun splitReassembledStream(stream: ByteArray): StreamParts? = runCatching {
        val (coreItems, afterCore) = MiniCbor.decodeSequencePrefix(stream, 1)
        val core = coreItems[0] as? Map<Int, Any> ?: return@runCatching null
        val bulkyCompression = core.uint(K_BULKY_COMPRESSION)?.toInt() ?: COMPRESSION_NONE
        if (bulkyCompression != COMPRESSION_NONE) {
            val n = core.uint(K_BULKY_COMPRESSED_BYTES)?.toInt() ?: return@runCatching null
            if (n > afterCore.size) return@runCatching null
            val bulkyRaw = afterCore.copyOfRange(0, n)
            val bulky = MiniCbor.decodeMap(decompress(bulkyRaw))
            StreamParts(core, bulky, bulkyRaw, afterCore.copyOfRange(n, afterCore.size))
        } else {
            val (bulkyItems, afterBulky) = MiniCbor.decodeSequencePrefix(afterCore, 1)
            val bulky = bulkyItems[0] as? Map<Int, Any> ?: return@runCatching null
            val bulkyRaw = afterCore.copyOfRange(0, afterCore.size - afterBulky.size)
            StreamParts(core, bulky, bulkyRaw, afterBulky)
        }
    }.getOrNull()

    /** Outcome of parsing a Content payload's reassembled stream (SPEC §5 steps 3–5). */
    sealed class ContentParse {
        /** Parsed and (if `content_sha256` was present) integrity-verified. */
        data class Ok(val content: TagDropPayload.Content) : ContentParse()
        /** `content_sha256` was present and did not match — incomplete or corrupt assembly. */
        object HashMismatch : ContentParse()
        /** The stream isn't a well-formed Content reassembled stream. */
        object Malformed : ContentParse()
    }

    /**
     * Parses a reassembled Content stream into a [TagDropPayload.Content] (SPEC §4.2, §5).
     * Verifies `content_sha256` over the content slot **as transmitted** (key 8, SPEC §3) —
     * that's the transmission-integrity check, independent of any later override decryption
     * (§9). Required whenever [partMeta] reports more than one sector — without it a decoder
     * can't detect a substituted/forged sector during reassembly — and otherwise checked only
     * if present. [partMeta]'s `cache_id` becomes the Content's id (null for a key-only code).
     * Cover/override resolution is the caller's job (see [SectorAssembler]).
     */
    fun parseContentStream(stream: ByteArray, partMeta: PartMeta): ContentParse {
        val parts = splitReassembledStream(stream) ?: return ContentParse.Malformed
        val declaredSha = parts.core.bytesOrNull(K_CONTENT_SHA)
        if (declaredSha == null) {
            if (partMeta.sectorCount > 1) return ContentParse.Malformed
        } else if (!sha256(parts.content).contentEquals(declaredSha)) {
            return ContentParse.HashMismatch
        }
        val core = parts.core
        val slot = parts.content
        return ContentParse.Ok(
            TagDropPayload.Content(
                cacheId         = partMeta.cacheId,
                hint            = core.text(K_HINT),
                filename        = core.text(K_FILENAME),
                mimeType        = core.text(K_MIME) ?: "",
                compression     = core.uint(K_COMPRESSION)?.toInt() ?: COMPRESSION_NONE,
                content         = slot,
                overrideBlob    = slot.takeIf { it.size >= OVERRIDE_BLOB_MIN_BYTES },
                encryption      = core.uint(K_ENCRYPTION)?.toInt() ?: ENCRYPTION_NONE,
                keyMaterial     = core.bytesOrNull(K_KEY_MATERIAL),
                retainKey       = core.boolOrNull(K_RETAIN_KEY) ?: true,
                collectionId    = core.bytesOrNull(K_COLLECTION_ID),
                collectionLabel = core.text(K_COLLECTION_LABEL),
                collectionTag   = core.text(K_COLLECTION_TAG),
                icon            = core.text(K_ICON),
                kdfAlg          = core.uint(K_KDF_ALG)?.toInt() ?: KDF_NONE,
                kdfSalt         = core.bytesOrNull(K_KDF_SALT),
                kdfIters        = core.uint(K_KDF_ITERS)?.toInt() ?: DEFAULT_KDF_ITERS,
                lat             = core.doubleOrNull(K_LAT),
                lng             = core.doubleOrNull(K_LNG),
                radiusM         = core.doubleOrNull(K_RADIUS_M),
                preferDeclaredLocation = core.boolOrNull(K_PREFER_DECLARED_LOCATION) ?: false,
                inReplyTo       = core.bytesOrNull(K_IN_REPLY_TO)
            )
        )
    }

    /**
     * Parses a reassembled Paper stream into a [TagDropPayload.Paper] (SPEC §4.2, §4.3), using
     * [partMeta]'s `root_hash` as the paper's address. Verifies `bulky_meta_sha256` (key 47,
     * SPEC §3, §5 step 4) — required whenever [partMeta] reports more than one sector, since
     * that's the only integrity check over the `files`/`related` directory once a multi-sector
     * paper is reassembled from independently scanned codes. Also verifies a declared
     * `partMeta.cacheId` against the recomputed root hash (SPEC §4.4) — `root_hash` is this
     * paper's permanent, content-addressed storage key (`ScannedPaper.rootHash`, replace-on-
     * conflict), so trusting an attacker-declared value verbatim would let a forged sector
     * silently overwrite an unrelated, previously-scanned paper's stored directory. Returns null
     * if malformed, or if a required/declared hash doesn't verify.
     */
    fun parsePaperStream(stream: ByteArray, partMeta: PartMeta): TagDropPayload.Paper? {
        val parts = splitReassembledStream(stream) ?: return null
        if (!verifyBulkyMetaSha(parts, partMeta.sectorCount)) return null
        val computedHash = sha256(stream).copyOf(8)
        val declaredHash = partMeta.cacheId
        if (declaredHash != null && !declaredHash.contentEquals(computedHash)) return null
        return paperFromParts(parts, computedHash)
    }

    /**
     * Verifies [parts]' `bulky_meta_sha256` (key 47, SPEC §3, §5 step 4) against its transmitted
     * `bulky_meta_item` bytes. Required when [sectorCount] > 1 (absence fails verification);
     * checked only if present otherwise.
     */
    private fun verifyBulkyMetaSha(parts: StreamParts, sectorCount: Int): Boolean {
        val declared = parts.core.bytesOrNull(K_BULKY_SHA) ?: return sectorCount <= 1
        return sha256(parts.bulkyRawBytes).contentEquals(declared)
    }

    /**
     * Decodes a stored Paper reassembled stream (e.g. `ScannedPaper.cborBytes`) back into a
     * [TagDropPayload.Paper], recomputing `root_hash` from the bytes (SPEC §4.4). Used to
     * re-read a scanned paper's directory for navigation/display.
     */
    fun decodePaperStream(stream: ByteArray): TagDropPayload.Paper? {
        val parts = splitReassembledStream(stream) ?: return null
        return paperFromParts(parts, sha256(stream).copyOf(8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun paperFromParts(parts: StreamParts, rootHash: ByteArray): TagDropPayload.Paper {
        val files = (parts.bulky[K_FILES] as? List<*>)?.mapNotNull { entry ->
            val em = entry as? Map<Int, Any> ?: return@mapNotNull null
            TagDropPayload.FileEntry(
                slug        = em.text(K_FILE_SLUG) ?: return@mapNotNull null,
                mimeType    = em.text(K_FILE_MIME) ?: return@mapNotNull null,
                fileId      = em.bytesOrNull(K_FILE_ID) ?: return@mapNotNull null,
                description = em.text(K_FILE_DESCRIPTION)
            )
        } ?: emptyList()

        val related = (parts.bulky[K_RELATED] as? List<*>)?.mapNotNull { entry ->
            val em = entry as? Map<Int, Any> ?: return@mapNotNull null
            TagDropPayload.RelatedPaper(
                hint        = em.text(K_HINT) ?: return@mapNotNull null,
                set         = em.text(K_SET),
                slug        = em.text(K_SLUG),
                paperId     = em.bytesOrNull(K_PAPER_ID),
                lat         = em.doubleOrNull(K_LAT),
                lng         = em.doubleOrNull(K_LNG),
                radiusM     = em.doubleOrNull(K_RADIUS_M),
                keyMaterial = em.bytesOrNull(K_KEY_MATERIAL),
                retainKey   = em.boolOrNull(K_RETAIN_KEY) ?: true
            )
        } ?: emptyList()

        return TagDropPayload.Paper(
            rootHash        = rootHash,
            label           = parts.core.text(K_HINT),
            set             = parts.core.text(K_SET),
            slug            = parts.core.text(K_SLUG),
            files           = files,
            related         = related,
            description     = parts.core.text(K_PAPER_DESCRIPTION),
            collectionId    = parts.core.bytesOrNull(K_COLLECTION_ID),
            collectionLabel = parts.core.text(K_COLLECTION_LABEL),
            collectionTag   = parts.core.text(K_COLLECTION_TAG),
            icon            = parts.core.text(K_ICON),
            keyMaterial     = parts.core.bytesOrNull(K_KEY_MATERIAL),
            retainKey       = parts.core.boolOrNull(K_RETAIN_KEY) ?: true,
            lat             = parts.core.doubleOrNull(K_LAT),
            lng             = parts.core.doubleOrNull(K_LNG),
            radiusM         = parts.core.doubleOrNull(K_RADIUS_M),
            preferDeclaredLocation = parts.core.boolOrNull(K_PREFER_DECLARED_LOCATION) ?: false,
            inReplyTo       = parts.core.bytesOrNull(K_IN_REPLY_TO)
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

    private fun Map<Int, Any>.bytesOrNull(key: Int): ByteArray? = get(key) as? ByteArray
    private fun Map<Int, Any>.text(key: Int): String? = get(key) as? String
    private fun Map<Int, Any>.uint(key: Int): Long? = get(key) as? Long
    private fun Map<Int, Any>.doubleOrNull(key: Int): Double? = get(key) as? Double
    private fun Map<Int, Any>.boolOrNull(key: Int): Boolean? = get(key) as? Boolean

    // ── Debug ─────────────────────────────────────────────────────────────────

    /** Human-readable names for TagDrop's CBOR integer keys, used by [describeCbor]. */
    private val KEY_NAMES = mapOf(
        K_CACHE_ID to "cache_id/root_hash", K_HINT to "hint/label", K_MIME to "mime_type",
        K_CONTENT to "content", K_TOTAL_BYTES to "total_bytes", K_CONTENT_SHA to "content_sha256",
        K_FILENAME to "filename", K_COMPRESSION to "content_compression", K_SET to "set",
        K_SLUG to "slug", K_FILES to "files", K_RELATED to "related",
        K_COLLECTION_ID to "collection_id", K_COLLECTION_LABEL to "collection_label",
        K_COLLECTION_TAG to "collection_tag", K_FILE_SLUG to "slug", K_FILE_MIME to "mime_type",
        K_FILE_ID to "file_id", K_PAPER_ID to "paper_id", K_ICON to "icon",
        K_LAT to "lat", K_LNG to "lng", K_ENCRYPTION to "encryption",
        K_KEY_MATERIAL to "key_material", K_RETAIN_KEY to "retain_key",
        K_KDF_ALG to "kdf_alg", K_KDF_SALT to "kdf_salt", K_KDF_ITERS to "kdf_iters",
        K_PAPER_DESCRIPTION to "description", K_FILE_DESCRIPTION to "description",
        K_SECTOR_INDEX to "sector_index", K_SECTOR_COUNT to "sector_count", K_PARITY to "parity_scheme",
        K_BULKY_COMPRESSION to "bulky_meta_compression",
        K_BULKY_COMPRESSED_BYTES to "bulky_meta_compressed_bytes", K_BULKY_SHA to "bulky_meta_sha256",
        K_RADIUS_M to "radius_m", K_PREFER_DECLARED_LOCATION to "prefer_declared_location",
        K_IN_REPLY_TO to "in_reply_to"
    )

    private val TYPE_NAMES = mapOf(TYPE_CONTENT to "Content", TYPE_PAPER to "Paper")

    /**
     * Pretty-prints a raw TagDrop sector CBOR sequence for the on-device debug view: a hex
     * dump, the four-item envelope (SPEC §2) with `part_meta` broken out by field name, and —
     * for a complete single-sector payload — the `core_meta_item`/`bulky_meta_item`/content
     * split of its reassembled stream (§4.2).
     */
    @Suppress("UNCHECKED_CAST")
    fun describeCbor(cbor: ByteArray): String = buildString {
        appendLine("${cbor.size} bytes")
        appendLine(cbor.toHexDump())
        appendLine()
        runCatching {
            val (items, trailing) = MiniCbor.decodeSequencePrefix(cbor, 4)
            val version = items[0] as Long
            val type = items[1] as Long
            val partMeta = items[2] as Map<Int, Any>
            val sectorBytes = items[3] as ByteArray
            appendLine("version: $version")
            appendLine("type: $type (${TYPE_NAMES[type.toInt()] ?: "unknown"})")
            appendLine()
            appendLine("part_meta:")
            describeMap(partMeta, 1, this)
            appendLine()
            appendLine("sector_bytes: ${sectorBytes.size} bytes")
            val count = (partMeta[K_SECTOR_COUNT] as? Long)?.toInt() ?: 1
            val index = (partMeta[K_SECTOR_INDEX] as? Long)?.toInt() ?: 0
            if (count == 1 && index == 0) {
                splitReassembledStream(sectorBytes)?.let { parts ->
                    appendLine("  core_meta_item:")
                    describeMap(parts.core, 2, this)
                    appendLine("  bulky_meta_item:")
                    describeMap(parts.bulky, 2, this)
                    appendLine("  content: ${parts.content.size} bytes")
                    if (parts.content.isNotEmpty()) appendLine("    ${parts.content.toHexDump()}")
                } ?: appendLine("  ${sectorBytes.toHexDump()}")
            } else {
                appendLine("  (sector $index of $count — reassemble before parsing)")
                appendLine("  ${sectorBytes.toHexDump()}")
            }
            if (trailing.isNotEmpty()) {
                appendLine()
                appendLine("trailing bytes: ${trailing.toHexDump()} (${trailing.size} bytes — stacked envelope or padding, SPEC §9)")
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
