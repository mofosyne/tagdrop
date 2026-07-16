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
 * Encodes and decodes TagDrop codes — the wire-format codec (SPEC.md §2-§5, §9, §10, v8).
 *
 * Encoding URI scheme:  tagdrop:<base41-cbor-sequence>
 *   <base41-cbor-sequence> = Base41( CBOR Sequence of 1-2 QDEF Records )
 * A code always carries a Preview Record (Content-Preview Type 1, or Paper-Preview Type 5)
 * and, if the payload has a body, either the complete Body Record (Content-Body Type 3, or
 * Paper-Body Type 7 — optionally Compress-wrapped, QDEF Type 8) or one Split-Wrapper-wrapped
 * (QDEF Type 2) Body fragment for a multi-code payload. No magic header, no namespace
 * discriminator on this carrier (or on NFC NDEF) — the `tagdrop:` scheme itself is both the
 * dispatch signal and (implicitly, never transmitted) TagDrop's fixed namespace, the same
 * value declared explicitly on the byte-mode QR carrier this app doesn't implement (SPEC §2.1a).
 *
 * Preview is always plain, unwrapped, and — for a multi-code payload — repeated identically
 * on every code (SPEC §5.1): a decoder scanning any single code already knows the payload's
 * identity and can show a usable preview regardless of scan order or how much of Body has
 * arrived. [decode]/[decodeRaw] return a [TagDropScan]; feed each [ScannedRecord] to
 * [SectorAssembler] to reassemble and parse the payload it belongs to.
 *
 * Navigation links (NOT encoding URIs, NOT put in QR codes):
 *   tagdrop://<domain-or-@rootHash-hex>/<slug>  — see TagDropLinkResolver for the grammar
 *   Disambiguated by "//": Base41's alphabet has no '/' at all, so an encoding
 *   URI can never have "//" right after the scheme.
 *
 * CBOR map integer keys — see SPEC.md §3.1-§3.4 for the authoritative tables. Each Record
 * Type has its own independent key namespace; `files[]`/`related[]` sub-maps (inside
 * Paper-Body) and the encrypted override map (inside Content-Body's `content`) each have
 * their own independent local numbering too.
 */
object TagDropCodec {

    const val COMPRESSION_NONE    = 0
    const val COMPRESSION_DEFLATE = 1

    const val ENCRYPTION_NONE      = 0
    const val ENCRYPTION_AES256GCM = 1

    /** `signature_algorithm` values (SPEC §10). Real ML-DSA-44 sign/verify lives in `data/signing/`. */
    const val SIGNATURE_ALG_NONE    = 0
    const val SIGNATURE_ALG_MLDSA44 = 1

    private const val AES_KEY_BYTES   = 32
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS    = 128
    private const val GCM_TAG_BYTES   = GCM_TAG_BITS / 8

    /** Minimum size of a self-contained `nonce(12) || ciphertext || tag(16)` override-map blob (SPEC §9). */
    const val OVERRIDE_BLOB_MIN_BYTES = GCM_NONCE_BYTES + GCM_TAG_BYTES

    /** Encoded URI length that reliably fits in one QR code (CreateActivity/CreatePaperActivity warn past this). */
    const val MAX_URI_LENGTH = 2000

    /**
     * Max Split-fragment `data` payload per code so its encoded `tagdrop:` URI stays under
     * [MAX_URI_LENGTH] — this is the hard ceiling, not the default target, see
     * [DEFAULT_SECTOR_DATA_BYTES].
     */
    const val MAX_SECTOR_DATA_BYTES = 1300

    /**
     * Default Split-fragment `data` target used by [createContentSectorsAutoSized]/
     * [createPaperAutoSized] (SPEC §6: ~400 bytes/code ≈ QR Version 15, scans without zooming
     * on most phones) — tighter than the [MAX_SECTOR_DATA_BYTES] hard ceiling, which a denser
     * manual override (web generator only, so far) can still use.
     */
    const val DEFAULT_SECTOR_DATA_BYTES = 400

    /** Paired don't-bother-splitting threshold for [DEFAULT_SECTOR_DATA_BYTES] — see [MAX_URI_LENGTH]. */
    const val DEFAULT_URI_LENGTH = 700

    /** NDEF MIME type for a code's raw CBOR Record Sequence on an NFC tag (SPEC §12/§13). */
    const val NFC_MIME_TYPE = "application/vnd.tagdrop"

    private const val SCHEME          = "tagdrop:"
    private const val NAV_LINK_PREFIX = "tagdrop://"

    // ── QDEF Record Type IDs (SPEC.md §2.1) — all small, even, self-allocated (32768+ tier
    // is NOT used here: these are small enough to embed in a single CBOR byte, safe on
    // tagdrop:/NFC via TagDrop's own-URI-scheme isolation and implied namespace, SPEC §2.1a) ──
    const val TYPE_CONTENT_PREVIEW = 1
    const val TYPE_CONTENT_BODY    = 3
    const val TYPE_PAPER_PREVIEW   = 5
    const val TYPE_PAPER_BODY      = 7

    // QDEF stdlib Wrapper Record Type IDs (QDEF-SPEC.md §4.1) — always-global, even.
    private const val TYPE_SPLIT    = 2
    private const val TYPE_COMPRESS = 8

    // ── Content-Preview field keys (SPEC.md §3.1) ──────────────────────────────
    private const val PK_CACHE_ID     = 1
    private const val PK_HINT         = 3
    private const val PK_MIME         = 5
    private const val PK_FILENAME     = 7
    private const val PK_TITLE        = 9
    private const val PK_DESCRIPTION  = 11
    private const val PK_COLLECTION_ID    = 13
    private const val PK_COLLECTION_LABEL = 15
    private const val PK_COLLECTION_TAG   = 17
    private const val PK_ICON         = 19
    private const val PK_PIXEL_ART    = 21
    private const val PK_LAT          = 23
    private const val PK_LNG          = 25
    private const val PK_RADIUS_M     = 27
    private const val PK_PREFER_DECLARED_LOCATION = 29
    private const val PK_LOCATION_LABEL = 31
    private const val PK_KEY_MATERIAL = 33
    private const val PK_RETAIN_KEY   = 35
    private const val PK_ENCRYPTION   = 37
    private const val PK_KDF_ALG      = 39
    private const val PK_KDF_SALT     = 41
    private const val PK_KDF_ITERS    = 43
    private const val PK_SIGNATURE_ALGORITHM = 45
    private const val PK_SIGNER_ID    = 47
    private const val PK_SIGNER_LABEL = 49
    private const val PK_IN_REPLY_TO  = 51
    private const val PK_CREATED_AT   = 53
    private const val PK_SOURCE_URL   = 55

    // ── Content-Body field keys (SPEC.md §3.2) ─────────────────────────────────
    private const val BK_CONTENT       = 1
    private const val BK_SIGNATURE     = 3
    private const val BK_SIGNER_PUBKEY = 5

    // Content-Body's `content` may be a hidden encrypted override map (SPEC §9) — its own
    // independent local key namespace, unrelated to Content-Preview's numbering.
    private const val OK_HINT     = 1
    private const val OK_MIME     = 3
    private const val OK_CONTENT  = 5
    private const val OK_FILENAME = 7

    // ── Paper-Preview field keys (SPEC.md §3.3) ────────────────────────────────
    private const val PPK_ROOT_HASH   = 1
    private const val PPK_HINT        = 3
    private const val PPK_SET         = 5
    private const val PPK_SLUG        = 7
    private const val PPK_DOMAIN      = 9
    private const val PPK_STEP        = 11
    private const val PPK_COLLECTION_ID    = 13
    private const val PPK_COLLECTION_LABEL = 15
    private const val PPK_COLLECTION_TAG   = 17
    private const val PPK_ICON        = 19
    private const val PPK_LAT         = 21
    private const val PPK_LNG         = 23
    private const val PPK_RADIUS_M    = 25
    private const val PPK_PREFER_DECLARED_LOCATION = 27
    private const val PPK_LOCATION_LABEL = 29
    private const val PPK_SIGNATURE_ALGORITHM = 31
    private const val PPK_SIGNER_ID   = 33
    private const val PPK_SIGNER_LABEL = 35
    private const val PPK_IN_REPLY_TO = 37
    private const val PPK_CREATED_AT  = 39
    private const val PPK_SOURCE_URL  = 41
    private const val PPK_TITLE       = 43
    private const val PPK_DESCRIPTION = 45
    private const val PPK_KEY_MATERIAL = 47
    private const val PPK_RETAIN_KEY  = 49

    // ── Paper-Body field keys (SPEC.md §3.4) ───────────────────────────────────
    private const val PBK_FILES         = 1
    private const val PBK_RELATED       = 3
    private const val PBK_SIGNATURE     = 5
    private const val PBK_SIGNER_PUBKEY = 7

    // files[] entry local keys (SPEC.md §3.4 — independent namespace)
    private const val KF_SLUG        = 1
    private const val KF_MIME        = 2
    private const val KF_FILE_ID     = 3
    private const val KF_DESCRIPTION = 4
    private const val KF_PIXEL_ART   = 5

    // related[] entry local keys (SPEC.md §3.4 — independent namespace)
    private const val KR_HINT         = 1
    private const val KR_SET          = 2
    private const val KR_SLUG         = 3
    private const val KR_PAPER_ID     = 4
    private const val KR_LAT          = 5
    private const val KR_LNG          = 6
    private const val KR_RADIUS_M     = 7
    private const val KR_KEY_MATERIAL = 8
    private const val KR_RETAIN_KEY   = 9
    private const val KR_STEP         = 10

    // QDEF Split Wrapper (Type 2) / Compress Wrapper (Type 8) field keys (QDEF-SPEC.md §4.1).
    private const val SK_GROUP_ID = 2
    private const val SK_INDEX    = 4
    private const val SK_COUNT    = 6
    private const val SK_DATA     = 8
    private const val SK_TOTAL    = 9
    private const val SK_PARITY   = 11
    private const val CK_PAYLOAD  = 2

    // SPEC §10 signature-field key sets, per Record Type — what the placeholder-then-strip
    // discipline strips before hashing (contentSignedMessageHash/paperSignedMessageHash).
    private val CONTENT_PREVIEW_SIGNATURE_KEYS = setOf(PK_SIGNATURE_ALGORITHM, PK_SIGNER_ID, PK_SIGNER_LABEL)
    private val CONTENT_BODY_SIGNATURE_KEYS    = setOf(BK_SIGNATURE, BK_SIGNER_PUBKEY)
    private val PAPER_PREVIEW_SIGNATURE_KEYS   = setOf(PPK_ROOT_HASH, PPK_SIGNATURE_ALGORITHM, PPK_SIGNER_ID, PPK_SIGNER_LABEL)
    private val PAPER_BODY_SIGNATURE_KEYS      = setOf(PBK_SIGNATURE, PBK_SIGNER_PUBKEY)

    // Known-key sets for SPEC §2.2's even/odd criticality rule (see [checkRecordKeys]).
    private val KNOWN_CONTENT_PREVIEW = setOf(0,
        PK_CACHE_ID, PK_HINT, PK_MIME, PK_FILENAME, PK_TITLE, PK_DESCRIPTION,
        PK_COLLECTION_ID, PK_COLLECTION_LABEL, PK_COLLECTION_TAG, PK_ICON, PK_PIXEL_ART,
        PK_LAT, PK_LNG, PK_RADIUS_M, PK_PREFER_DECLARED_LOCATION, PK_LOCATION_LABEL,
        PK_KEY_MATERIAL, PK_RETAIN_KEY, PK_ENCRYPTION, PK_KDF_ALG, PK_KDF_SALT, PK_KDF_ITERS,
        PK_SIGNATURE_ALGORITHM, PK_SIGNER_ID, PK_SIGNER_LABEL, PK_IN_REPLY_TO, PK_CREATED_AT, PK_SOURCE_URL)
    private val KNOWN_CONTENT_BODY = setOf(0, BK_CONTENT, BK_SIGNATURE, BK_SIGNER_PUBKEY)
    private val KNOWN_PAPER_PREVIEW = setOf(0,
        PPK_ROOT_HASH, PPK_HINT, PPK_SET, PPK_SLUG, PPK_DOMAIN, PPK_STEP,
        PPK_COLLECTION_ID, PPK_COLLECTION_LABEL, PPK_COLLECTION_TAG, PPK_ICON,
        PPK_LAT, PPK_LNG, PPK_RADIUS_M, PPK_PREFER_DECLARED_LOCATION, PPK_LOCATION_LABEL,
        PPK_SIGNATURE_ALGORITHM, PPK_SIGNER_ID, PPK_SIGNER_LABEL, PPK_IN_REPLY_TO, PPK_CREATED_AT,
        PPK_SOURCE_URL, PPK_TITLE, PPK_DESCRIPTION, PPK_KEY_MATERIAL, PPK_RETAIN_KEY)
    private val KNOWN_PAPER_BODY = setOf(0, PBK_FILES, PBK_RELATED, PBK_SIGNATURE, PBK_SIGNER_PUBKEY)
    private val KNOWN_SPLIT = setOf(0, SK_GROUP_ID, SK_INDEX, SK_COUNT, SK_DATA, SK_TOTAL, SK_PARITY)
    private val KNOWN_COMPRESS = setOf(0, CK_PAYLOAD)

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
     * (SPEC §9): the override map's CBOR bytes (its own local key namespace: 1=hint,
     * 3=mime_type, 5=content, 7=filename) are compressed per [compression] (the same value
     * the clear Preview declares), then AES-256-GCM-encrypted under a fresh nonce.
     */
    fun encryptOverrideMap(override: TagDropPayload.OverrideMap, key: ByteArray, compression: Int): ByteArray {
        val cbor = MiniCbor.encodeMap(listOf(
            OK_HINT     to override.hint,
            OK_MIME     to override.mimeType,
            OK_CONTENT  to override.content,
            OK_FILENAME to override.filename
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
     *
     * The map's CBOR bytes may have been DEFLATE-compressed before encryption ("compress
     * first, then encrypt") with nothing declaring which — the GCM tag has already
     * authenticated the key by this point, so this tries the plaintext as-is first, then
     * inflated, mirroring the web reader's `tryDecryptOverrideMap`. Either candidate must
     * decode as exactly one CBOR map with no trailing bytes to count as a match — a
     * mis-branch (trying to inflate an uncompressed plaintext, or vice versa) either throws
     * or leaves trailing garbage, never a clean full-consume of both attempts.
     */
    fun tryDecryptOverrideMap(blob: ByteArray, key: ByteArray): TagDropPayload.OverrideMap? {
        if (blob.size < OVERRIDE_BLOB_MIN_BYTES) return null
        val nonce = blob.copyOfRange(0, GCM_NONCE_BYTES)
        val ciphertextAndTag = blob.copyOfRange(GCM_NONCE_BYTES, blob.size)
        val plaintext = decryptAesGcm(ciphertextAndTag, key, nonce) ?: return null
        for (inflate in listOf(false, true)) {
            val cbor = if (inflate) {
                runCatching { decompress(plaintext) }.getOrNull() ?: continue
            } else {
                plaintext
            }
            val result = runCatching { MiniCbor.decodeSequencePrefix(cbor, 1) }.getOrNull() ?: continue
            val (items, trailing) = result
            if (trailing.isNotEmpty()) continue
            @Suppress("UNCHECKED_CAST")
            val map = items[0] as? Map<Int, Any> ?: continue
            return TagDropPayload.OverrideMap(
                hint     = map.text(OK_HINT),
                mimeType = map.text(OK_MIME),
                content  = map.bytesOrNull(OK_CONTENT),
                filename = map.text(OK_FILENAME)
            )
        }
        return null
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

    // ── QDEF Records (SPEC.md §2's "Relationship to QDEF") ─────────────────────

    /** A QDEF Record: an ordinary CBOR map with `typeId` at key 0. Fields sort ascending (SPEC §2.2). */
    private fun cborRecord(typeId: Int, fields: List<Pair<Int, Any?>>): ByteArray =
        MiniCbor.encodeMap(listOf<Pair<Int, Any?>>(0 to typeId).plus(fields).sortedBy { it.first })

    /** QDEF Compress Wrapper (Type 8, QDEF-SPEC.md §4.1) — DEFLATEs [bodyBytes] as its `payload` field. */
    private fun compressWrap(bodyBytes: ByteArray): ByteArray =
        cborRecord(TYPE_COMPRESS, listOf(CK_PAYLOAD to compress(bodyBytes)))

    /**
     * Fragments [bytes] into [fragmentCount] QDEF Split Wrapper Records (Type 2, QDEF-SPEC.md
     * §4.1, SPEC §5) of `ceil(total/count)` bytes each (every fragment but the last the same
     * length), stamped with [groupId] (a content hash of [bytes], computed by the caller). If
     * [withParity], appends one more fragment at `index == count`: the byte-wise XOR of every
     * data fragment, each implicitly zero-padded to the widest — SPEC §5's single-loss
     * redundancy scheme.
     */
    private fun splitFragments(bytes: ByteArray, groupId: ByteArray, fragmentCount: Int, withParity: Boolean): List<ByteArray> {
        val total = bytes.size
        val chunkLen = (total + fragmentCount - 1) / fragmentCount
        val fragments = mutableListOf<ByteArray>()
        for (i in 0 until fragmentCount) {
            val start = minOf(i * chunkLen, total)
            val end = minOf(start + chunkLen, total)
            fragments.add(cborRecord(TYPE_SPLIT, listOf(
                SK_GROUP_ID to groupId, SK_INDEX to i, SK_COUNT to fragmentCount,
                SK_DATA to bytes.copyOfRange(start, end), SK_TOTAL to total,
                SK_PARITY to (1.takeIf { withParity })
            )))
        }
        if (withParity) {
            val parity = ByteArray(chunkLen)
            for (i in 0 until fragmentCount) {
                val start = minOf(i * chunkLen, total)
                val end = minOf(start + chunkLen, total)
                for (j in start until end) parity[j - start] = (parity[j - start].toInt() xor bytes[j].toInt()).toByte()
            }
            fragments.add(cborRecord(TYPE_SPLIT, listOf(
                SK_GROUP_ID to groupId, SK_INDEX to fragmentCount, SK_COUNT to fragmentCount,
                SK_DATA to parity, SK_TOTAL to total, SK_PARITY to 1
            )))
        }
        return fragments
    }

    /**
     * Builds the codes for one payload: [preview] repeated on every code (SPEC §5.1), plus
     * either [body] directly (single code, if it and [preview] together fit within
     * [maxFragmentDataBytes]), or [body] Split-wrapped across as many codes as needed. Shared
     * by [createContentSectors] and [createPaper] — the only difference between them is what
     * [preview]/[body] contain. `group_id` is `SHA-256(body)[0:8]` (the wrapped — Compress-
     * wrapped if [compress], not the raw plain — bytes, SPEC §5.1), computed once and reused
     * across every fragment of the group.
     */
    private fun buildCodes(
        preview: ByteArray, body: ByteArray, compressBody: Boolean, withParity: Boolean, maxFragmentDataBytes: Int
    ): List<ByteArray> {
        val wrapped = if (compressBody) compressWrap(body) else body
        val single = preview + wrapped
        if (maxFragmentDataBytes <= 0 || single.size <= maxFragmentDataBytes) return listOf(single)
        val fragmentCount = maxOf(1, (wrapped.size + maxFragmentDataBytes - 1) / maxFragmentDataBytes)
        if (fragmentCount <= 1) return listOf(single)
        val groupId = sha256(wrapped).copyOf(8)
        return splitFragments(wrapped, groupId, fragmentCount, withParity).map { preview + it }
    }

    // ── Content: build (SPEC §3.1-§3.2) ─────────────────────────────────────────

    /**
     * The result of building a Content payload's code(s): [codes] are the final wire bytes
     * (Split/Compress-wrapped as needed) to encode/render; [previewRaw]/[bodyRaw] are the
     * LOGICAL (unwrapped, unsplit) Preview/Body Record bytes — not reconstructable from [codes]
     * alone once Split/Compress-wrapped — exposed so a signer can hash them directly
     * (`contentSignedMessageHash`, `data/signing/SigningIdentity.kt`) without re-decoding a code.
     */
    data class ContentBuild(val codes: List<ByteArray>, val previewRaw: ByteArray, val bodyRaw: ByteArray, val cacheId: ByteArray?)

    /**
     * Builds the code(s) for a Content payload. A Preview Record (Content-Preview, Type 1) is
     * repeated on every code; a Body Record (Content-Body, Type 3, optionally Compress- and
     * Split-wrapped) carries `content` plus the large signature fields, when there is one.
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
     * an available live GPS fix. [preferDeclaredLocation] set true with [lat]/[lng] both absent,
     * or a non-null [locationLabel] with [lat]/[lng] both absent, declares this content has no
     * fixed point at all (e.g. mailed, or carried on a moving vehicle) — a live GPS fix MUST NOT
     * be substituted for it (SPEC §4.2, "Explicit no fixed point"). [locationLabel] is an optional
     * human-readable, non-coordinate location description (e.g. "🚋 Tram 40"); it may also be
     * present alongside coordinates, simply as descriptive text.
     *
     * [inReplyTo] is the `cache_id`/`root_hash` of a single parent this content is replying to
     * (SPEC §7, "Replies and threading") — omit for a new, unprompted message.
     *
     * [title] is an optional short subject/caption, kept separate from [hint]'s existing role.
     * [description] is an optional content teaser or message body — e.g. a postcard's message
     * when [rawContent] is spoken for by an attachment instead (SPEC §7, "Postcards").
     *
     * [createdAt] is an optional author-declared Unix timestamp (seconds since epoch) recording
     * when this payload was authored — the authoring device's clock at encode time, not an
     * independently verified timestamp (SPEC §3).
     *
     * [pixelArt] declares this image content should render with no smoothing/nearest-neighbor
     * scaling rather than a renderer's default bilinear smoothing (SPEC §7, "Pixel art") — for
     * pixel art large enough that a renderer's own small-image heuristic wouldn't catch it.
     *
     * [signatureAlgorithm]/[signature]/[signerPubkey]/[signerId]/[signerLabel] are Verified
     * Authorship fields (SPEC §10) — this function embeds them opaquely, exactly as given; see
     * `data/signing/SigningIdentity.kt` for the placeholder-then-strip build that actually signs.
     *
     * [withParity] adds one extra XOR-parity code (SPEC §5) when the payload needs splitting —
     * a no-op for a single-code payload.
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
        preferDeclaredLocation: Boolean = false, locationLabel: String? = null,
        inReplyTo: ByteArray? = null,
        title: String? = null, description: String? = null,
        createdAt: Long? = null,
        pixelArt: Boolean = false,
        signatureAlgorithm: Int = SIGNATURE_ALG_NONE, signature: ByteArray? = null,
        signerPubkey: ByteArray? = null, signerId: ByteArray? = null, signerLabel: String? = null,
        withParity: Boolean = false,
        maxSectorDataBytes: Int = Int.MAX_VALUE
    ): ContentBuild {
        // Compression is never applied to `rawContent` directly — matching the web generator's
        // createContentSectors, an encrypted override blob is already high-entropy (DEFLATE-ing
        // it wastes a wrapper layer for nothing) and gets its own inner compression instead
        // (compressed before encryption, inside encryptOverrideMap); a plain content slot is
        // compressed, if requested, by Compress-wrapping the whole Body Record (QDEF Type 8,
        // see buildCodes's compressBody), not by pre-compressing the field's bytes here.
        val cacheId: ByteArray?
        val contentSlot: ByteArray
        val encryption: Int
        if (override != null) {
            requireNotNull(encryptionKey) { "encryptionKey is required when override is provided" }
            val compression = if (compress) COMPRESSION_DEFLATE else COMPRESSION_NONE
            contentSlot = encryptOverrideMap(override, encryptionKey, compression)
            cacheId = randomCacheId()
            encryption = if (declareEncryption) ENCRYPTION_AES256GCM else ENCRYPTION_NONE
        } else {
            contentSlot = rawContent
            cacheId = contentId(rawContent)
            encryption = ENCRYPTION_NONE
        }

        val preview = cborRecord(TYPE_CONTENT_PREVIEW, listOf(
            PK_CACHE_ID to cacheId, PK_HINT to hint, PK_MIME to mimeType, PK_FILENAME to filename,
            PK_TITLE to title, PK_DESCRIPTION to description,
            PK_COLLECTION_ID to collectionId, PK_COLLECTION_LABEL to collectionLabel, PK_COLLECTION_TAG to collectionTag,
            PK_ICON to icon, PK_PIXEL_ART to (true.takeIf { pixelArt }),
            PK_LAT to lat, PK_LNG to lng, PK_RADIUS_M to radiusM,
            PK_PREFER_DECLARED_LOCATION to (true.takeIf { preferDeclaredLocation }), PK_LOCATION_LABEL to locationLabel,
            PK_KEY_MATERIAL to keyMaterial, PK_RETAIN_KEY to (false.takeIf { keyMaterial != null && !retainKey }),
            PK_ENCRYPTION to (encryption.takeIf { it != ENCRYPTION_NONE }),
            PK_KDF_ALG to null, PK_KDF_SALT to null, PK_KDF_ITERS to null,
            PK_SIGNATURE_ALGORITHM to (signatureAlgorithm.takeIf { it != SIGNATURE_ALG_NONE }),
            PK_SIGNER_ID to signerId, PK_SIGNER_LABEL to signerLabel,
            PK_IN_REPLY_TO to inReplyTo, PK_CREATED_AT to createdAt, PK_SOURCE_URL to null
        ))
        val body = cborRecord(TYPE_CONTENT_BODY, listOf(
            BK_CONTENT to contentSlot, BK_SIGNATURE to signature, BK_SIGNER_PUBKEY to signerPubkey
        ))
        val codes = buildCodes(preview, body, compressBody = compress && override == null, withParity = withParity, maxFragmentDataBytes = maxSectorDataBytes)
        return ContentBuild(codes, preview, body, cacheId)
    }

    /**
     * Two-pass auto-sizing wrapper around [createContentSectors] (mirrors the web generator's
     * createContentSectorsAutoSized): builds with an unbounded fragment size first; if the
     * single resulting code's `tagdrop:` URI fits under [DEFAULT_URI_LENGTH], uses it as-is;
     * otherwise rebuilds the whole payload with [DEFAULT_SECTOR_DATA_BYTES] forced, producing
     * several uniform codes.
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
        preferDeclaredLocation: Boolean = false, locationLabel: String? = null,
        inReplyTo: ByteArray? = null,
        title: String? = null, description: String? = null,
        createdAt: Long? = null,
        pixelArt: Boolean = false,
        signatureAlgorithm: Int = SIGNATURE_ALG_NONE, signature: ByteArray? = null,
        signerPubkey: ByteArray? = null, signerId: ByteArray? = null, signerLabel: String? = null,
        withParity: Boolean = false
    ): ContentBuild {
        val first = createContentSectors(
            hint, filename, mimeType, rawContent, compress,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey, override, encryptionKey, declareEncryption,
            lat, lng, radiusM, preferDeclaredLocation, locationLabel, inReplyTo, title, description,
            createdAt, pixelArt,
            signatureAlgorithm, signature, signerPubkey, signerId, signerLabel,
            withParity = false, maxSectorDataBytes = Int.MAX_VALUE
        )
        if (encode(first.codes.first()).length <= DEFAULT_URI_LENGTH) return first
        return createContentSectors(
            hint, filename, mimeType, rawContent, compress,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey, override, encryptionKey, declareEncryption,
            lat, lng, radiusM, preferDeclaredLocation, locationLabel, inReplyTo, title, description,
            createdAt, pixelArt,
            signatureAlgorithm, signature, signerPubkey, signerId, signerLabel,
            withParity = withParity, maxSectorDataBytes = DEFAULT_SECTOR_DATA_BYTES
        )
    }

    /**
     * Builds a single "key-only" code (SPEC §9): a Content-Preview Record carrying
     * [keyMaterial] for other content, with no Body Record at all.
     */
    fun createKeyCodeSector(keyMaterial: ByteArray, retainKey: Boolean = true, hint: String? = null): ByteArray {
        require(keyMaterial.size == AES_KEY_BYTES) { "key_material must be $AES_KEY_BYTES bytes" }
        return cborRecord(TYPE_CONTENT_PREVIEW, listOf(
            PK_HINT to hint, PK_KEY_MATERIAL to keyMaterial, PK_RETAIN_KEY to (false.takeIf { !retainKey })
        ))
    }

    /**
     * SPEC §10's Content signed-message hash: `SHA-256(Preview' || Body')`, where `Preview'`
     * strips [CONTENT_PREVIEW_SIGNATURE_KEYS] and `Body'` strips [CONTENT_BODY_SIGNATURE_KEYS]
     * — the hash an *unsigned* build would have produced, computed from the **logical**
     * (already Compress-unwrapped, if applicable) Record bytes. Works whether [previewRaw]/
     * [bodyRaw] are already signed (the common verification case) or never signed at all
     * (nothing to strip). [bodyRaw] null (a key-only code, SPEC §9: Preview only, no Body at
     * all) contributes empty bytes, not a skipped/absent term. Used both by the encode-side
     * "hash the placeholder build, then sign" step (`data/signing/SigningIdentity.kt`) and the
     * decode-side verify step (`data/signing/SignatureVerifier.kt`).
     */
    fun contentSignedMessageHash(previewRaw: ByteArray, bodyRaw: ByteArray?): ByteArray {
        val unsignedPreview = MiniCbor.stripKeys(previewRaw, CONTENT_PREVIEW_SIGNATURE_KEYS)
        val unsignedBody = bodyRaw?.let { MiniCbor.stripKeys(it, CONTENT_BODY_SIGNATURE_KEYS) } ?: ByteArray(0)
        return sha256(unsignedPreview + unsignedBody)
    }

    // ── Paper: build (SPEC §3.3-§3.4, §4.4) ─────────────────────────────────────

    /**
     * The result of building a Paper payload's code(s) — see [ContentBuild] for why
     * [previewRaw]/[bodyRaw] (the LOGICAL, unwrapped, unsplit Record bytes) are exposed
     * alongside [codes] and [paper].
     */
    data class PaperBuild(val paper: TagDropPayload.Paper, val codes: List<ByteArray>, val previewRaw: ByteArray, val bodyRaw: ByteArray)

    /**
     * Builds a Paper payload's code(s). `root_hash` is a genuine self-reference (SPEC §4.4):
     * `SHA-256(Preview' || Body')[0:8]`, where `Preview'` is Paper-Preview's own bytes with
     * `root_hash` itself (key 1) and the signature fields stripped, and `Body'` is Paper-Body's
     * bytes with its own signature fields stripped. Built via the same placeholder-then-strip
     * discipline SPEC §10 already requires for signing: encode Preview with `root_hash` (and,
     * if this payload will be signed, the signature fields too) omitted — not zero-filled,
     * simply absent, since removing a field never shifts any other field's encoded position —
     * compute the hash, then encode the final Preview with `root_hash` (and signature fields)
     * now included. This is in fact the *same* SHA-256 call as [paperSignedMessageHash],
     * just truncated to 8 bytes (SPEC §10).
     */
    fun createPaper(
        label: String?, set: String?, slug: String?,
        files: List<TagDropPayload.FileEntry>, related: List<TagDropPayload.RelatedPaper> = emptyList(),
        description: String? = null,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null,
        keyMaterial: ByteArray? = null, retainKey: Boolean = true,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false, locationLabel: String? = null,
        inReplyTo: ByteArray? = null,
        title: String? = null,
        createdAt: Long? = null,
        domain: String? = null,
        step: Int? = null,
        signatureAlgorithm: Int = SIGNATURE_ALG_NONE, signature: ByteArray? = null,
        signerPubkey: ByteArray? = null, signerId: ByteArray? = null, signerLabel: String? = null,
        compressBody: Boolean = false,
        withParity: Boolean = false,
        maxSectorDataBytes: Int = Int.MAX_VALUE
    ): PaperBuild {
        val filesCbor = MiniCbor.encodeArrayBytes(files.map { f ->
            MiniCbor.CborMap(listOf(
                KF_SLUG to f.slug, KF_MIME to f.mimeType, KF_FILE_ID to f.fileId,
                KF_DESCRIPTION to f.description, KF_PIXEL_ART to (true.takeIf { f.pixelArt })
            ))
        })
        val relatedCbor = MiniCbor.encodeArrayBytes(related.map { r ->
            MiniCbor.CborMap(listOf(
                KR_HINT to r.hint, KR_SET to r.set, KR_SLUG to r.slug, KR_PAPER_ID to r.paperId,
                KR_LAT to r.lat, KR_LNG to r.lng, KR_RADIUS_M to r.radiusM,
                KR_KEY_MATERIAL to r.keyMaterial,
                KR_RETAIN_KEY to (false.takeIf { r.keyMaterial != null && !r.retainKey }),
                KR_STEP to r.step
            ))
        })

        fun buildBody(sig: ByteArray?, pubkey: ByteArray?) = cborRecord(TYPE_PAPER_BODY, listOf(
            PBK_FILES to filesCbor, PBK_RELATED to relatedCbor,
            PBK_SIGNATURE to sig, PBK_SIGNER_PUBKEY to pubkey
        ))
        fun buildPreview(rootHash: ByteArray?, sigAlg: Int?, sId: ByteArray?, sLabel: String?) = cborRecord(TYPE_PAPER_PREVIEW, listOf(
            PPK_ROOT_HASH to rootHash, PPK_HINT to label, PPK_SET to set, PPK_SLUG to slug, PPK_DOMAIN to domain,
            PPK_STEP to step,
            PPK_COLLECTION_ID to collectionId, PPK_COLLECTION_LABEL to collectionLabel, PPK_COLLECTION_TAG to collectionTag,
            PPK_ICON to icon,
            PPK_LAT to lat, PPK_LNG to lng, PPK_RADIUS_M to radiusM,
            PPK_PREFER_DECLARED_LOCATION to (true.takeIf { preferDeclaredLocation }), PPK_LOCATION_LABEL to locationLabel,
            PPK_SIGNATURE_ALGORITHM to sigAlg, PPK_SIGNER_ID to sId, PPK_SIGNER_LABEL to sLabel,
            PPK_IN_REPLY_TO to inReplyTo, PPK_CREATED_AT to createdAt, PPK_SOURCE_URL to null,
            PPK_TITLE to title, PPK_DESCRIPTION to description,
            PPK_KEY_MATERIAL to keyMaterial, PPK_RETAIN_KEY to (false.takeIf { keyMaterial != null && !retainKey })
        ))

        val isSigned = signatureAlgorithm != SIGNATURE_ALG_NONE || signature != null ||
            signerPubkey != null || signerId != null || signerLabel != null
        val placeholderPreview = buildPreview(
            rootHash = null,
            sigAlg = if (isSigned) signatureAlgorithm.takeIf { it != SIGNATURE_ALG_NONE } else null,
            sId = if (isSigned) signerId else null, sLabel = if (isSigned) signerLabel else null
        )
        val placeholderBody = buildBody(if (isSigned) signature else null, if (isSigned) signerPubkey else null)
        val rootHash = paperSignedMessageHash(placeholderPreview, placeholderBody).copyOf(8)

        val finalPreview = buildPreview(
            rootHash = rootHash,
            sigAlg = if (isSigned) signatureAlgorithm.takeIf { it != SIGNATURE_ALG_NONE } else null,
            sId = if (isSigned) signerId else null, sLabel = if (isSigned) signerLabel else null
        )
        val finalBody = placeholderBody

        val paper = TagDropPayload.Paper(
            rootHash = rootHash, label = label, set = set, slug = slug, files = files, related = related,
            description = description, collectionId = collectionId, collectionLabel = collectionLabel,
            collectionTag = collectionTag, icon = icon, keyMaterial = keyMaterial, retainKey = retainKey,
            lat = lat, lng = lng, radiusM = radiusM, preferDeclaredLocation = preferDeclaredLocation,
            locationLabel = locationLabel, inReplyTo = inReplyTo, title = title, createdAt = createdAt,
            domain = domain, step = step,
            signatureAlgorithm = signatureAlgorithm, signature = signature, signerPubkey = signerPubkey,
            signerId = signerId, signerLabel = signerLabel
        )
        val codes = buildCodes(finalPreview, finalBody, compressBody = compressBody, withParity = withParity, maxFragmentDataBytes = maxSectorDataBytes)
        return PaperBuild(paper, codes, finalPreview, finalBody)
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
        preferDeclaredLocation: Boolean = false, locationLabel: String? = null,
        inReplyTo: ByteArray? = null,
        title: String? = null,
        createdAt: Long? = null,
        domain: String? = null,
        step: Int? = null,
        signatureAlgorithm: Int = SIGNATURE_ALG_NONE, signature: ByteArray? = null,
        signerPubkey: ByteArray? = null, signerId: ByteArray? = null, signerLabel: String? = null,
        compressBody: Boolean = false,
        withParity: Boolean = false
    ): PaperBuild {
        val first = createPaper(
            label, set, slug, files, related, description,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey,
            lat, lng, radiusM, preferDeclaredLocation, locationLabel, inReplyTo, title,
            createdAt, domain, step,
            signatureAlgorithm, signature, signerPubkey, signerId, signerLabel,
            compressBody = compressBody, withParity = false, maxSectorDataBytes = Int.MAX_VALUE
        )
        if (encode(first.codes.first()).length <= DEFAULT_URI_LENGTH) return first
        return createPaper(
            label, set, slug, files, related, description,
            collectionId, collectionLabel, collectionTag, icon,
            keyMaterial, retainKey,
            lat, lng, radiusM, preferDeclaredLocation, locationLabel, inReplyTo, title,
            createdAt, domain, step,
            signatureAlgorithm, signature, signerPubkey, signerId, signerLabel,
            compressBody = compressBody, withParity = withParity, maxSectorDataBytes = DEFAULT_SECTOR_DATA_BYTES
        )
    }

    /**
     * SPEC §10's Paper signed-message hash: `SHA-256(Preview' || Body')`, where `Preview'`
     * strips [PAPER_PREVIEW_SIGNATURE_KEYS] (including `root_hash` itself, key 1) and `Body'`
     * strips [PAPER_BODY_SIGNATURE_KEYS] — the *same* computation `root_hash` uses (SPEC §4.4),
     * just returned in full rather than truncated to 8 bytes. Used both by the encode-side
     * "hash the placeholder build" step ([createPaper], `data/signing/SigningIdentity.kt`)
     * and the decode-side verify/reconstruct step.
     */
    fun paperSignedMessageHash(previewRaw: ByteArray, bodyRaw: ByteArray): ByteArray {
        val unsignedPreview = MiniCbor.stripKeys(previewRaw, PAPER_PREVIEW_SIGNATURE_KEYS)
        val unsignedBody = MiniCbor.stripKeys(bodyRaw, PAPER_BODY_SIGNATURE_KEYS)
        return sha256(unsignedPreview + unsignedBody)
    }

    /**
     * The Record Sequence bytes for [paper]'s Preview+Body (unwrapped, unsplit) — what gets
     * stored as `ScannedPaper.cborBytes` for later re-parsing ([decodePaperStream]).
     */
    fun paperStreamBytes(paper: TagDropPayload.Paper): ByteArray {
        val build = createPaper(
            paper.label, paper.set, paper.slug, paper.files, paper.related, paper.description,
            paper.collectionId, paper.collectionLabel, paper.collectionTag, paper.icon,
            paper.keyMaterial, paper.retainKey,
            paper.lat, paper.lng, paper.radiusM, paper.preferDeclaredLocation, paper.locationLabel,
            paper.inReplyTo, paper.title, paper.createdAt, paper.domain, paper.step,
            paper.signatureAlgorithm, paper.signature, paper.signerPubkey, paper.signerId, paper.signerLabel,
            withParity = false, maxSectorDataBytes = Int.MAX_VALUE
        )
        return build.codes.single()
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    /** A code's `tagdrop:` encoding URI: `tagdrop:` + Base41 of its raw Record Sequence bytes. */
    fun encode(recordSequence: ByteArray): String = SCHEME + Base41.encode(recordSequence)

    /**
     * The raw Record Sequence bytes for a single-code Content payload reconstructed from a
     * cached page's resolved fields — used by the on-device "Inspect CBOR" diagnostic only.
     */
    fun inspectableContentCbor(
        hint: String?, filename: String?, mimeType: String, content: ByteArray,
        collectionId: ByteArray? = null, collectionLabel: String? = null,
        collectionTag: String? = null, icon: String? = null
    ): ByteArray = createContentSectors(
        hint, filename, mimeType, content, compress = false,
        collectionId = collectionId, collectionLabel = collectionLabel,
        collectionTag = collectionTag, icon = icon
    ).codes.first()

    // ── Decoding (SPEC §2, §5.1) ────────────────────────────────────────────────

    /**
     * Decodes one scanned string into a [TagDropScan]: a `tagdrop:` encoding URI becomes a
     * [TagDropScan.RecordScan]; a raw `data:` URI becomes a [TagDropScan.LegacyScan] (§11).
     * Navigation links (`tagdrop://`, §2) and anything else return null.
     */
    fun decode(scanned: String): TagDropScan? {
        if (scanned.startsWith("data:")) return TagDropScan.LegacyScan(TagDropPayload.Legacy(scanned))
        if (!scanned.startsWith(SCHEME) || scanned.startsWith(NAV_LINK_PREFIX)) return null
        val bytes = runCatching { Base41.decode(scanned.removePrefix(SCHEME)) }.getOrNull() ?: return null
        return decodeRaw(bytes)
    }

    /**
     * Decodes a [ScannedRecord] straight from its raw CBOR Record Sequence, with no
     * `tagdrop:`/Base41 text wrapper — the carrier already supports raw bytes (SPEC §13: NFC
     * NDEF). Returns null for an unrecognized Preview Type ID or malformed sequence.
     */
    fun decodeRaw(bytes: ByteArray): TagDropScan? = recordScanResult(bytes)?.let { TagDropScan.RecordScan(it) }

    /**
     * Decodes one QDEF Record (a CBOR map with a Type ID at key 0) from the head of [bytes].
     * Returns `(record, raw, trailing)` — `raw` is the Record's own exact byte range (what
     * signature/group-id hashes are computed over), `trailing` whatever follows in the
     * Sequence — or null if the head of [bytes] isn't a well-formed Record.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeRecordPrefix(bytes: ByteArray): Triple<Map<Int, Any>, ByteArray, ByteArray>? = runCatching {
        val (items, trailing) = MiniCbor.decodeSequencePrefix(bytes, 1)
        val record = items[0] as? Map<Int, Any> ?: return@runCatching null
        if (record[0] == null) return@runCatching null
        Triple(record, bytes.copyOfRange(0, bytes.size - trailing.size), trailing)
    }.getOrNull()

    /** SPEC §2.2 even/odd criticality: an unrecognized EVEN key means this decoder can't
     *  safely use the Record — reject it; an unrecognized ODD key is optional and ignored. */
    private fun checkRecordKeys(record: Map<Int, Any>, known: Set<Int>): Boolean =
        record.keys.all { it in known || it % 2 != 0 }

    private fun recordScanResult(bytes: ByteArray): ScannedRecord? {
        val (first, firstRaw, trailing) = decodeRecordPrefix(bytes) ?: return null
        val typeId = (first[0] as? Int) ?: (first[0] as? Long)?.toInt() ?: return null
        val kind: PayloadKind
        val known: Set<Int>
        when (typeId) {
            TYPE_CONTENT_PREVIEW -> { kind = PayloadKind.CONTENT; known = KNOWN_CONTENT_PREVIEW }
            TYPE_PAPER_PREVIEW   -> { kind = PayloadKind.PAPER; known = KNOWN_PAPER_PREVIEW }
            else -> return null
        }
        if (!checkRecordKeys(first, known)) return null
        var second: Triple<Map<Int, Any>, ByteArray, ByteArray>? = null
        if (trailing.isNotEmpty()) {
            second = decodeRecordPrefix(trailing) ?: return null
        }
        return ScannedRecord(kind, firstRaw, first, second?.second, second?.first)
    }

    /**
     * Unwraps a (possibly Compress-wrapped) Body byte sequence into its Body Record's own
     * `(record, raw)` — `raw` being the LOGICAL Record bytes SPEC §10's signature formula
     * covers, i.e. after any Compress unwrap — or null if malformed.
     */
    private fun unwrapBody(bodyWireBytes: ByteArray, isPaper: Boolean): Pair<Map<Int, Any>, ByteArray>? {
        var cur = decodeRecordPrefix(bodyWireBytes) ?: return null
        val (curRecord0, _, _) = cur
        val typeId0 = (curRecord0[0] as? Int) ?: (curRecord0[0] as? Long)?.toInt()
        if (typeId0 == TYPE_COMPRESS) {
            if (!checkRecordKeys(curRecord0, KNOWN_COMPRESS)) return null
            val payload = curRecord0[CK_PAYLOAD] as? ByteArray ?: return null
            val inflated = runCatching { decompress(payload) }.getOrNull() ?: return null
            cur = decodeRecordPrefix(inflated) ?: return null
        }
        val (record, raw, _) = cur
        val typeId = (record[0] as? Int) ?: (record[0] as? Long)?.toInt()
        val expectedType = if (isPaper) TYPE_PAPER_BODY else TYPE_CONTENT_BODY
        val expectedKeys = if (isPaper) KNOWN_PAPER_BODY else KNOWN_CONTENT_BODY
        if (typeId != expectedType || !checkRecordKeys(record, expectedKeys)) return null
        return record to raw
    }

    // ── Reassembly primitives used by SectorAssembler ───────────────────────────

    /** Outcome of parsing a Content payload's reassembled Body (SPEC §5 steps 3-5). */
    sealed class ContentParse {
        /**
         * Parsed and (if reassembled from a Split group) `group_id`-verified. [bodyRaw] is the
         * LOGICAL (Compress-unwrapped, if applicable) Body Record bytes SPEC §10's signature
         * formula covers — null for a key-only code (no Body at all).
         */
        data class Ok(val content: TagDropPayload.Content, val bodyRaw: ByteArray?) : ContentParse()
        /** `group_id` was checked and did not match — incomplete or corrupt assembly. */
        object HashMismatch : ContentParse()
        /** The bytes aren't a well-formed Content Preview+Body pair. */
        object Malformed : ContentParse()
    }

    /**
     * Resolves a scanned Content [record] whose Body (if any) is already fully in hand — either
     * `record.second` directly (single-code payload) or externally reassembled Split-fragment
     * data (multi-code payload, passed as [reassembledBody]). `record.second`/[reassembledBody]
     * both null means a key-only code (SPEC §9): Preview only, empty content.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseContentStream(record: ScannedRecord, reassembledBody: ByteArray? = null): ContentParse {
        val bodyWireBytes = reassembledBody ?: record.secondRaw
        val preview = record.preview
        if (bodyWireBytes == null) {
            return ContentParse.Ok(contentFromParts(record.previewRaw, preview, null, ByteArray(0)), bodyRaw = null)
        }
        val (body, bodyRaw) = unwrapBody(bodyWireBytes, isPaper = false) ?: return ContentParse.Malformed
        val slot = body[BK_CONTENT] as? ByteArray ?: ByteArray(0)
        return ContentParse.Ok(contentFromParts(record.previewRaw, preview, bodyRaw, slot, body), bodyRaw = bodyRaw)
    }

    @Suppress("UNCHECKED_CAST")
    private fun contentFromParts(previewRaw: ByteArray, preview: Map<Int, Any>, bodyRaw: ByteArray?, slot: ByteArray, body: Map<Int, Any>? = null): TagDropPayload.Content =
        TagDropPayload.Content(
            cacheId         = preview.bytesOrNull(PK_CACHE_ID),
            hint            = preview.text(PK_HINT),
            filename        = preview.text(PK_FILENAME),
            mimeType        = preview.text(PK_MIME) ?: "",
            compression     = COMPRESSION_NONE, // Compress Wrapper presence is transient (unwrapped already); not re-declared on TagDropPayload
            content         = slot,
            overrideBlob    = slot.takeIf { it.size >= OVERRIDE_BLOB_MIN_BYTES },
            encryption      = preview.uint(PK_ENCRYPTION)?.toInt() ?: ENCRYPTION_NONE,
            keyMaterial     = preview.bytesOrNull(PK_KEY_MATERIAL),
            retainKey       = preview.boolOrNull(PK_RETAIN_KEY) ?: true,
            collectionId    = preview.bytesOrNull(PK_COLLECTION_ID),
            collectionLabel = preview.text(PK_COLLECTION_LABEL),
            collectionTag   = preview.text(PK_COLLECTION_TAG),
            icon            = preview.text(PK_ICON),
            kdfAlg          = preview.uint(PK_KDF_ALG)?.toInt() ?: KDF_NONE,
            kdfSalt         = preview.bytesOrNull(PK_KDF_SALT),
            kdfIters        = preview.uint(PK_KDF_ITERS)?.toInt() ?: DEFAULT_KDF_ITERS,
            lat             = preview.doubleOrNull(PK_LAT),
            lng             = preview.doubleOrNull(PK_LNG),
            radiusM         = preview.doubleOrNull(PK_RADIUS_M),
            preferDeclaredLocation = preview.boolOrNull(PK_PREFER_DECLARED_LOCATION) ?: false,
            locationLabel   = preview.text(PK_LOCATION_LABEL),
            inReplyTo       = preview.bytesOrNull(PK_IN_REPLY_TO),
            title           = preview.text(PK_TITLE),
            description     = preview.text(PK_DESCRIPTION),
            createdAt       = preview.uint(PK_CREATED_AT),
            pixelArt        = preview.boolOrNull(PK_PIXEL_ART) ?: false,
            sourceUrl       = preview.text(PK_SOURCE_URL),
            signatureAlgorithm = preview.uint(PK_SIGNATURE_ALGORITHM)?.toInt() ?: SIGNATURE_ALG_NONE,
            signature       = body?.bytesOrNull(BK_SIGNATURE),
            signerPubkey    = body?.bytesOrNull(BK_SIGNER_PUBKEY),
            signerId        = preview.bytesOrNull(PK_SIGNER_ID),
            signerLabel     = preview.text(PK_SIGNER_LABEL)
        )

    /**
     * Parses a fully-reassembled Paper Preview+Body pair into a [TagDropPayload.Paper] (SPEC
     * §4.3-§4.4), recomputing and verifying `root_hash` from the bytes. [bodyWireBytes] is the
     * Body Record's wire bytes (possibly Compress-wrapped; possibly externally Split-
     * reassembled). Returns null if malformed or `root_hash` doesn't verify against
     * [record]'s declared value (when present).
     */
    fun parsePaperStream(record: ScannedRecord, bodyWireBytes: ByteArray?): TagDropPayload.Paper? {
        if (bodyWireBytes == null) return null
        val (body, bodyRaw) = unwrapBody(bodyWireBytes, isPaper = true) ?: return null
        val computedRootHash = paperSignedMessageHash(record.previewRaw, bodyRaw).copyOf(8)
        val declaredRootHash = record.preview.bytesOrNull(PPK_ROOT_HASH)
        if (declaredRootHash != null && !declaredRootHash.contentEquals(computedRootHash)) return null
        return paperFromParts(record.preview, body, computedRootHash)
    }

    /**
     * Decodes a stored Paper Record Sequence (e.g. `ScannedPaper.cborBytes`) back into a
     * [TagDropPayload.Paper], recomputing `root_hash` from the bytes (SPEC §4.4). Used to
     * re-read a scanned paper's directory for navigation/display.
     */
    fun decodePaperStream(stream: ByteArray): TagDropPayload.Paper? {
        val scan = recordScanResult(stream) ?: return null
        if (scan.kind != PayloadKind.PAPER) return null
        return parsePaperStream(scan, scan.secondRaw)
    }

    @Suppress("UNCHECKED_CAST")
    private fun paperFromParts(preview: Map<Int, Any>, body: Map<Int, Any>, rootHash: ByteArray): TagDropPayload.Paper {
        val filesRaw = body[PBK_FILES] as? ByteArray
        val files = filesRaw?.let { raw ->
            runCatching {
                val (items, _) = MiniCbor.decodeSequencePrefix(raw, 1)
                (items[0] as? List<*>)?.mapNotNull { entry ->
                    val em = entry as? Map<Int, Any> ?: return@mapNotNull null
                    TagDropPayload.FileEntry(
                        slug        = em.text(KF_SLUG) ?: return@mapNotNull null,
                        mimeType    = em.text(KF_MIME) ?: return@mapNotNull null,
                        fileId      = em.bytesOrNull(KF_FILE_ID) ?: return@mapNotNull null,
                        description = em.text(KF_DESCRIPTION),
                        pixelArt    = em.boolOrNull(KF_PIXEL_ART) ?: false
                    )
                } ?: emptyList()
            }.getOrDefault(emptyList())
        } ?: emptyList()

        val relatedRaw = body[PBK_RELATED] as? ByteArray
        val related = relatedRaw?.let { raw ->
            runCatching {
                val (items, _) = MiniCbor.decodeSequencePrefix(raw, 1)
                (items[0] as? List<*>)?.mapNotNull { entry ->
                    val em = entry as? Map<Int, Any> ?: return@mapNotNull null
                    TagDropPayload.RelatedPaper(
                        hint        = em.text(KR_HINT) ?: return@mapNotNull null,
                        set         = em.text(KR_SET),
                        slug        = em.text(KR_SLUG),
                        paperId     = em.bytesOrNull(KR_PAPER_ID),
                        lat         = em.doubleOrNull(KR_LAT),
                        lng         = em.doubleOrNull(KR_LNG),
                        radiusM     = em.doubleOrNull(KR_RADIUS_M),
                        keyMaterial = em.bytesOrNull(KR_KEY_MATERIAL),
                        retainKey   = em.boolOrNull(KR_RETAIN_KEY) ?: true,
                        step        = em.uint(KR_STEP)?.toInt()
                    )
                } ?: emptyList()
            }.getOrDefault(emptyList())
        } ?: emptyList()

        return TagDropPayload.Paper(
            rootHash        = rootHash,
            label           = preview.text(PPK_HINT),
            set             = preview.text(PPK_SET),
            slug            = preview.text(PPK_SLUG),
            files           = files,
            related         = related,
            description     = preview.text(PPK_DESCRIPTION),
            collectionId    = preview.bytesOrNull(PPK_COLLECTION_ID),
            collectionLabel = preview.text(PPK_COLLECTION_LABEL),
            collectionTag   = preview.text(PPK_COLLECTION_TAG),
            icon            = preview.text(PPK_ICON),
            keyMaterial     = preview.bytesOrNull(PPK_KEY_MATERIAL),
            retainKey       = preview.boolOrNull(PPK_RETAIN_KEY) ?: true,
            lat             = preview.doubleOrNull(PPK_LAT),
            lng             = preview.doubleOrNull(PPK_LNG),
            radiusM         = preview.doubleOrNull(PPK_RADIUS_M),
            preferDeclaredLocation = preview.boolOrNull(PPK_PREFER_DECLARED_LOCATION) ?: false,
            locationLabel   = preview.text(PPK_LOCATION_LABEL),
            inReplyTo       = preview.bytesOrNull(PPK_IN_REPLY_TO),
            title           = preview.text(PPK_TITLE),
            createdAt       = preview.uint(PPK_CREATED_AT),
            domain          = preview.text(PPK_DOMAIN),
            step            = preview.uint(PPK_STEP)?.toInt(),
            signatureAlgorithm = preview.uint(PPK_SIGNATURE_ALGORITHM)?.toInt() ?: SIGNATURE_ALG_NONE,
            signature       = body.bytesOrNull(PBK_SIGNATURE),
            signerPubkey    = body.bytesOrNull(PBK_SIGNER_PUBKEY),
            signerId        = preview.bytesOrNull(PPK_SIGNER_ID),
            signerLabel     = preview.text(PPK_SIGNER_LABEL)
        )
    }

    /**
     * Reads one Split fragment's own fields ([SplitFragment]) from [record]'s `second` (must
     * already be `TYPE_SPLIT`, per [ScannedRecord.second]'s own Type ID at key 0). Returns
     * null if malformed or missing a required field.
     */
    @Suppress("UNCHECKED_CAST")
    fun splitFragmentOf(record: ScannedRecord): SplitFragment? {
        val frag = record.second ?: return null
        val typeId = (frag[0] as? Int) ?: (frag[0] as? Long)?.toInt()
        if (typeId != TYPE_SPLIT || !checkRecordKeys(frag, KNOWN_SPLIT)) return null
        val groupId = frag.bytesOrNull(SK_GROUP_ID) ?: return null
        val index = frag.uint(SK_INDEX)?.toInt() ?: 0
        val count = frag.uint(SK_COUNT)?.toInt() ?: 1
        val data = frag.bytesOrNull(SK_DATA) ?: return null
        val total = frag.uint(SK_TOTAL)?.toInt() ?: return null
        if (count < 1) return null
        return SplitFragment(groupId, index, count, data, total, isParity = index >= count)
    }

    /** Unwraps [record]'s Body into its Body Record's wire bytes, for a single-code (non-Split) payload. */
    fun unwrappedBodyBytes(record: ScannedRecord): ByteArray? = record.secondRaw

    /**
     * The LOGICAL Body Record bytes — after any Compress Wrapper (QDEF Type 8) unwrap — that
     * SPEC §10's signature formula (`contentSignedMessageHash`/`paperSignedMessageHash`) covers,
     * given [bodyWireBytes] (a complete, possibly Compress-wrapped, possibly externally
     * Split-reassembled Body byte sequence). Returns null if malformed.
     */
    fun logicalBodyBytes(bodyWireBytes: ByteArray, isPaper: Boolean): ByteArray? =
        unwrapBody(bodyWireBytes, isPaper)?.second

    /** Whether [record]'s second Record (if any) is a Split Wrapper fragment rather than a plain/Compress-wrapped Body. */
    fun isSplitFragment(record: ScannedRecord): Boolean {
        val typeId = (record.second?.get(0) as? Int) ?: (record.second?.get(0) as? Long)?.toInt()
        return typeId == TYPE_SPLIT
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

    private val PREVIEW_KEY_NAMES = mapOf(
        PK_CACHE_ID to "cache_id", PK_HINT to "hint", PK_MIME to "mime_type", PK_FILENAME to "filename",
        PK_TITLE to "title", PK_DESCRIPTION to "description",
        PK_COLLECTION_ID to "collection_id", PK_COLLECTION_LABEL to "collection_label", PK_COLLECTION_TAG to "collection_tag",
        PK_ICON to "icon", PK_PIXEL_ART to "pixel_art",
        PK_LAT to "lat", PK_LNG to "lng", PK_RADIUS_M to "radius_m",
        PK_PREFER_DECLARED_LOCATION to "prefer_declared_location", PK_LOCATION_LABEL to "location_label",
        PK_KEY_MATERIAL to "key_material", PK_RETAIN_KEY to "retain_key", PK_ENCRYPTION to "encryption",
        PK_KDF_ALG to "kdf_alg", PK_KDF_SALT to "kdf_salt", PK_KDF_ITERS to "kdf_iters",
        PK_SIGNATURE_ALGORITHM to "signature_algorithm", PK_SIGNER_ID to "signer_id", PK_SIGNER_LABEL to "signer_label",
        PK_IN_REPLY_TO to "in_reply_to", PK_CREATED_AT to "created_at", PK_SOURCE_URL to "source_url"
    )
    private val BODY_KEY_NAMES = mapOf(BK_CONTENT to "content", BK_SIGNATURE to "signature", BK_SIGNER_PUBKEY to "signer_pubkey")

    /**
     * Pretty-prints a raw TagDrop code's CBOR Record Sequence for the on-device debug view: a
     * hex dump, then each Record's Type ID and fields by name (SPEC §2, §3.1-§3.2 — Content
     * only; a Paper or wrapped/Split code prints its raw field map without named keys).
     */
    @Suppress("UNCHECKED_CAST")
    fun describeCbor(cbor: ByteArray): String = buildString {
        appendLine("${cbor.size} bytes")
        appendLine(cbor.toHexDump())
        appendLine()
        runCatching {
            val items = MiniCbor.decodeSequence(cbor)
            for ((i, item) in items.withIndex()) {
                val record = item as? Map<Int, Any> ?: continue
                val typeId = (record[0] as? Int) ?: (record[0] as? Long)?.toInt()
                appendLine("Record $i — Type $typeId:")
                val keyNames = when (typeId) {
                    TYPE_CONTENT_PREVIEW -> PREVIEW_KEY_NAMES
                    TYPE_CONTENT_BODY    -> BODY_KEY_NAMES
                    else -> emptyMap()
                }
                describeMap(record, 1, this, keyNames)
                appendLine()
            }
        }.onFailure { append("Failed to decode as CBOR sequence: ${it.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun describeMap(map: Map<Int, Any>, indent: Int, out: StringBuilder, keyNames: Map<Int, String>) {
        val pad = "  ".repeat(indent)
        for ((key, value) in map.toSortedMap()) {
            val label = keyNames[key]?.let { "$key ($it)" } ?: "$key"
            when (value) {
                is ByteArray -> out.appendLine("$pad$label: ${value.toHexDump()} (${value.size} bytes)")
                is String    -> out.appendLine("$pad$label: \"$value\"")
                else -> out.appendLine("$pad$label: $value")
            }
        }
    }

    private fun ByteArray.toHexDump(): String = joinToString(" ") { "%02x".format(it) }
}
