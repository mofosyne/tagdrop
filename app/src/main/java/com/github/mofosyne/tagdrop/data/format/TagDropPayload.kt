package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Represents a decoded TagDrop payload, fully reassembled from one or more scanned codes.
 *
 * Encoding URIs: tagdrop:<base41-cbor-sequence>
 *   <base41-cbor-sequence> = Base41( CBOR Sequence of 1-2 QDEF Records, SPEC.md §2 )
 *   A code always carries a Preview Record (Content-Preview, Type 1; or Paper-Preview,
 *   Type 5) and, if the payload has a body, either the complete Body Record (Content-Body,
 *   Type 3; or Paper-Body, Type 7 — optionally Compress-wrapped, QDEF Type 8) or one
 *   Split-Wrapper-wrapped (QDEF Type 2) Body fragment, for a multi-code payload. No magic
 *   header, no namespace discriminator on this carrier — the `tagdrop:` scheme itself is
 *   both the dispatch signal and (implicitly, never transmitted) TagDrop's namespace, the
 *   same fixed value declared explicitly on the byte-mode QR carrier (SPEC.md §2.1a).
 *
 * Navigation links (not QR payloads):
 *   tagdrop://<domain-or-@rootHash-hex>/<slug>  resolved by TagDropLinkResolver
 */
sealed class TagDropPayload {

    /** A cache (file, page, snippet) of any size, fully reassembled (SPEC §4.1). */
    data class Content(
        val cacheId: ByteArray?,  // SHA-256(uncompressed content)[0:8] — content-addressed; null for a key-only code; random if a hidden override map is present (SPEC §9)
        val hint: String?,
        val filename: String?,
        val mimeType: String,     // empty for a key-only code (SPEC §9)
        val compression: Int,     // 0 = none, 1 = deflate — QDEF Compress Wrapper (Type 8) presence
        val content: ByteArray,   // raw (possibly compressed) content bytes — cover/decoy/genuine; empty for a key-only code
        val overrideBlob: ByteArray? = null,    // candidate encrypted override map found positionally in the content slot, >=28 bytes (SPEC §9)
        val encryption: Int = 0,                // 0 = none, 1 = AES-256-GCM — optional cosmetic hint only, NOT a precondition (SPEC §9)
        val keyMaterial: ByteArray? = null,     // optional — a decryption key for OTHER content (SPEC §9)
        val retainKey: Boolean = true,          // recommendation for whether keyMaterial should be remembered (SPEC §9)
        val collectionId: ByteArray? = null,    // optional — groups related QR codes (see SPEC §7)
        val collectionLabel: String? = null,    // optional — human-readable name for the collection
        val collectionTag: String? = null,      // optional — hashtag-style cross-collection tag
        val icon: String? = null,               // optional — emoji icon for this page/collection
        val kdfAlg: Int = 0,                    // 0 = none, 1 = PBKDF2-SHA256 (SPEC §9)
        val kdfSalt: ByteArray? = null,         // 16-byte random salt (present when kdfAlg != 0)
        val kdfIters: Int = 100000,             // PBKDF2 iteration count
        val lat: Double? = null,                // optional — author-declared latitude of this content's physical location
        val lng: Double? = null,                // optional — author-declared longitude of this content's physical location
        val radiusM: Double? = null,            // optional — circle-of-uncertainty radius in meters around lat/lng
        val preferDeclaredLocation: Boolean = false, // if true, lat/lng wins over live GPS even when a fix is available
        val locationLabel: String? = null,      // optional — human-readable, non-coordinate location description, e.g. "🚋 Tram 40" (SPEC §4.2)
        val inReplyTo: ByteArray? = null,       // optional — cache_id/root_hash of the single parent this is replying to (SPEC §7)
        val title: String? = null,              // optional — short subject/caption, distinct from hint (SPEC §4.3, issue #35)
        val description: String? = null,        // optional — content teaser / message body, e.g. when an attachment occupies content (SPEC §4.3, issue #35)
        val createdAt: Long? = null,            // optional — author-declared Unix timestamp (seconds) this payload was authored; the authoring device's clock, not independently verified (SPEC §3)
        val pixelArt: Boolean = false,          // optional — author hint to render this image with no smoothing/nearest-neighbor scaling (SPEC §7)
        val sourceUrl: String? = null,          // optional — URL of a JSON drop-source registry listing nearby drops
        val signatureAlgorithm: Int = 0,        // 0 = unsigned, 1 = ML-DSA-44 (SPEC §10)
        val signature: ByteArray? = null,       // opaque signature bytes (SPEC §10)
        val signerPubkey: ByteArray? = null,    // present only on the first signed code from a given signer_id (SPEC §10)
        val signerId: ByteArray? = null,        // SHA-256(signerPubkey)[0:8] (SPEC §10)
        val signerLabel: String? = null         // optional self-asserted human-readable signer name (SPEC §10)
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Content && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId?.contentHashCode() ?: 0
    }

