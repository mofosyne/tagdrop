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
 * Encodes and decodes TagDrop codes — the wire-format codec (SPEC.md §2-§5, §9, §10, v16).
 *
 * Encoding URI scheme:  tagdrop:<base41-cbor-root>
 *   <base41-cbor-root> = Base41( QDEF self-delimited root array of Records )
 * Every Record is its own self-delimited CBOR array, `[namespace?, typeId, map?, subrecord*]`
 * (QDEF-SPEC.md §3.1/§3.5) — not a bare typeId-then-map pair. As of SPEC.md v15, a Record's "one
 * genuinely singular value," if it has one (Compress Wrapper's deflated bytes, Media Payload's
 * content), lives at reserved map key `0` (QDEF-SPEC.md §3.6) rather than a separate positional
 * payload item — there is no positional payload slot in the grammar at all any more. The root
 * array's own leading element always declares TagDrop's namespace (§2.1a, [TAGDROP_NAMESPACE]) —
 * mandatory on every carrier as of SPEC.md v14, not just byte-mode QR. As of SPEC.md v16, EVERY
 * TagDrop code is this Bundle shape, wrapping one (the key-only case, §9) or two (the common
 * case) top-level Records as its own subrecords — a Record can no longer both introduce a
 * namespace and be scoped by it in the same array, so the old "a lone top-level Record needs no
 * Bundle wrapping" exception is gone. Every TagDrop-scoped Record nested anywhere underneath
 * resolves back to the root's one declaration purely via its own **negative** wire typeId
 * (`h''`'s old cascade job, now done by sign alone at zero extra bytes) — it carries no
 * namespace item of its own at all. A code always carries the small, always-plain part of
 * whatever payload it's part of (Paper: Preview; Content: Content Extension + Media Preview,
 * §3.1/§3.1a) and, if the payload has a large part, either that part complete (single code) or
 * one Split-Wrapper-wrapped (QDEF Type 1) fragment of it (multi-code). No magic header on this
 * carrier — the `tagdrop:` scheme itself is still the dispatch signal — but every other carrier,
 * including NFC NDEF as of SPEC.md v17, does carry the QDEF magic header (see [addQdefFraming]);
 * the namespace itself is transmitted on the wire the same as on every carrier either way (§2.1a).
 *
 * Content (SPEC §3.1/§3.1a, §5.1): Content Extension (declared Type 1, wire `-1`, TagDrop-scoped) carries
 * hint/collection/location/small-signing fields and is always whole, unwrapped, and repeated
 * on every code. Media Preview (QDEF standard Type 7) carries file identification
 * (mediaType/contentHash/filename/label). Media Payload (QDEF standard Type 3) carries the
 * content bytes — nested as Media Preview's own subrecord when the payload fits on one code,
 * or Split-wrapped (with Media Preview becoming *Split's* subrecord instead) when it doesn't.
 * Content Signature (declared Type 2, wire `-2`, TagDrop-scoped), present only when signed, nests as Media
 * Payload's own subrecord, so `signature`/`signer_pubkey` travel once per payload regardless
 * of how many codes it spans. [decode]/[decodeRaw] return a [TagDropScan]; feed each
 * [ScannedRecord] to [SectorAssembler] to reassemble and parse the payload it belongs to.
 *
 * Paper (SPEC §3.3-§3.4) is a flat Preview/Body pair (Types 3/4).
 *
 * Navigation links (NOT encoding URIs, NOT put in QR codes):
 *   tagdrop://<domain-or-@rootHash-hex>/<slug>  — see TagDropLinkResolver for the grammar
 *   Disambiguated by "//": Base41's alphabet has no '/' at all, so an encoding
 *   URI can never have "//" right after the scheme.
 *
 * CBOR map integer keys — see SPEC.md §3.1-§3.4 for the authoritative tables. Each Record
 * Type has its own independent key namespace; `files[]`/`related[]` sub-maps (inside
 * Paper-Body) and the encrypted override map (inside Media Payload's `content`) each have
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

    // ── Resource-exhaustion guards (SPEC §8) ────────────────────────────────
    // A scanned code is untrusted input — nothing stops a hostile QR/NFC tag from declaring a
    // Split Wrapper `count`/`total_bytes` far beyond any real payload, or from Compress-wrapping
    // a small DEFLATE stream engineered to inflate far past its own size (a "decompression bomb"
    // — DEFLATE's own single-pass ceiling is roughly 1032:1, since there's no recursive
    // container structure here the way nested ZIP archives have). Both [decompress] and
    // [SectorAssembler]'s Split reassembly enforce these caps before allocating anything sized by
    // untrusted values, so a malicious scan fails closed (a bounded, catchable error) instead of
    // exhausting memory. All three are generous relative to any real TagDrop payload (SPEC §4.3
    // "no more practical size limit" describes hundreds of files, not gigabytes) and cheap to
    // check relative to the allocations they're guarding.

    /** Hard ceiling on [decompress]'s output size — DEFLATE's own amplification (≈1032:1) means a
     *  compressed blob far smaller than this can still inflate past it if unchecked. Checked
     *  incrementally as bytes are produced, not after allocating the full output. */
    const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

    /** Hard ceiling on a Split Wrapper's declared `total_bytes` (SPEC §5.1) — bounds the
     *  reassembled-buffer allocation in [SectorAssembler] directly, independent of and before any
     *  decompression is attempted on the result. */
    const val MAX_SPLIT_TOTAL_BYTES = 16 * 1024 * 1024

    /** Hard ceiling on a Split Wrapper's declared `count` (SPEC §5.1) — bounds the fragment-
     *  tracking map size and the `missing`-index scan's cost in [SectorAssembler], which would
     *  otherwise scale with an attacker-declared value on every fragment received. */
    const val MAX_SPLIT_FRAGMENT_COUNT = 4096

    /** NDEF MIME type for a code's raw CBOR Record Sequence on an NFC tag (SPEC §12/§13). */
    const val NFC_MIME_TYPE = "application/vnd.tagdrop"

    private const val SCHEME          = "tagdrop:"
    private const val NAV_LINK_PREFIX = "tagdrop://"

    /**
     * SPEC §2.1a: TagDrop's namespace token — `SHA-256("io.github.mofosyne.tagdrop")[0:4]`.
     * Passed to [MiniCbor.encodeRootBundle] as the full value (`h'89d414e0'`, 5 bytes on the
     * wire) as the root Bundle's own leading element — as of SPEC.md v16 this is EVERY TagDrop
     * code's shape, including the former key-only "no Bundle indirection" case (SPEC §9; see
     * [createKeyCodeSector]), since a Record can no longer both introduce a namespace and be
     * scoped by it in the same array. Every TagDrop-scoped Record nested underneath then
     * resolves back to this value purely via its own **negative** typeId (the negation of one
     * of the declared magnitudes below) and carries no namespace item of its own at all — `h''`
     * (the old "cascade" marker) no longer exists on the wire; sign alone is the scoping signal
     * now, at zero extra bytes either way (see [MiniCbor.encodeRecord]/
     * [MiniCbor.decodeRecordPrefix]). As of SPEC.md v14 this declaration is mandatory on *every*
     * carrier (`tagdrop:` URI, NFC NDEF, byte-mode QR/JABCode alike) — versions 6-13's
     * carrier-implied namespace (never actually on the wire on the first two) no longer has any
     * spec-level backing (§2.1a).
     */
    val TAGDROP_NAMESPACE: ByteArray = byteArrayOf(0x89.toByte(), 0xD4.toByte(), 0x14.toByte(), 0xE0.toByte())

    /** On-wire byte cost of declaring [TAGDROP_NAMESPACE] explicitly as a CBOR byte string —
     *  1-byte header + 4-byte value (SPEC §2.1a/§14's "+5 bytes per code"). */
    const val NAMESPACE_DECLARATION_BYTES = 5

    private fun isTagDropNamespace(namespace: ByteArray?): Boolean =
        namespace != null && namespace.contentEquals(TAGDROP_NAMESPACE)

    // ── QDEF magic framing (QDEF-SPEC.md §2, §3.5) ────────────────
    // 4-byte "QDEF" magic ahead of the Record Sequence bytes. As of SPEC.md v17, only
    // `tagdrop:` URI skips it (the scheme itself is still the dispatch signal) — every other
    // carrier, including NFC NDEF as of this version, always includes it: QDEF dropped NFC/NDEF
    // from its own scope entirely, taking the carrier-specific "NDEF's own MIME type already
    // disambiguates it" exemption with it, so TagDrop no longer carves one out here either. The
    // namespace declaration itself is separate overhead, unaffected by this — every carrier's
    // root array carries it the same way regardless of magic (MiniCbor.decodeRootBundle/
    // encodeRootBundle handle it uniformly).
    private val QDEF_MAGIC = byteArrayOf(0x51, 0x44, 0x45, 0x46)  // "QDEF" (4 bytes)

    /** If [bytes] starts with the 4-byte QDEF magic header, strips it, returning the plain
     *  Record Sequence bytes (a namespaced root array, decodable directly by
     *  [MiniCbor.decodeRootBundle] exactly like any other carrier's); otherwise returns [bytes]
     *  unchanged. Tolerant either way, so it's safe to call on any carrier's payload regardless
     *  of whether that carrier's framing includes the magic prefix. */
    fun stripQdefFraming(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || !QDEF_MAGIC.contentEquals(bytes.copyOfRange(0, 4))) return bytes
        return bytes.copyOfRange(4, bytes.size)
    }

    /** Prepends the 4-byte QDEF magic header ahead of [bytes] (a Record Sequence — a namespaced
     *  root array). As of SPEC.md v17, every carrier except `tagdrop:` URI includes this —
     *  currently used for NFC NDEF's MIME-record payload ([com.github.mofosyne.tagdrop.util.NfcUtils.buildNdefMessage]); byte-mode
     *  QR encoding isn't implemented in this app yet. */
    fun addQdefFraming(bytes: ByteArray): ByteArray = QDEF_MAGIC + bytes

    // ── QDEF Record Type IDs (SPEC.md v14 §2.1) — TagDrop's four are small sequential DECLARED
    // magnitudes, namespace-scoped (§2.1a) — parity carries no meaning as of v14. As of SPEC.md
    // v16, every call site that builds one of these four Records on the wire passes the
    // NEGATION of the constant below (e.g. `-TYPE_CONTENT_EXTENSION`) to MiniCbor.encodeRecord,
    // and every decode-side comparison against a DecodedRecord.typeId must do the same — sign is
    // now the namespace-scoping signal (§2.1a), so these constants themselves stay the positive
    // declared magnitude (matching registry.rec) but are never passed bare to encodeRecord or
    // compared bare against a decoded typeId. Split/Compress/Media Preview/Media Payload are
    // QDEF's own small well-known, globally-interpreted standard types (wire-encoded
    // non-negative, unaffected by v16); TagDrop's own numbers happen to coincide with some of
    // them (expected and safe, not a collision — the two sets resolve in different spaces, and
    // as of v16 are additionally disjoint by CBOR major type, see §2.1's note) ──
    const val TYPE_CONTENT_EXTENSION = 1
    const val TYPE_CONTENT_SIGNATURE = 2
    const val TYPE_PAPER_PREVIEW     = 3
    const val TYPE_PAPER_BODY        = 4

    private const val TYPE_SPLIT         = 1
    private const val TYPE_MEDIA_PAYLOAD = 3
    private const val TYPE_COMPRESS      = 4
    private const val TYPE_MEDIA_PREVIEW = 7

    // ── Content Extension field keys (SPEC.md §3.1) ────────────────────────────
    private const val EK_HINT         = 3
    private const val EK_DESCRIPTION  = 11
    private const val EK_COLLECTION_ID    = 13
    private const val EK_COLLECTION_LABEL = 15
    private const val EK_COLLECTION_TAG   = 17
    private const val EK_ICON         = 19
    private const val EK_PIXEL_ART    = 21
    private const val EK_LAT          = 23
    private const val EK_LNG          = 25
    private const val EK_RADIUS_M     = 27
    private const val EK_PREFER_DECLARED_LOCATION = 29
    private const val EK_LOCATION_LABEL = 31
    private const val EK_KEY_MATERIAL = 33
    private const val EK_RETAIN_KEY   = 35
    private const val EK_ENCRYPTION   = 37
    private const val EK_KDF_ALG      = 39
    private const val EK_KDF_SALT     = 41
    private const val EK_KDF_ITERS    = 43
    private const val EK_SIGNATURE_ALGORITHM = 45
    private const val EK_SIGNER_ID    = 47
    private const val EK_SIGNER_LABEL = 49
    private const val EK_IN_REPLY_TO  = 51
    private const val EK_CREATED_AT   = 53
    /** Back to this Type-specific key as of SPEC.md v14 — was QDEF Common Field Key `-13` in
     *  versions 11-13; the shared registry shrank to just `-1`/`-3` and no longer defines it. */
    private const val EK_SOURCE_URL   = 55

    // ── Media Preview field keys (QDEF standard Type 7, SPEC.md §3.1a) ────────
    // Back to Type-specific keys as of SPEC.md v14 — contentHash/filename/label were QDEF
    // Common Field Keys -11/-15/-7 in versions 11-13; the shared registry shrank to just
    // -1/-3 and no longer defines any of them. Renumbered again in v15: QDEF's positional
    // payload slot was removed from the grammar entirely in favor of reserved map key `0`
    // (QDEF-SPEC.md §3.6) — Media Preview never had a payload of its own, but key `0`'s
    // reservation means it can no longer use it either, so every field here shifts up two
    // keys (key `0` is unused on this Type entirely as of v15).
    private const val MPK_MEDIA_TYPE   = 2
    private const val MPK_CONTENT_HASH = 3
    private const val MPK_FILENAME     = 5
    private const val MPK_LABEL        = 7

    // ── Media Payload field keys (QDEF standard Type 3, SPEC.md §3.1a) ─────────
    // As of SPEC.md v15, `content` moves from the array's separate positional payload slot
    // into an ordinary field at reserved map key `0` (QDEF-SPEC.md §3.6) — `mediaType`, which
    // used to sit at key `0` itself (v11-v14), is displaced to key `1` to make room.
    private const val MYK_CONTENT    = 0
    private const val MYK_MEDIA_TYPE = 1

    // ── Content Signature field keys (TagDrop-scoped Type 2, SPEC.md §3.1a) ────
    private const val CSK_SIGNATURE     = 3
    private const val CSK_SIGNER_PUBKEY = 5

    // Media Payload's `content` may be a hidden encrypted override map (SPEC §9) — its own
    // independent local key namespace, unrelated to any Record's own numbering.
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
    private const val PPK_TITLE       = 43
    private const val PPK_DESCRIPTION = 45
    private const val PPK_KEY_MATERIAL = 47
    private const val PPK_RETAIN_KEY  = 49
    /** Back to this Type-specific key as of SPEC.md v14 — same reversion as Content Extension's
     *  [EK_SOURCE_URL], above. */
    private const val PPK_SOURCE_URL  = 41

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

    // QDEF Split Wrapper (Type 1) field keys (QDEF-SPEC.md §4.1). Renumbered in SPEC.md v15:
    // fragment data moves off its old key 6 onto reserved map key `0` (QDEF-SPEC.md §3.6),
    // the same "singular value" slot Compress Wrapper/Media Payload also adopt — group_id/
    // index/count shift up two keys each to make room (2/4/6); total_bytes/parity_scheme are
    // untouched (7/9), since neither collided with anything vacating a slot.
    private const val SK_DATA     = 0
    private const val SK_GROUP_ID = 2
    private const val SK_INDEX    = 4
    private const val SK_COUNT    = 6
    private const val SK_TOTAL    = 7
    private const val SK_PARITY   = 9

    // Compress Wrapper (Type 4) field keys (QDEF-SPEC.md §4.1). As of SPEC.md v15, its one
    // value (the deflated bytes) is an ordinary field at reserved map key `0` (QDEF-SPEC.md
    // §3.6) rather than a separate positional payload item — no map at all, pre-v15.
    private const val CK_PAYLOAD = 0

    // SPEC §10 signature-field key sets, per Record Type — what the placeholder-then-strip
    // discipline strips before hashing (contentSignedMessageHash/paperSignedMessageHash).
    private val CONTENT_EXTENSION_SIGNATURE_KEYS = setOf(EK_SIGNATURE_ALGORITHM, EK_SIGNER_ID, EK_SIGNER_LABEL)
    private val PAPER_PREVIEW_SIGNATURE_KEYS = setOf(PPK_ROOT_HASH, PPK_SIGNATURE_ALGORITHM, PPK_SIGNER_ID, PPK_SIGNER_LABEL)
    private val PAPER_BODY_SIGNATURE_KEYS    = setOf(PBK_SIGNATURE, PBK_SIGNER_PUBKEY)

    // Known-key sets for SPEC §2.2's even/odd criticality rule (see [checkRecordKeys]).
    private val KNOWN_CONTENT_EXTENSION = setOf(
        EK_HINT, EK_DESCRIPTION, EK_COLLECTION_ID, EK_COLLECTION_LABEL, EK_COLLECTION_TAG, EK_ICON,
        EK_PIXEL_ART, EK_LAT, EK_LNG, EK_RADIUS_M, EK_PREFER_DECLARED_LOCATION, EK_LOCATION_LABEL,
        EK_KEY_MATERIAL, EK_RETAIN_KEY, EK_ENCRYPTION, EK_KDF_ALG, EK_KDF_SALT, EK_KDF_ITERS,
        EK_SIGNATURE_ALGORITHM, EK_SIGNER_ID, EK_SIGNER_LABEL, EK_IN_REPLY_TO, EK_CREATED_AT, EK_SOURCE_URL)
    private val KNOWN_MEDIA_PREVIEW = setOf(MPK_MEDIA_TYPE, MPK_CONTENT_HASH, MPK_FILENAME, MPK_LABEL)
    private val KNOWN_MEDIA_PAYLOAD = setOf(MYK_CONTENT, MYK_MEDIA_TYPE)
    private val KNOWN_CONTENT_SIGNATURE = setOf(CSK_SIGNATURE, CSK_SIGNER_PUBKEY)
    private val KNOWN_PAPER_PREVIEW = setOf(
        PPK_ROOT_HASH, PPK_HINT, PPK_SET, PPK_SLUG, PPK_DOMAIN, PPK_STEP,
        PPK_COLLECTION_ID, PPK_COLLECTION_LABEL, PPK_COLLECTION_TAG, PPK_ICON,
        PPK_LAT, PPK_LNG, PPK_RADIUS_M, PPK_PREFER_DECLARED_LOCATION, PPK_LOCATION_LABEL,
        PPK_SIGNATURE_ALGORITHM, PPK_SIGNER_ID, PPK_SIGNER_LABEL, PPK_IN_REPLY_TO, PPK_CREATED_AT,
        PPK_SOURCE_URL, PPK_TITLE, PPK_DESCRIPTION, PPK_KEY_MATERIAL, PPK_RETAIN_KEY)
    private val KNOWN_PAPER_BODY = setOf(PBK_FILES, PBK_RELATED, PBK_SIGNATURE, PBK_SIGNER_PUBKEY)
    private val KNOWN_SPLIT = setOf(SK_GROUP_ID, SK_INDEX, SK_COUNT, SK_DATA, SK_TOTAL, SK_PARITY)
    private val KNOWN_COMPRESS = setOf(CK_PAYLOAD)

    const val KDF_NONE          = 0
    const val KDF_PBKDF2_SHA256 = 1

    private const val DEFAULT_KDF_ITERS = 100000

    // ── Content addressing (IPFS-inspired, SPEC §4.4) ─────────────────────────

    /** `contentHash` = SHA-256(uncompressed content)[0:8] — same bytes, same ID, everywhere. */
    fun contentId(content: ByteArray): ByteArray = sha256(content).copyOf(8)

    /**
     * 8 random bytes — `contentHash` for a Content code carrying a hidden override map (SPEC
     * §9), so the ID itself can't be used as a content-equality oracle against a known plaintext.
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

    // ── QDEF Records (QDEF-SPEC.md §3.1: array-wrapped [typeId, map, subrecord*]) ─────

    /** QDEF Compress Wrapper (Type 4, QDEF-SPEC.md §4.1) — DEFLATEs [bodyBytes] into reserved map key `0` (§3.1/§3.6, v15): `[4, {0: deflated_bytes}]`. */
    private fun compressWrap(bodyBytes: ByteArray): ByteArray =
        MiniCbor.encodeRecord(TYPE_COMPRESS, listOf(CK_PAYLOAD to compress(bodyBytes)))

    /**
     * Fragments [bytes] into [fragmentCount] QDEF Split Wrapper Records (Type 1, QDEF-SPEC.md
     * §4.1, SPEC §5) of `ceil(total/count)` bytes each (every fragment but the last the same
     * length), stamped with [groupId] (a content hash of [bytes], computed by the caller). If
     * [withParity], appends one more fragment at `index == count`: the byte-wise XOR of every
     * data fragment, each implicitly zero-padded to the widest — SPEC §5's single-loss
     * redundancy scheme. [extraSubrecords] (SPEC §3.1a) is attached to every fragment Record,
     * unwrapped and repeated — used to carry Content's Media Preview alongside a Split-wrapped
     * Media Payload; Paper has none, so it's empty by default.
     */
    private fun splitFragments(
        bytes: ByteArray, groupId: ByteArray, fragmentCount: Int, withParity: Boolean,
        extraSubrecords: List<ByteArray> = emptyList()
    ): List<ByteArray> {
        val total = bytes.size
        val chunkLen = (total + fragmentCount - 1) / fragmentCount
        val fragments = mutableListOf<ByteArray>()
        for (i in 0 until fragmentCount) {
            val start = minOf(i * chunkLen, total)
            val end = minOf(start + chunkLen, total)
            fragments.add(MiniCbor.encodeRecord(TYPE_SPLIT, listOf(
                SK_GROUP_ID to groupId, SK_INDEX to i, SK_COUNT to fragmentCount,
                SK_DATA to bytes.copyOfRange(start, end), SK_TOTAL to total,
                SK_PARITY to (1.takeIf { withParity })
            ), extraSubrecords))
        }
        if (withParity) {
            val parity = ByteArray(chunkLen)
            for (i in 0 until fragmentCount) {
                val start = minOf(i * chunkLen, total)
                val end = minOf(start + chunkLen, total)
                for (j in start until end) parity[j - start] = (parity[j - start].toInt() xor bytes[j].toInt()).toByte()
            }
            fragments.add(MiniCbor.encodeRecord(TYPE_SPLIT, listOf(
                SK_GROUP_ID to groupId, SK_INDEX to fragmentCount, SK_COUNT to fragmentCount,
                SK_DATA to parity, SK_TOTAL to total, SK_PARITY to 1
            ), extraSubrecords))
        }
        return fragments
    }

    /**
     * Builds the codes for one Paper payload: [preview] repeated on every code (SPEC §5.1),
     * plus either [body] directly (single code, if it and [preview] together fit within
     * [maxFragmentDataBytes]), or [body] Split-wrapped across as many codes as needed.
     * `group_id` is `SHA-256(body)[0:8]` (the wrapped — Compress-wrapped if [compressBody], not
     * the raw plain — bytes, SPEC §5.1), computed once and reused across every fragment of the
     * group. Content doesn't use this — its single-code/multi-code nesting differs (§3.1a), see
     * [createContentSectors].
     */
    private fun buildCodes(
        preview: ByteArray, body: ByteArray, compressBody: Boolean, withParity: Boolean, maxFragmentDataBytes: Int
    ): List<ByteArray> {
        val wrapped = if (compressBody) compressWrap(body) else body
        // Paper always has two top-level Records (Preview + Body/its wrapper) — the root
        // Bundle's own leading element declares TagDrop's namespace explicitly (§2.1a).
        val single = MiniCbor.encodeRootBundle(listOf(preview, wrapped), TAGDROP_NAMESPACE)
        if (maxFragmentDataBytes <= 0 || single.size <= maxFragmentDataBytes) return listOf(single)
        val fragmentCount = maxOf(1, (wrapped.size + maxFragmentDataBytes - 1) / maxFragmentDataBytes)
        if (fragmentCount <= 1) return listOf(single)
        val groupId = sha256(wrapped).copyOf(8)
        return splitFragments(wrapped, groupId, fragmentCount, withParity).map { MiniCbor.encodeRootBundle(listOf(preview, it), TAGDROP_NAMESPACE) }
    }

    // ── Content: build (SPEC §3.1/§3.1a) ────────────────────────────────────────

    /**
     * The result of building a Content payload's code(s): [codes] are the final wire bytes
     * (Split/Compress-wrapped as needed) to encode/render; [extensionRaw]/[mediaPreviewRaw]/
     * [mediaPayloadRaw] are the LOGICAL (unwrapped, unsplit, pre-wire-nesting) Record bytes —
     * not reconstructable from [codes] alone once Split/Compress-wrapped or nested — exposed
     * so a signer can hash them directly ([contentSignedMessageHash],
     * `data/signing/SigningIdentity.kt`) without re-decoding a code.
     */
    data class ContentBuild(
        val codes: List<ByteArray>,
        val extensionRaw: ByteArray,
        val mediaPreviewRaw: ByteArray,
        val mediaPayloadRaw: ByteArray,
        val cacheId: ByteArray?
    )

    /**
     * Builds the code(s) for a Content payload. A Content Extension Record (Type 1) is
     * repeated on every code; Media Preview (QDEF Type 7) is likewise repeated on every code
     * (nested as Split's subrecord in the multi-code case). Media Payload (QDEF Type 3,
     * optionally Compress- and Split-wrapped) carries `content` plus, if signed, a nested
     * Content Signature subrecord (Type 3) — nested inside Media Preview when the payload fits
     * on one code, or Split-wrapped when it doesn't (SPEC §3.1a, §5.1).
     *
     * [hint]/[filename]/[mimeType]/[rawContent] become the **clear** view — shown until a
     * hidden override map, if any, is unlocked (SPEC §9). They may be a cover story, a
     * decoy, or genuine unremarkable content.
     *
     * If [override] is given (with [encryptionKey], 32 bytes — see [generateKeyMaterial]),
     * the content slot IS the AES-256-GCM-encrypted override blob (SPEC §9) rather than
     * [rawContent]; `contentHash` becomes random (see [randomCacheId]), and `encryption` is set
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
     * [inReplyTo] is the `contentHash`/`root_hash` of a single parent this content is replying
     * to (SPEC §7, "Replies and threading") — omit for a new, unprompted message.
     *
     * [title] is an optional short subject/caption, kept separate from [hint]'s existing role —
     * carried on Media Preview's `label` field (SPEC §3.1a). [description] is an optional
     * content teaser or message body — e.g. a postcard's message when [rawContent] is spoken
     * for by an attachment instead (SPEC §7, "Postcards").
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
        // Compression is never applied to `rawContent` directly — an encrypted override blob is
        // already high-entropy (DEFLATE-ing it wastes a wrapper layer for nothing) and gets its
        // own inner compression instead (compressed before encryption, inside
        // encryptOverrideMap); a plain content slot is compressed, if requested, by
        // Compress-wrapping the whole Media Payload Record (QDEF Type 4), not by pre-compressing
        // the field's bytes here.
        val cacheId: ByteArray
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

        // Content Extension is TagDrop-scoped — as of SPEC.md v16 it resolves back to the root
        // Bundle's own namespace declaration (§2.1a) purely via its negative wire typeId
        // (-TYPE_CONTENT_EXTENSION), carrying no namespace item of its own at all (see
        // createKeyCodeSector for the key-only/lone-Record case, still Bundle-wrapped as of v16).
        val extensionRaw = MiniCbor.encodeRecord(-TYPE_CONTENT_EXTENSION, listOf(
            EK_HINT to hint, EK_DESCRIPTION to description,
            EK_COLLECTION_ID to collectionId, EK_COLLECTION_LABEL to collectionLabel, EK_COLLECTION_TAG to collectionTag,
            EK_ICON to icon, EK_PIXEL_ART to (true.takeIf { pixelArt }),
            EK_LAT to lat, EK_LNG to lng, EK_RADIUS_M to radiusM,
            EK_PREFER_DECLARED_LOCATION to (true.takeIf { preferDeclaredLocation }), EK_LOCATION_LABEL to locationLabel,
            EK_KEY_MATERIAL to keyMaterial, EK_RETAIN_KEY to (false.takeIf { keyMaterial != null && !retainKey }),
            EK_ENCRYPTION to (encryption.takeIf { it != ENCRYPTION_NONE }),
            EK_KDF_ALG to null, EK_KDF_SALT to null, EK_KDF_ITERS to null,
            EK_SIGNATURE_ALGORITHM to (signatureAlgorithm.takeIf { it != SIGNATURE_ALG_NONE }),
            EK_SIGNER_ID to signerId, EK_SIGNER_LABEL to signerLabel,
            EK_IN_REPLY_TO to inReplyTo, EK_CREATED_AT to createdAt, EK_SOURCE_URL to null
        ))

        // Media Preview is a QDEF standard/global Type — never carries a namespace item of its own.
        fun buildMediaPreview(subrecords: List<ByteArray> = emptyList()) = MiniCbor.encodeRecord(TYPE_MEDIA_PREVIEW, listOf(
            MPK_MEDIA_TYPE to mimeType,
            MPK_CONTENT_HASH to (byteArrayOf(0x12) + cacheId),
            MPK_FILENAME to filename, MPK_LABEL to title
        ), subrecords)
        // The LOGICAL (bare) Media Preview bytes — for hashing/return, and reused unwrapped as
        // Split's own repeated subrecord in the multi-code case (§3.1a).
        val mediaPreviewRaw = buildMediaPreview()

        // Content Signature is TagDrop-scoped, always nested as Media Payload's own subrecord —
        // never a lone top-level Record — so it always resolves back to the ambient namespace via
        // its own negative wire typeId too (no namespace item of its own).
        val contentSignatureRecord = if (signature != null) {
            MiniCbor.encodeRecord(-TYPE_CONTENT_SIGNATURE, listOf(CSK_SIGNATURE to signature, CSK_SIGNER_PUBKEY to signerPubkey))
        } else null
        // Media Payload is a QDEF standard/global Type — no namespace item of its own. `content`
        // lives at reserved map key `0` (§3.1/§3.6, v15) rather than a separate payload item.
        val mediaPayloadRaw = MiniCbor.encodeRecord(
            TYPE_MEDIA_PAYLOAD, listOf(MYK_CONTENT to contentSlot, MYK_MEDIA_TYPE to mimeType),
            if (contentSignatureRecord != null) listOf(contentSignatureRecord) else emptyList()
        )
        // An encrypted override blob is already high-entropy — Compress-wrapping it on top would
        // waste a wrapper layer for nothing (DEFLATE doesn't shrink it).
        val mediaPayloadForWire = if (compress && override == null) compressWrap(mediaPayloadRaw) else mediaPayloadRaw

        val totalBytes = mediaPayloadForWire.size
        if (totalBytes <= maxSectorDataBytes) {
            // Single code: Media Payload (or its Compress Wrapper) nests as Media Preview's own
            // subrecord (§3.1a) — not a separate sibling Record.
            val wireMediaPreview = buildMediaPreview(listOf(mediaPayloadForWire))
            // Two top-level Records (Content Extension + Media Preview): the root Bundle's own
            // leading element declares TagDrop's namespace explicitly (§2.1a) — mandatory on
            // every carrier as of SPEC.md v14.
            val code = MiniCbor.encodeRootBundle(listOf(extensionRaw, wireMediaPreview), TAGDROP_NAMESPACE)
            return ContentBuild(listOf(code), extensionRaw, mediaPreviewRaw, mediaPayloadRaw, cacheId)
        }

        val fragmentCount = (totalBytes + maxSectorDataBytes - 1) / maxSectorDataBytes
        val groupId = sha256(mediaPayloadForWire).copyOf(8)
        // Media Preview becomes Split's own repeated subrecord in the multi-code case (§3.1a) —
        // Content Extension stays a separate top-level Record, repeated per code exactly as in
        // the single-code case above.
        val fragments = splitFragments(mediaPayloadForWire, groupId, fragmentCount, withParity, listOf(mediaPreviewRaw))
        val codes = fragments.map { MiniCbor.encodeRootBundle(listOf(extensionRaw, it), TAGDROP_NAMESPACE) }
        return ContentBuild(codes, extensionRaw, mediaPreviewRaw, mediaPayloadRaw, cacheId)
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
     * Builds a single "key-only" code (SPEC §9): a Content Extension Record carrying
     * [keyMaterial] for other content, with no Media Preview/Payload at all.
     */
    fun createKeyCodeSector(keyMaterial: ByteArray, retainKey: Boolean = true, hint: String? = null): ByteArray {
        require(keyMaterial.size == AES_KEY_BYTES) { "key_material must be $AES_KEY_BYTES bytes" }
        // As of SPEC.md v16, a Record can no longer both introduce a namespace and be scoped by
        // it in the same array — so even this lone-Record/key-only code (SPEC §9) is now a root
        // Bundle wrapping one subrecord, the same uniform shape every other TagDrop code has
        // (the version-13-15 "no Bundle indirection" exception is gone). The Content Extension
        // Record itself carries no namespace item of its own — its negative wire typeId
        // (-TYPE_CONTENT_EXTENSION) resolves it back to the Bundle's own declaration.
        val extension = MiniCbor.encodeRecord(-TYPE_CONTENT_EXTENSION, listOf(
            EK_HINT to hint, EK_KEY_MATERIAL to keyMaterial, EK_RETAIN_KEY to (false.takeIf { !retainKey })
        ))
        return MiniCbor.encodeRootBundle(listOf(extension), TAGDROP_NAMESPACE)
    }

    /**
     * SPEC §10's Content signed-message hash: `SHA-256(MediaPreview' || MediaPayload'' ||
     * Extension')`, where `MediaPreview'` is Media Preview's canonical bytes (nothing to strip
     * — it never carries a signature field), `MediaPayload''` is Media Payload's canonical bytes
     * with its own Content Signature subrecord (Type 3) omitted entirely if present, and
     * `Extension'` is Content Extension's canonical bytes with [CONTENT_EXTENSION_SIGNATURE_KEYS]
     * stripped — the hash an *unsigned* build would have produced, computed from the **logical**
     * (already Compress-unwrapped/un-nested, if applicable) Record bytes. [mediaPreviewRaw]/
     * [mediaPayloadRaw] null (a key-only code, SPEC §9: Extension only, nothing else) contributes
     * empty bytes for that term, not a skipped one. Used both by the encode-side "hash the
     * placeholder build, then sign" step (`data/signing/SigningIdentity.kt`) and the decode-side
     * verify step (`data/signing/SignatureVerifier.kt`).
     */
    fun contentSignedMessageHash(extensionRaw: ByteArray, mediaPreviewRaw: ByteArray?, mediaPayloadRaw: ByteArray?): ByteArray {
        val unsignedExtension = MiniCbor.stripKeys(extensionRaw, CONTENT_EXTENSION_SIGNATURE_KEYS)
        val unsignedMediaPayload = mediaPayloadRaw?.let { MiniCbor.stripSubrecordType(it, -TYPE_CONTENT_SIGNATURE) } ?: ByteArray(0)
        return sha256((mediaPreviewRaw ?: ByteArray(0)) + unsignedMediaPayload + unsignedExtension)
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

        // Paper-Body is TagDrop-scoped — as of SPEC.md v16 it resolves back to the ambient
        // namespace purely via its negative wire typeId (§2.1a), carrying no namespace item of
        // its own, whether it's the root's direct second child or nested inside a Compress/Split
        // Wrapper (a global Type that passes the ambient namespace through transparently
        // regardless of nesting depth).
        fun buildBody(sig: ByteArray?, pubkey: ByteArray?) = MiniCbor.encodeRecord(-TYPE_PAPER_BODY, listOf(
            PBK_FILES to filesCbor, PBK_RELATED to relatedCbor,
            PBK_SIGNATURE to sig, PBK_SIGNER_PUBKEY to pubkey
        ))
        // Paper-Preview is always the root Bundle's direct first child (paired with Body or its
        // wrapper) — resolves the same way.
        fun buildPreview(rootHash: ByteArray?, sigAlg: Int?, sId: ByteArray?, sLabel: String?) = MiniCbor.encodeRecord(-TYPE_PAPER_PREVIEW, listOf(
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
     * NDEF, byte-mode QR with optional QDEF framing). Returns null for an unrecognized
     * leading Type ID or malformed sequence.
     */
    fun decodeRaw(bytes: ByteArray): TagDropScan? =
        recordScanResult(stripQdefFraming(bytes))?.let { TagDropScan.RecordScan(it, rawWireBytes = bytes) }

    /** SPEC §2.2 even/odd criticality: an unrecognized EVEN key means this decoder can't
     *  safely use the Record — reject it; an unrecognized ODD key is optional and ignored. */
    private fun checkRecordKeys(record: Map<Int, Any>, known: Set<Int>): Boolean =
        record.keys.all { it in known || it % 2 != 0 }

    /**
     * Decodes a new-format code (SPEC.md v9 §2, §5.1). Paper: a Paper-Preview Record,
     * optionally followed by one more Record — the Body itself, a Compress Wrapper around it,
     * or one Split fragment of it (unchanged from before v9). Content (§3.1/§3.1a): a Content
     * Extension Record, optionally followed by one more top-level Record — either Media Preview
     * (single-code case, with Media Payload nested as its own subrecord) or Split (multi-code
     * case, with Media Preview nested as *its* subrecord instead). Either way, Media Preview's
     * own fields are extracted here so a single scan is self-identifying regardless of
     * reassembly progress (§5.1).
     */
    @Suppress("UNCHECKED_CAST")
    private fun recordScanResult(bytes: ByteArray): ScannedRecord? {
        // QDEF-SPEC.md's self-delimited root (§2/§3.1): the whole scanned payload is exactly one
        // definite-length CBOR array, so decodeRootBundle's own array-header length is what
        // bounds it — bytes past that length are provably outside the container and never
        // inspected here, satisfying SPEC §9's deniability requirement structurally rather than
        // by this function manually declining to look further (see MiniCbor.decodeRootBundle's
        // own doc comment).
        val records = MiniCbor.decodeRootBundle(bytes) ?: return null
        if (records.isEmpty() || records.size > 2) return null
        val first = records[0]
        // SPEC §2.1a's actual security-relevant check: TagDrop's small Type IDs are, as of
        // SPEC.md v14, literally the same integers QDEF's own global standard Types use — a
        // Record only means "Content Extension"/"Paper-Preview" if it resolved to TagDrop's own
        // namespace on the wire (never assumed from carrier context). Anything else (no
        // namespace resolved, or a different one) isn't a TagDrop Record at all, whatever its
        // typeId happens to number-match.
        if (!isTagDropNamespace(first.namespace)) return null
        val second = records.getOrNull(1)
        // As of SPEC.md v16, TagDrop's own scoped Types wire-encode NEGATIVE (§2.1a) — compare
        // against the negated declared magnitude, not the bare positive constant.
        return when (first.typeId) {
            -TYPE_CONTENT_EXTENSION -> contentScanResult(first, second)
            -TYPE_PAPER_PREVIEW -> paperScanResult(first, second)
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun contentScanResult(first: MiniCbor.DecodedRecord, second: MiniCbor.DecodedRecord?): ScannedRecord.Content? {
        if (!checkRecordKeys(first.record, KNOWN_CONTENT_EXTENSION)) return null
        if (second == null) {
            // Key-only code (SPEC §9): Extension only, no Media Preview/Payload at all.
            return ScannedRecord.Content(first.raw, first.record, null, null, null, null, null)
        }
        if (second.typeId == TYPE_MEDIA_PREVIEW) {
            // Single-code case: Media Payload is Media Preview's own (sole) subrecord — either
            // directly, or Compress-wrapped (unwrapMediaPayload handles both downstream). Taken
            // positionally, not by typeId: the size==1 check makes position 0 unambiguous, and
            // deliberately deferring "is this actually Media Payload/Compress" to unwrap time
            // (rather than rejecting here) matches SPEC §5.1 — scan time only validates the field
            // map and subrecord count, nothing type-specific.
            if (!checkRecordKeys(second.record, KNOWN_MEDIA_PREVIEW) || second.subrecords.size != 1) return null
            val mediaPreviewRaw = MiniCbor.stripAllSubrecords(second.raw)
            return ScannedRecord.Content(first.raw, first.record, second.record, mediaPreviewRaw, null, second.subrecords[0].raw, second.raw)
        }
        if (second.typeId == TYPE_SPLIT && second.namespace == null) {
            // Multi-code case: Media Preview is Split's own subrecord instead. Split Wrapper's
            // declared magnitude (1) happens to numerically match Content Extension's own (also
            // 1, §2.1's note) — but as of SPEC.md v16 the two are disjoint CBOR values by sign
            // (Split Wrapper always wire-encodes +1/global, Content Extension always -1/scoped),
            // so `second.typeId == TYPE_SPLIT` alone already can't match a genuine Content
            // Extension; `&& second.namespace == null` stays as harmless defense-in-depth
            // (provably always true here, same reasoning as unwrapMediaPayload/unwrapPaperBody's
            // Compress Wrapper checks).
            if (!checkRecordKeys(second.record, KNOWN_SPLIT)) return null
            val mediaPreviewSub = second.subrecords.find { it.typeId == TYPE_MEDIA_PREVIEW } ?: return null
            if (!checkRecordKeys(mediaPreviewSub.record, KNOWN_MEDIA_PREVIEW)) return null
            return ScannedRecord.Content(first.raw, first.record, mediaPreviewSub.record, mediaPreviewSub.raw, second.record, null, second.raw)
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun paperScanResult(first: MiniCbor.DecodedRecord, second: MiniCbor.DecodedRecord?): ScannedRecord.Paper? {
        if (!checkRecordKeys(first.record, KNOWN_PAPER_PREVIEW)) return null
        return ScannedRecord.Paper(first.raw, first.record, second?.typeId, second?.raw, second?.record)
    }

    /** Outcome of unwrapping a (possibly Compress-wrapped) Media Payload byte sequence. */
    private data class UnwrappedMediaPayload(val body: Map<Int, Any>, val content: ByteArray, val bodyRaw: ByteArray, val contentSignature: Map<Int, Any>?)

    /**
     * Unwraps a (possibly Compress-wrapped) Media Payload byte sequence into its own
     * `(record, raw, contentSignature)` — [UnwrappedMediaPayload.bodyRaw] being the LOGICAL
     * Media Payload Record bytes SPEC §10's signature formula covers (i.e. after any Compress
     * unwrap, WITH its own Content Signature subrecord, if any, still present — the caller
     * strips that separately when hashing), [UnwrappedMediaPayload.contentSignature] the
     * decoded Content Signature subrecord if present — or null if malformed.
     */
    @Suppress("UNCHECKED_CAST")
    private fun unwrapMediaPayload(bodyWireBytes: ByteArray): UnwrappedMediaPayload? {
        // bodyWireBytes is always reached only after a TagDrop-namespaced Content Extension has
        // already been confirmed (recordScanResult's own check) — Media Payload/Compress Wrapper
        // are QDEF global Types with no namespace item of their own, but per §2.1a they still
        // relay whatever ambient value they received on to their own subrecords (Content
        // Signature); seed that ambient explicitly here since this is a fresh top-level decode
        // of just these bytes, with no surrounding root Bundle to have threaded it through.
        var cur = MiniCbor.decodeRecordPrefix(bodyWireBytes, TAGDROP_NAMESPACE) ?: return null
        // Compress Wrapper's declared magnitude (4) happens to numerically match Paper-Body's own
        // (also 4, §2.1's note) — but as of SPEC.md v16 the two are different CBOR values by sign
        // alone (Compress Wrapper always wire-encodes +4/global; Paper-Body always wire-encodes
        // -4/scoped), so `cur.typeId == TYPE_COMPRESS` alone can never match a genuine Paper-Body.
        // `&& cur.namespace == null` is kept as harmless defense-in-depth (it's now provably
        // always true whenever the typeId check passes, since a non-negative typeId always
        // resolves to namespace == null per decodeRecordPrefix) rather than removed, matching the
        // equivalent check in unwrapPaperBody below.
        if (cur.typeId == TYPE_COMPRESS && cur.namespace == null) {
            if (!checkRecordKeys(cur.record, KNOWN_COMPRESS)) return null
            val payload = cur.record.bytesOrNull(CK_PAYLOAD) ?: return null
            val inflated = runCatching { decompress(payload) }.getOrNull() ?: return null
            cur = MiniCbor.decodeRecordPrefix(inflated, TAGDROP_NAMESPACE) ?: return null
        }
        // Media Payload's declared magnitude (3) happens to numerically match Paper-Preview's own
        // (also 3) — same v16 sign-disjointness reasoning as above: Media Payload always
        // wire-encodes +3/global, so `cur.typeId != TYPE_MEDIA_PAYLOAD` alone already rules out a
        // genuine Paper-Preview here. `cur.namespace != null` is kept as the same harmless
        // defense-in-depth as the Compress Wrapper check above.
        if (cur.typeId != TYPE_MEDIA_PAYLOAD || cur.namespace != null || !checkRecordKeys(cur.record, KNOWN_MEDIA_PAYLOAD)) return null
        var contentSignature: Map<Int, Any>? = null
        // Content Signature is TagDrop-scoped (§2.1a) and wire-encodes NEGATIVE as of v16 — only
        // accept a subrecord that actually resolved to TagDrop's namespace as a genuine one, same
        // reasoning as recordScanResult's own check.
        val cs = cur.subrecords.find { it.typeId == -TYPE_CONTENT_SIGNATURE && isTagDropNamespace(it.namespace) }
        if (cs != null) {
            if (!checkRecordKeys(cs.record, KNOWN_CONTENT_SIGNATURE)) return null
            contentSignature = cs.record
        }
        // `content` lives at reserved map key `0` (§3.1/§3.6, v15) rather than a separate
        // payload item — mandatory whenever a Media Payload Record exists, but default to
        // empty rather than fail closed here, matching this function's existing leniency.
        return UnwrappedMediaPayload(cur.record, cur.record.bytesOrNull(MYK_CONTENT) ?: ByteArray(0), cur.raw, contentSignature)
    }

    /**
     * Unwraps a (possibly Compress-wrapped) Paper-Body byte sequence into its own
     * `(record, raw)` — `raw` being the LOGICAL Record bytes SPEC §10's signature formula
     * covers, i.e. after any Compress unwrap — or null if malformed.
     */
    private fun unwrapPaperBody(bodyWireBytes: ByteArray): Pair<Map<Int, Any>, ByteArray>? {
        // Same reasoning as unwrapMediaPayload above: bodyWireBytes is only ever reached from a
        // context that already confirmed a TagDrop-namespaced Paper-Preview, so it's correct
        // (and necessary, since this is a fresh top-level decode) to seed the ambient namespace
        // explicitly rather than default to none.
        var cur = MiniCbor.decodeRecordPrefix(bodyWireBytes, TAGDROP_NAMESPACE) ?: return null
        // See unwrapMediaPayload's matching comment above — Compress Wrapper's declared magnitude
        // (4) is the same integer as Paper-Body's own, but as of v16 they're disjoint CBOR values
        // by sign (Compress Wrapper +4, Paper-Body -4), so `cur.typeId == TYPE_COMPRESS` alone
        // already can't match a genuine Paper-Body; `&& cur.namespace == null` stays as the same
        // harmless defense-in-depth.
        if (cur.typeId == TYPE_COMPRESS && cur.namespace == null) {
            if (!checkRecordKeys(cur.record, KNOWN_COMPRESS)) return null
            val payload = cur.record.bytesOrNull(CK_PAYLOAD) ?: return null
            val inflated = runCatching { decompress(payload) }.getOrNull() ?: return null
            cur = MiniCbor.decodeRecordPrefix(inflated, TAGDROP_NAMESPACE) ?: return null
        }
        // Paper-Body is TagDrop-scoped (§2.1a) and wire-encodes NEGATIVE as of v16 — its own
        // resolved namespace must actually be TagDrop's, same check as recordScanResult's.
        if (cur.typeId != -TYPE_PAPER_BODY || !isTagDropNamespace(cur.namespace) || !checkRecordKeys(cur.record, KNOWN_PAPER_BODY)) return null
        return cur.record to cur.raw
    }

    // ── Reassembly primitives used by SectorAssembler ───────────────────────────

    /** Outcome of parsing a Content payload's reassembled Media Payload (SPEC §5 steps 3-5). */
    sealed class ContentParse {
        /**
         * Parsed and (if reassembled from a Split group) `group_id`-verified. [mediaPayloadRaw]
         * is the LOGICAL (Compress-unwrapped, if applicable) Media Payload Record bytes SPEC
         * §10's signature formula covers — null for a key-only code (no Media Payload at all).
         */
        data class Ok(val content: TagDropPayload.Content, val mediaPayloadRaw: ByteArray?) : ContentParse()
        /** `group_id` was checked and did not match — incomplete or corrupt assembly. */
        object HashMismatch : ContentParse()
        /** The bytes aren't a well-formed Content Extension+Media Payload set. */
        object Malformed : ContentParse()
    }

    /**
     * Resolves a scanned Content [record] whose Media Payload (if any) is already fully in hand
     * — either [record]'s own single-code wire bytes directly, or externally reassembled Split-
     * fragment data (multi-code payload, passed as [reassembledMediaPayloadWireBytes]). Both
     * null means a key-only code (SPEC §9): Extension only, empty content.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseContentStream(record: ScannedRecord.Content, reassembledMediaPayloadWireBytes: ByteArray? = null): ContentParse {
        val bodyWireBytes = reassembledMediaPayloadWireBytes ?: record.mediaPayloadWireRaw
        if (bodyWireBytes == null) {
            return ContentParse.Ok(contentFromParts(record.extension, null, ByteArray(0), null), mediaPayloadRaw = null)
        }
        val unwrapped = unwrapMediaPayload(bodyWireBytes) ?: return ContentParse.Malformed
        return ContentParse.Ok(
            contentFromParts(record.extension, record.mediaPreview, unwrapped.content, unwrapped.contentSignature),
            mediaPayloadRaw = unwrapped.bodyRaw
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun contentFromParts(
        extension: Map<Int, Any>, mediaPreview: Map<Int, Any>?, slot: ByteArray, contentSignature: Map<Int, Any>?
    ): TagDropPayload.Content {
        val rawHash = mediaPreview?.get(MPK_CONTENT_HASH) as? ByteArray
        // contentHash is multihash-style on the wire (1-byte function-code prefix, §4.4) —
        // stripped back to the plain 8-byte digest this app's cacheId convention uses elsewhere.
        val cacheId = if (rawHash != null && rawHash.size > 1) rawHash.copyOfRange(1, rawHash.size) else null
        return TagDropPayload.Content(
            cacheId         = cacheId,
            hint            = extension.text(EK_HINT),
            filename        = mediaPreview?.text(MPK_FILENAME),
            mimeType        = mediaPreview?.text(MPK_MEDIA_TYPE) ?: "",
            compression     = COMPRESSION_NONE, // Compress Wrapper presence is transient (unwrapped already); not re-declared on TagDropPayload
            content         = slot,
            overrideBlob    = slot.takeIf { it.size >= OVERRIDE_BLOB_MIN_BYTES },
            encryption      = extension.uint(EK_ENCRYPTION)?.toInt() ?: ENCRYPTION_NONE,
            keyMaterial     = extension.bytesOrNull(EK_KEY_MATERIAL),
            retainKey       = extension.boolOrNull(EK_RETAIN_KEY) ?: true,
            collectionId    = extension.bytesOrNull(EK_COLLECTION_ID),
            collectionLabel = extension.text(EK_COLLECTION_LABEL),
            collectionTag   = extension.text(EK_COLLECTION_TAG),
            icon            = extension.text(EK_ICON),
            kdfAlg          = extension.uint(EK_KDF_ALG)?.toInt() ?: KDF_NONE,
            kdfSalt         = extension.bytesOrNull(EK_KDF_SALT),
            kdfIters        = extension.uint(EK_KDF_ITERS)?.toInt() ?: DEFAULT_KDF_ITERS,
            lat             = extension.doubleOrNull(EK_LAT),
            lng             = extension.doubleOrNull(EK_LNG),
            radiusM         = extension.doubleOrNull(EK_RADIUS_M),
            preferDeclaredLocation = extension.boolOrNull(EK_PREFER_DECLARED_LOCATION) ?: false,
            locationLabel   = extension.text(EK_LOCATION_LABEL),
            inReplyTo       = extension.bytesOrNull(EK_IN_REPLY_TO),
            title           = mediaPreview?.text(MPK_LABEL),
            description     = extension.text(EK_DESCRIPTION),
            createdAt       = extension.uint(EK_CREATED_AT),
            pixelArt        = extension.boolOrNull(EK_PIXEL_ART) ?: false,
            sourceUrl       = extension.text(EK_SOURCE_URL),
            signatureAlgorithm = extension.uint(EK_SIGNATURE_ALGORITHM)?.toInt() ?: SIGNATURE_ALG_NONE,
            signature       = contentSignature?.bytesOrNull(CSK_SIGNATURE),
            signerPubkey    = contentSignature?.bytesOrNull(CSK_SIGNER_PUBKEY),
            signerId        = extension.bytesOrNull(EK_SIGNER_ID),
            signerLabel     = extension.text(EK_SIGNER_LABEL)
        )
    }

    /**
     * Parses a fully-reassembled Paper Preview+Body pair into a [TagDropPayload.Paper] (SPEC
     * §4.3-§4.4), recomputing and verifying `root_hash` from the bytes. [bodyWireBytes] is the
     * Body Record's wire bytes (possibly Compress-wrapped; possibly externally Split-
     * reassembled). Returns null if malformed or `root_hash` doesn't verify against
     * [record]'s declared value (when present).
     */
    fun parsePaperStream(record: ScannedRecord.Paper, bodyWireBytes: ByteArray?): TagDropPayload.Paper? {
        if (bodyWireBytes == null) return null
        val (body, bodyRaw) = unwrapPaperBody(bodyWireBytes) ?: return null
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
        val scan = recordScanResult(stream) as? ScannedRecord.Paper ?: return null
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
     * Reads one Split fragment's own fields ([SplitFragment]) from [record]'s large part (must
     * already be `TYPE_SPLIT`, per [isSplitFragment]). Returns null if malformed or missing a
     * required field.
     */
    @Suppress("UNCHECKED_CAST")
    fun splitFragmentOf(record: ScannedRecord): SplitFragment? {
        val frag = when (record) {
            is ScannedRecord.Content -> record.splitFragment ?: return null
            is ScannedRecord.Paper -> record.second?.takeIf { record.secondTypeId == TYPE_SPLIT } ?: return null
        }
        if (!checkRecordKeys(frag, KNOWN_SPLIT)) return null
        val groupId = frag.bytesOrNull(SK_GROUP_ID) ?: return null
        val index = frag.uint(SK_INDEX)?.toInt() ?: 0
        val count = frag.uint(SK_COUNT)?.toInt() ?: 1
        val data = frag.bytesOrNull(SK_DATA) ?: return null
        val total = frag.uint(SK_TOTAL)?.toInt() ?: return null
        if (count < 1) return null
        return SplitFragment(groupId, index, count, data, total, isParity = index >= count)
    }

    /**
     * [record]'s complete (single-code, non-Split) large-part wire bytes — Media Payload's own
     * wire bytes for Content, or Paper-Body's for Paper — or null for a key-only code / a
     * malformed Paper scan with no second Record at all.
     */
    fun unwrappedBodyBytes(record: ScannedRecord): ByteArray? = when (record) {
        is ScannedRecord.Content -> record.mediaPayloadWireRaw
        is ScannedRecord.Paper -> record.secondRaw
    }

    /**
     * The LOGICAL Paper-Body Record bytes — after any Compress Wrapper (QDEF Type 4) unwrap —
     * that [paperSignedMessageHash] covers, given [bodyWireBytes] (a complete, possibly
     * Compress-wrapped, possibly externally Split-reassembled Paper-Body byte sequence).
     * Returns null if malformed.
     */
    fun logicalPaperBodyBytes(bodyWireBytes: ByteArray): ByteArray? = unwrapPaperBody(bodyWireBytes)?.second

    /** Whether [record]'s large part (if any) is a Split Wrapper fragment rather than a plain/Compress-wrapped body. */
    fun isSplitFragment(record: ScannedRecord): Boolean = when (record) {
        is ScannedRecord.Content -> record.splitFragment != null
        is ScannedRecord.Paper -> record.secondTypeId == TYPE_SPLIT
    }

    /** [record]'s small, always-repeated identity for UI display before full reassembly (SPEC §5.1) — `(cacheId/rootHash, hint)`. */
    @Suppress("UNCHECKED_CAST")
    fun previewIdentity(record: ScannedRecord): Pair<ByteArray?, String?> = when (record) {
        is ScannedRecord.Content -> {
            val rawHash = record.mediaPreview?.get(MPK_CONTENT_HASH) as? ByteArray
            val cacheId = if (rawHash != null && rawHash.size > 1) rawHash.copyOfRange(1, rawHash.size) else null
            cacheId to record.extension.text(EK_HINT)
        }
        is ScannedRecord.Paper -> record.preview.bytesOrNull(PPK_ROOT_HASH) to record.preview.text(PPK_HINT)
    }

    // ── Compression helpers ───────────────────────────────────────────────────

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** Thrown by [decompress] when a compressed payload's output exceeds [maxBytes]
     *  ([MAX_DECOMPRESSED_BYTES] by default) — a decompression-bomb guard (SPEC §8), not a
     *  malformed-stream error. Callers already treat any exception from [decompress] as a decode
     *  failure (`runCatching { decompress(...) }.getOrNull()`), so this needs no special handling
     *  beyond existing call sites. */
    class DecompressionBombException(message: String) : Exception(message)

    /** [maxBytes] defaults to [MAX_DECOMPRESSED_BYTES]; overridable only so tests can exercise the
     *  guard itself without allocating a real multi-megabyte fixture — every production call site
     *  uses the default. */
    fun decompress(data: ByteArray, maxBytes: Long = MAX_DECOMPRESSED_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw DecompressionBombException("decompressed output exceeds $maxBytes bytes (SPEC §8)")
                }
                out.write(buffer, 0, n)
            }
        }
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

    // As of SPEC.md v14, TagDrop's own (namespace-scoped) Type IDs and QDEF's own global
    // standard Type IDs deliberately share the same small integers (§2.1's note) — a lookup
    // keyed purely by typeId would collide (e.g. `1` means Content Extension in TagDrop's own
    // namespace, Split Wrapper globally). Two separate tables, chosen by whether a given Record
    // actually resolved to TagDrop's namespace ([isTagDropNamespace]) — see [describeRecord].
    private val TAGDROP_TYPE_NAMES = mapOf(
        TYPE_CONTENT_EXTENSION to "Content Extension", TYPE_CONTENT_SIGNATURE to "Content Signature",
        TYPE_PAPER_PREVIEW to "Paper-Preview", TYPE_PAPER_BODY to "Paper-Body"
    )
    private val QDEF_GLOBAL_TYPE_NAMES = mapOf(
        TYPE_MEDIA_PREVIEW to "Media Preview", TYPE_MEDIA_PAYLOAD to "Media Payload",
        TYPE_SPLIT to "Split Wrapper", TYPE_COMPRESS to "Compress Wrapper"
    )

    private val CONTENT_EXTENSION_KEY_NAMES = mapOf(
        EK_HINT to "hint", EK_DESCRIPTION to "description",
        EK_COLLECTION_ID to "collection_id", EK_COLLECTION_LABEL to "collection_label", EK_COLLECTION_TAG to "collection_tag",
        EK_ICON to "icon", EK_PIXEL_ART to "pixel_art",
        EK_LAT to "lat", EK_LNG to "lng", EK_RADIUS_M to "radius_m",
        EK_PREFER_DECLARED_LOCATION to "prefer_declared_location", EK_LOCATION_LABEL to "location_label",
        EK_KEY_MATERIAL to "key_material", EK_RETAIN_KEY to "retain_key", EK_ENCRYPTION to "encryption",
        EK_KDF_ALG to "kdf_alg", EK_KDF_SALT to "kdf_salt", EK_KDF_ITERS to "kdf_iters",
        EK_SIGNATURE_ALGORITHM to "signature_algorithm", EK_SIGNER_ID to "signer_id", EK_SIGNER_LABEL to "signer_label",
        EK_IN_REPLY_TO to "in_reply_to", EK_CREATED_AT to "created_at", EK_SOURCE_URL to "source_url"
    )
    private val MEDIA_PREVIEW_KEY_NAMES = mapOf(
        MPK_MEDIA_TYPE to "mediaType", MPK_CONTENT_HASH to "contentHash", MPK_FILENAME to "filename", MPK_LABEL to "label"
    )
    private val MEDIA_PAYLOAD_KEY_NAMES = mapOf(MYK_CONTENT to "content", MYK_MEDIA_TYPE to "mediaType")
    private val CONTENT_SIGNATURE_KEY_NAMES = mapOf(CSK_SIGNATURE to "signature", CSK_SIGNER_PUBKEY to "signer_pubkey")
    private val PAPER_PREVIEW_KEY_NAMES = mapOf(
        PPK_ROOT_HASH to "root_hash", PPK_HINT to "hint", PPK_SET to "set", PPK_SLUG to "slug",
        PPK_DOMAIN to "domain", PPK_STEP to "step",
        PPK_COLLECTION_ID to "collection_id", PPK_COLLECTION_LABEL to "collection_label", PPK_COLLECTION_TAG to "collection_tag",
        PPK_ICON to "icon",
        PPK_LAT to "lat", PPK_LNG to "lng", PPK_RADIUS_M to "radius_m",
        PPK_PREFER_DECLARED_LOCATION to "prefer_declared_location", PPK_LOCATION_LABEL to "location_label",
        PPK_SIGNATURE_ALGORITHM to "signature_algorithm", PPK_SIGNER_ID to "signer_id", PPK_SIGNER_LABEL to "signer_label",
        PPK_IN_REPLY_TO to "in_reply_to", PPK_CREATED_AT to "created_at", PPK_SOURCE_URL to "source_url",
        PPK_TITLE to "title", PPK_DESCRIPTION to "description",
        PPK_KEY_MATERIAL to "key_material", PPK_RETAIN_KEY to "retain_key"
    )
    private val PAPER_BODY_KEY_NAMES = mapOf(
        PBK_FILES to "files", PBK_RELATED to "related",
        PBK_SIGNATURE to "signature", PBK_SIGNER_PUBKEY to "signer_pubkey"
    )
    private val SPLIT_KEY_NAMES = mapOf(
        SK_GROUP_ID to "group_id", SK_INDEX to "index", SK_COUNT to "count",
        SK_DATA to "data", SK_TOTAL to "total_bytes", SK_PARITY to "parity"
    )
    private val COMPRESS_KEY_NAMES = mapOf(CK_PAYLOAD to "payload")

    // Split the same way as TAGDROP_TYPE_NAMES/QDEF_GLOBAL_TYPE_NAMES above, for the same reason.
    private val TAGDROP_KEY_NAMES_BY_TYPE = mapOf(
        TYPE_CONTENT_EXTENSION to CONTENT_EXTENSION_KEY_NAMES, TYPE_CONTENT_SIGNATURE to CONTENT_SIGNATURE_KEY_NAMES,
        TYPE_PAPER_PREVIEW to PAPER_PREVIEW_KEY_NAMES, TYPE_PAPER_BODY to PAPER_BODY_KEY_NAMES
    )
    private val QDEF_GLOBAL_KEY_NAMES_BY_TYPE = mapOf(
        TYPE_MEDIA_PREVIEW to MEDIA_PREVIEW_KEY_NAMES, TYPE_MEDIA_PAYLOAD to MEDIA_PAYLOAD_KEY_NAMES,
        TYPE_SPLIT to SPLIT_KEY_NAMES, TYPE_COMPRESS to COMPRESS_KEY_NAMES
    )

    /**
     * Pretty-prints a raw TagDrop code's CBOR Record Sequence for the on-device debug view: a
     * hex dump, then each top-level Record's Type ID and fields by name, recursing into
     * subrecords (SPEC §2, §3.1-§3.4).
     */
    fun describeCbor(cbor: ByteArray): String = buildString {
        appendLine("${cbor.size} bytes")
        appendLine(cbor.toHexDump())
        appendLine()
        // The only carrier-specific framing left as of SPEC.md v14 (§14) is the byte-mode QR/
        // JABCode carrier's 4-byte "QDEF" magic — the namespace declaration itself is no longer
        // magic-specific overhead (every carrier's root array now carries it the same way, see
        // MiniCbor.decodeRootBundle).
        val qdefMagicPresent = cbor.size >= 4 && QDEF_MAGIC.contentEquals(cbor.copyOfRange(0, 4))
        val bytes = if (qdefMagicPresent) cbor.copyOfRange(4, cbor.size) else cbor
        if (qdefMagicPresent) {
            appendLine("── QDEF byte-mode framing ──")
            appendLine("  magic: 51444546 (\"QDEF\")")
            appendLine()
        }
        if (bytes.isNotEmpty()) {
            appendLine("── Record Sequence (${bytes.size} bytes) ──")
        }
        runCatching {
            // The self-delimited QDEF root (QDEF-SPEC.md §2/§3.1, §3.5/SPEC.md v16 §2.1a): try
            // the strict, structured decode first — as of v16, always a namespace-declaring
            // Bundle wrapping one (key-only) or two (the common case) subrecords, since that's
            // what any real TagDrop code now is. Whatever's left after the root array's own
            // self-delimited length (SPEC §9: possibly tolerated padding, or a wholly independent
            // second Sequence) falls through to the same best-effort walk this function always
            // used, so a genuinely malformed/legacy input still shows something rather than
            // nothing.
            var i = 0
            var rest = bytes
            val records = MiniCbor.decodeRootBundle(bytes)
            if (records != null) {
                // As of SPEC.md v16, a top-level Record's own DecodedRecord.namespace resolves to
                // null whenever that Record's own typeId happens to be non-negative/global —
                // regardless of what the root itself declared — so reading it off the first
                // Record is no longer a reliable way to display the root's own declaration (only
                // true when that first Record happens to be TagDrop-scoped, i.e. negative typeId).
                // Read the root's own leading item directly instead — decodeRootBundle already
                // guarantees it's present whenever [records] is non-null.
                val namespace = MiniCbor.unframeNamespaceFromRootArray(bytes)?.first
                appendLine("namespace: ${namespace?.let { "${it.toHexDump()} (${if (isTagDropNamespace(it)) "TagDrop" else "unrecognized"})" } ?: "(none — global/standard Types only)"}")
                appendLine()
                for (rec in records) {
                    appendLine("Record $i:")
                    describeRecord(rec, 1, this)
                    appendLine()
                    i++
                }
                rest = bytes.copyOfRange(MiniCbor.encodeRootBundle(records.map { it.raw }, namespace).size, bytes.size)
            }
            while (rest.isNotEmpty()) {
                val rec = MiniCbor.decodeRecordPrefix(rest) ?: break
                appendLine("Record $i:")
                describeRecord(rec, 1, this)
                appendLine()
                rest = rec.trailing
                i++
            }
        }.onFailure { append("Failed to decode as CBOR sequence: ${it.message}") }
    }

    private fun describeRecord(rec: MiniCbor.DecodedRecord, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent - 1)
        // A Record's bare typeId magnitude alone is ambiguous as of v14 (TagDrop's own small IDs
        // deliberately coincide with some of QDEF's own global ones, §2.1) — pick the name/key
        // tables that match how this Record's own namespace actually resolved. As of SPEC.md v16
        // TagDrop's own Types wire-encode NEGATIVE (§2.1a) while [TAGDROP_TYPE_NAMES]/
        // [TAGDROP_KEY_NAMES_BY_TYPE] are keyed by the positive DECLARED magnitude (matching
        // registry.rec) — negate rec.typeId back to that declared magnitude before looking it up.
        val tagDrop = isTagDropNamespace(rec.namespace)
        val typeName = if (tagDrop) TAGDROP_TYPE_NAMES[-rec.typeId] else QDEF_GLOBAL_TYPE_NAMES[rec.typeId]
        val typeNameStr = if (typeName != null) "$typeName (${rec.typeId})" else "Type ${rec.typeId}"
        out.appendLine("$pad$typeNameStr [${rec.raw.toHexDump()}]")
        val nsLine = rec.namespace?.let { "${it.toHexDump()}${if (tagDrop) " (TagDrop)" else " (unrecognized)"}" } ?: "(global — no namespace)"
        out.appendLine("${"  ".repeat(indent)}namespace: $nsLine")
        // As of SPEC.md v15, a Record's "one genuinely singular value" (if it has one) is an
        // ordinary field at reserved map key `0` — describeMap already renders it by name
        // (e.g. Compress Wrapper's/Media Payload's "payload"/"content" key), no separate
        // payload-slot line needed any more.
        val keyNames = if (tagDrop) TAGDROP_KEY_NAMES_BY_TYPE[-rec.typeId] else QDEF_GLOBAL_KEY_NAMES_BY_TYPE[rec.typeId]
        describeMap(rec.record, indent + 1, out, keyNames ?: emptyMap())
        for (sub in rec.subrecords) describeRecord(sub, indent + 1, out)
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
