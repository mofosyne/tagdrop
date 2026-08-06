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
}

/** Which payload kind a scanned code's small, always-repeated Record belongs to (SPEC §2.1). */
enum class PayloadKind { CONTENT, PAPER }

/**
 * One scanned/printed code, decoded down to its small always-repeated Record(s) plus whatever
 * carries the large part (SPEC §2, §5.1) — mirrors tools/reader/index.html's
 * `recordScanResult()`/`RecordAssembler` design (SPEC.md v9), the settled JS reference
 * implementation this Kotlin port targets.
 *
 * A [Paper] scan is unchanged in shape from before v9 (still a flat Preview/Body pair, Types
 * 5/7). A [Content] scan reflects SPEC.md v9's restructuring (§3.1/§3.1a): Content Extension
 * (Type 1) is always present and repeated on every code in a group; Media Preview (QDEF Type
 * 14) is known as soon as ANY code in the group has been scanned — either directly (single-code
 * case, nesting Media Payload as its own subrecord) or via Split's own subrecord (multi-code
 * case) — which is what lets a single isolated scan already show `hint`/`contentHash`/etc
 * regardless of reassembly progress, same as Paper's Preview always has. Field maps are kept as
 * raw `Map<Int, Any>` (each Record Type's own independent key namespace, SPEC §3), not further
 * typed here, so field extraction stays in [com.github.mofosyne.tagdrop.data.format.TagDropCodec]
 * alongside the key tables it already owns.
 */
sealed class ScannedRecord {
    /**
     * A scanned Content code (SPEC.md v9 §3.1/§3.1a). [mediaPreview]/[mediaPreviewRaw] are
     * `null` only for a key-only code (SPEC §9) — [extension] alone, no Media Preview/Payload
     * at all. Exactly one of [splitFragment] (multi-code case) / [mediaPayloadWireRaw]
     * (single-code case) is non-null when [mediaPreview] is non-null; both are null for a
     * key-only code.
     */
    data class Content(
        /** Content Extension's (Type 1) own exact byte range — repeated on every code (§5.1). */
        val extensionRaw: ByteArray,
        /** Content Extension's own decoded field map. */
        val extension: Map<Int, Any>,
        /** Media Preview's (QDEF Type 14) own decoded field map, once known. */
        val mediaPreview: Map<Int, Any>?,
        /** Media Preview's own bare (subrecord-stripped) canonical bytes — SPEC §10's `MediaPreview'` term. */
        val mediaPreviewRaw: ByteArray?,
        /** Split Wrapper's (QDEF Type 2) own field map (group_id/index/count/data/total) — multi-code case only. */
        val splitFragment: Map<Int, Any>?,
        /** Media Payload's (QDEF Type 6) own wire bytes (possibly Compress-wrapped; Content Signature nested if signed) — single-code case only. */
        val mediaPayloadWireRaw: ByteArray?,
        /**
         * The exact byte range of whatever Record followed Content Extension on this scanned
         * code — the wire-nested Media Preview (single-code case) or the Split fragment with
         * Media Preview as its own subrecord (multi-code case) — null only for a key-only code.
         * Debug/display use only (e.g. "Inspect CBOR"); reassembly logic uses the other fields.
         */
        val secondRaw: ByteArray?
    ) : ScannedRecord() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Content) return false
            if (!extensionRaw.contentEquals(other.extensionRaw)) return false
            if ((mediaPreviewRaw == null) != (other.mediaPreviewRaw == null)) return false
            if (mediaPreviewRaw != null && other.mediaPreviewRaw != null && !mediaPreviewRaw.contentEquals(other.mediaPreviewRaw)) return false
            if ((mediaPayloadWireRaw == null) != (other.mediaPayloadWireRaw == null)) return false
            if (mediaPayloadWireRaw != null && other.mediaPayloadWireRaw != null && !mediaPayloadWireRaw.contentEquals(other.mediaPayloadWireRaw)) return false
            if ((splitFragment == null) != (other.splitFragment == null)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = extensionRaw.contentHashCode()
            result = 31 * result + (mediaPreviewRaw?.contentHashCode() ?: 0)
            result = 31 * result + (mediaPayloadWireRaw?.contentHashCode() ?: 0)
            result = 31 * result + (splitFragment?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * A scanned Paper code — unchanged in shape from before v9 (SPEC §3.3-§3.4). [second] is:
     *   - `null` for a malformed code (a Paper always has a Body — SPEC §4.1).
     *   - the complete (optionally Compress-wrapped) Body Record for a single-code payload.
     *   - one Split-Wrapper-wrapped (QDEF Type 2) Body fragment for a multi-code payload.
     */
    data class Paper(
        val previewRaw: ByteArray,
        val preview: Map<Int, Any>,
        val secondTypeId: Int?,
        val secondRaw: ByteArray?,
        val second: Map<Int, Any>?
    ) : ScannedRecord() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Paper) return false
            if (!previewRaw.contentEquals(other.previewRaw)) return false
            if (secondTypeId != other.secondTypeId) return false
            if ((secondRaw == null) != (other.secondRaw == null)) return false
            if (secondRaw != null && other.secondRaw != null && !secondRaw.contentEquals(other.secondRaw)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = previewRaw.contentHashCode()
            result = 31 * result + (secondTypeId ?: 0)
            result = 31 * result + (secondRaw?.contentHashCode() ?: 0)
            return result
        }
    }
}