    /**
     * A hidden override map (SPEC §9), decrypted from a Content's [Content.overrideBlob] —
     * found positionally inside the reassembled Body's `content` slot (Content-Body key 1).
     * Its present fields overlay Content-Preview's same-purpose fields — `hint`/`mime_type`/
     * `content`/`filename` — with the override map's values winning on collisions. Its own
     * local key namespace (1=hint, 3=mime_type, 5=content, 7=filename) is independent of
     * Content-Preview's numbering (SPEC §9).
     */
    data class OverrideMap(
        val hint: String? = null,
        val mimeType: String? = null,
        val content: ByteArray? = null,
        val filename: String? = null
    )

    /** One file listed in a paper's directory (Paper-Body `files[]`, local key namespace). */
    data class FileEntry(
        val slug: String,        // URL-safe name for this file within the paper
        val mimeType: String,
        val fileId: ByteArray,   // cache_id of the file's root QR (a Content payload)
        val description: String? = null,  // optional content teaser, e.g. "A poem to read" (SPEC §4.3, issue #35)
        val pixelArt: Boolean = false     // render with nearest-neighbour scaling (no smoothing) — raw QR files only
    ) {
        override fun equals(other: Any?) = other is FileEntry && slug == other.slug && fileId.contentEquals(other.fileId)
        override fun hashCode() = 31 * slug.hashCode() + fileId.contentHashCode()
    }

    /** A hint pointing to a related paper at a different physical location (Paper-Body `related[]`, local key namespace). */
    data class RelatedPaper(
        val hint: String,              // human-readable description / location hint
        val set: String?    = null,    // which network/trail this paper belongs to
        val slug: String?   = null,    // that paper's address within the set
        val paperId: ByteArray? = null,// root hash of that paper, if pre-computed
        val lat: Double? = null,       // latitude of the related paper, if known
        val lng: Double? = null,       // longitude of the related paper, if known
        val radiusM: Double? = null,   // optional — circle-of-uncertainty radius in meters around lat/lng
        val keyMaterial: ByteArray? = null,  // optional — a decryption key for the related paper (SPEC §9)
        val retainKey: Boolean = true,       // recommendation for whether keyMaterial should be remembered (SPEC §9)
        val step: Int? = null                // optional — 1-based position of the related paper within its `set` trail (SPEC §4.3)
    ) {
        override fun equals(other: Any?) = other is RelatedPaper && hint == other.hint
        override fun hashCode() = hint.hashCode()
    }

    /**
     * Paper — the directory payload for a physical paper (A4 sheet, sticker, etc.), fully
     * reassembled (SPEC §4.3, §4.4).
     *
     * Analogous to a floppy-disk FAT: lists every file on the paper and can
     * point to related papers at other locations, forming an offline TagDropNet.
     *
     * rootHash = SHA-256(Preview' || Body')[0:8] — a genuine self-reference (unlike
     * Content's cache_id): Preview' is Paper-Preview's own canonical bytes with root_hash
     * itself and the signature fields stripped; Body' is Paper-Body's canonical bytes with
     * its own signature fields stripped. Built via the same placeholder-then-strip
     * discipline SPEC §10 already requires for signing (SPEC §4.4) — this is in fact the
     * *same* SHA-256 call as SPEC §10's signed-message hash, just truncated to 8 bytes.
     *
     * Navigation links embedded in HTML pages:
     *   tagdrop://@<rootHash-hex>/<slug>  — pinned to this exact paper, resolved by
     *   TagDropLinkResolver (the `@` marker rules out an unrelated paper's `domain`
     *   claim ever being mistaken for this root hash, SPEC §7).
     */
    data class Paper(
        val rootHash: ByteArray,           // SHA-256(Preview' || Body')[0:8]; paper's permanent address
        val label: String?,                // human-readable name for this paper
        val set: String?,                  // network/trail name
        val slug: String?,                 // this paper's address within the set
        val files: List<FileEntry>,        // directory of files on this paper
        val related: List<RelatedPaper>,   // hints to other papers / locations
        val description: String? = null,        // optional content teaser for the whole paper (SPEC §4.3, issue #35)
        val collectionId: ByteArray? = null,    // optional — groups related QR codes (see SPEC §7)
        val collectionLabel: String? = null,    // optional — human-readable name for the collection
        val collectionTag: String? = null,      // optional — hashtag-style cross-collection tag
        val icon: String? = null,               // optional — emoji icon for this page/collection
        val keyMaterial: ByteArray? = null,     // optional — a decryption key for OTHER content (SPEC §9)
        val retainKey: Boolean = true,          // recommendation for whether keyMaterial should be remembered (SPEC §9)
        val lat: Double? = null,                // optional — author-declared latitude of this paper's physical location
        val lng: Double? = null,                // optional — author-declared longitude of this paper's physical location
        val radiusM: Double? = null,            // optional — circle-of-uncertainty radius in meters around lat/lng
        val preferDeclaredLocation: Boolean = false, // if true, lat/lng wins over live GPS even when a fix is available
        val locationLabel: String? = null,      // optional — human-readable, non-coordinate location description, e.g. "🚋 Tram 40" (SPEC §4.2)
        val inReplyTo: ByteArray? = null,       // optional — cache_id/root_hash of the single parent this is replying to (SPEC §7)
        val title: String? = null,              // optional — short subject/caption, distinct from label (SPEC §4.3, issue #35)
        val createdAt: Long? = null,            // optional — author-declared Unix timestamp (seconds) this payload was authored; the authoring device's clock, not independently verified (SPEC §3)
        val domain: String? = null,             // optional — human-readable name for tagdrop://<domain>/<slug> links; falls back to slug if absent (SPEC §7)
        val step: Int? = null,                  // optional — 1-based position of this paper within its `set` trail (SPEC §4.3)
        val signatureAlgorithm: Int = 0,        // 0 = unsigned, 1 = ML-DSA-44 (SPEC §10)
        val signature: ByteArray? = null,       // opaque signature bytes (SPEC §10)
        val signerPubkey: ByteArray? = null,    // present only on the first signed code from a given signer_id (SPEC §10)
        val signerId: ByteArray? = null,        // SHA-256(signerPubkey)[0:8] (SPEC §10)
        val signerLabel: String? = null         // optional self-asserted human-readable signer name (SPEC §10)
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Paper && rootHash.contentEquals(other.rootHash)
        override fun hashCode() = rootHash.contentHashCode()
    }

    /** Raw data: URI from the original tagdrop format (backward compatibility). */
    data class Legacy(val dataUri: String) : TagDropPayload()
}

/** Which payload kind a scanned code's Preview Record belongs to (its own Type ID, SPEC §2.1). */
enum class PayloadKind { CONTENT, PAPER }