/** Which [PayloadKind] a [ScannedRecord] belongs to. */
val ScannedRecord.kind: PayloadKind
    get() = when (this) {
        is ScannedRecord.Content -> PayloadKind.CONTENT
        is ScannedRecord.Paper -> PayloadKind.PAPER
    }

/**
 * A Split Wrapper fragment's own wire fields (QDEF-SPEC.md §4.1 Type 2, SPEC §5) — [second]/
 * [secondRaw] decoded one level further when `second[0] == TYPE_SPLIT`. A fragment at
 * `index == count` is the optional XOR parity fragment (SPEC §5's redundancy scheme), never
 * a data fragment; [isParity] distinguishes the two cases explicitly rather than leaving
 * callers to compare `index`/`count` themselves. [payloadHash] (`payload_hash`, QDEF-SPEC.md
 * §4.1 key `11`) is only ever present on fragment index `0` — a multihash of the fully
 * reassembled payload, TagDrop-MANDATORY (SPEC.md "Reassembly integrity") even though QDEF
 * itself leaves it OPTIONAL/odd.
 */
data class SplitFragment(
    val groupId: ByteArray,
    val index: Int,
    val count: Int,
    val data: ByteArray,
    val total: Int,
    val isParity: Boolean,
    val payloadHash: ByteArray? = null
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
        if ((payloadHash == null) != (other.payloadHash == null)) return false
        if (payloadHash != null && other.payloadHash != null && !payloadHash.contentEquals(other.payloadHash)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = groupId.contentHashCode()
        result = 31 * result + index
        result = 31 * result + count
        result = 31 * result + data.contentHashCode()
        result = 31 * result + total
        result = 31 * result + isParity.hashCode()
        result = 31 * result + (payloadHash?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * What one scanned/printed `tagdrop:` code decoded to. Always needs [SectorAssembler] to
 * resolve into a usable [TagDropPayload] — even a single-code payload is technically a
 * one-code group.
 */
sealed class TagDropScan {
    data class RecordScan(val record: ScannedRecord, val rawWireBytes: ByteArray? = null) : TagDropScan() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RecordScan) return false
            if (record != other.record) return false
            if ((rawWireBytes == null) != (other.rawWireBytes == null)) return false
            if (rawWireBytes != null && other.rawWireBytes != null && !rawWireBytes.contentEquals(other.rawWireBytes)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = record.hashCode()
            result = 31 * result + (rawWireBytes?.contentHashCode() ?: 0)
            return result
        }
    }
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