/**
 * One scanned/printed code, decoded down to its Preview Record plus whatever came after it
 * (SPEC §2, §5.1) — mirrors tools/reader/index.html's recordScanResult()/RecordAssembler
 * design, the settled JS reference implementation this Kotlin port targets.
 *
 * Preview is always plain and unwrapped, and — for a multi-code payload — MUST be repeated
 * identically on every code in the group (SPEC §5.1): a decoder scanning any single code
 * already knows the payload's identity and can show a usable preview. [second] is:
 *   - `null` for a key-only code (SPEC §9) — Preview only, nothing else.
 *   - the complete (optionally Compress-wrapped) Body Record for a single-code payload.
 *   - one Split-Wrapper-wrapped (QDEF Type 2) Body fragment for a multi-code payload.
 *
 * [preview]/[second] are MiniCbor's decoded `Map<Int, Any>` field maps (the Record's own
 * Type ID sits at key 0) — kept as raw maps, not further typed here, so field extraction
 * stays in [com.github.mofosyne.tagdrop.data.format.TagDropCodec] alongside the key tables
 * it already owns, the same division of responsibility the pre-QDEF format used.
 */
data class ScannedRecord(
    val kind: PayloadKind,
    val previewRaw: ByteArray,
    val preview: Map<Int, Any>,
    val secondRaw: ByteArray?,
    val second: Map<Int, Any>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScannedRecord) return false
        if (kind != other.kind) return false
        if (!previewRaw.contentEquals(other.previewRaw)) return false
        if (secondRaw == null != (other.secondRaw == null)) return false
        if (secondRaw != null && other.secondRaw != null && !secondRaw.contentEquals(other.secondRaw)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + previewRaw.contentHashCode()
        result = 31 * result + (secondRaw?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * A Split Wrapper fragment's own wire fields (QDEF-SPEC.md §4.1 Type 2, SPEC §5) — [second]/
 * [secondRaw] decoded one level further when `second[0] == TYPE_SPLIT`. A fragment at
 * `index == count` is the optional XOR parity fragment (SPEC §5's redundancy scheme), never
 * a data fragment; [isParity] distinguishes the two cases explicitly rather than leaving
 * callers to compare `index`/`count` themselves.
 */
data class SplitFragment(
    val groupId: ByteArray,
    val index: Int,
    val count: Int,
    val data: ByteArray,
    val total: Int,
    val isParity: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SplitFragment) return false
        if (!groupId.contentEquals(other.groupId)) return false
        if (index != other.index) return false
        if (count != other.count) return false
        if (!data.contentEquals(other.data)) return false
        if (total != other.total) return false
        if (isParity != other.isParity) return false
        return true
    }

    override fun hashCode(): Int {
        var result = groupId.contentHashCode()
        result = 31 * result + index
        result = 31 * result + count
        result = 31 * result + data.contentHashCode()
        result = 31 * result + total
        result = 31 * result + isParity.hashCode()
        return result
    }
}

/**
 * What one scanned/printed code decoded to. A [RecordScan] always needs [SectorAssembler]
 * to resolve into a usable [TagDropPayload] — even a single-code payload is technically a
 * one-code group — while a [LegacyScan] is already a complete, displayable
 * [TagDropPayload.Legacy].
 */
sealed class TagDropScan {
    data class RecordScan(val record: ScannedRecord) : TagDropScan()
    data class LegacyScan(val payload: TagDropPayload.Legacy) : TagDropScan()
}

/**
 * True if [related] points to [paper] — by precomputed root hash, or by matching set+slug.
 * set+slug is the durable cross-reference: root hashes change whenever a paper is updated,
 * but a re-scanned replacement keeps the same set+slug.
 */
fun TagDropPayload.RelatedPaper.matchesScannedPaper(paper: ScannedPaper): Boolean {
    if (paperId != null && paperId.toHex() == paper.rootHash) return true
    return set != null && slug != null && set == paper.set && slug == paper.slug
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
