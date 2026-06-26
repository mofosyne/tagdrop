package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Represents a decoded TagDrop payload, fully reassembled from one or more scanned sectors.
 *
 * Encoding URIs: tagdrop:<base41-cbor-sequence>
 *   <base41-cbor-sequence> = Base41( CBOR(version) || CBOR(type) || CBOR(part_meta) || CBOR(sector_bytes) )
 *   type: 0 = Content (a cache of any size, one or more sectors)
 *         1 = Paper   (directory of files on a physical paper, one or more sectors)
 *   Reassembled stream (concatenated sector_bytes, SPEC §4.2):
 *     CBOR(core_meta_item) || CBOR(bulky_meta_item) || content
 *
 * Navigation links (not QR payloads):
 *   tagdrop://<rootHash-hex>/<slug>  resolved by TagDropLinkResolver
 */
sealed class TagDropPayload {

    /** A cache (file, page, snippet) of any size, fully reassembled (SPEC §4.2). */
    data class Content(
        val cacheId: ByteArray?,  // SHA-256(uncompressed content)[0:8] — content-addressed; null for a key-only code; random if a hidden override map is present (SPEC §9)
        val hint: String?,
        val filename: String?,
        val mimeType: String,     // empty for a key-only code (SPEC §9)
        val compression: Int,     // 0 = none, 1 = deflate — content_compression
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
        val inReplyTo: ByteArray? = null,       // optional — cache_id/root_hash of the single parent this is replying to (SPEC §7)
        val title: String? = null,              // optional — short subject/caption, distinct from hint (SPEC §4.3, issue #35)
        val description: String? = null,        // optional — content teaser / message body, e.g. when an attachment occupies content (SPEC §4.3, issue #35)
        val createdAt: Long? = null             // optional — author-declared Unix timestamp (seconds) this payload was authored; the authoring device's clock, not independently verified (SPEC §3)
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Content && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId?.contentHashCode() ?: 0
    }

    /**
     * A hidden override map (SPEC §9), decrypted from a Content's [Content.overrideBlob] —
     * found positionally inside the reassembled stream's `content` slot. Its present fields
     * overlay `core_meta_item`'s same-numbered fields — `hint`/`mime_type`/`content`/`filename` —
     * with the override map's values winning on collisions.
     */
    data class OverrideMap(
        val hint: String? = null,
        val mimeType: String? = null,
        val content: ByteArray? = null,
        val filename: String? = null
    )

    /** One file listed in a paper's directory. */
    data class FileEntry(
        val slug: String,        // URL-safe name for this file within the paper
        val mimeType: String,
        val fileId: ByteArray,   // cache_id of the file's root QR (a Content payload)
        val description: String? = null  // optional content teaser, e.g. "A poem to read" (SPEC §4.3, issue #35)
    ) {
        override fun equals(other: Any?) = other is FileEntry && slug == other.slug && fileId.contentEquals(other.fileId)
        override fun hashCode() = 31 * slug.hashCode() + fileId.contentHashCode()
    }

    /** A hint pointing to a related paper at a different physical location. */
    data class RelatedPaper(
        val hint: String,              // human-readable description / location hint
        val set: String?    = null,    // which network/trail this paper belongs to
        val slug: String?   = null,    // that paper's address within the set
        val paperId: ByteArray? = null,// root hash of that paper, if pre-computed
        val lat: Double? = null,       // latitude of the related paper, if known
        val lng: Double? = null,       // longitude of the related paper, if known
        val radiusM: Double? = null,   // optional — circle-of-uncertainty radius in meters around lat/lng
        val keyMaterial: ByteArray? = null,  // optional — a decryption key for the related paper (SPEC §9)
        val retainKey: Boolean = true        // recommendation for whether keyMaterial should be remembered (SPEC §9)
    ) {
        override fun equals(other: Any?) = other is RelatedPaper && hint == other.hint
        override fun hashCode() = hint.hashCode()
    }

    /**
     * Paper — the directory payload for a physical paper (A4 sheet, sticker, etc.), fully
     * reassembled (SPEC §4.3).
     *
     * Analogous to a floppy-disk FAT: lists every file on the paper and can
     * point to related papers at other locations, forming an offline TagDropNet.
     *
     * rootHash = SHA-256(core_meta_item || bulky_meta_item || content)[0:8] over the
     * **logical** (decompressed) bytes — content is always empty for a Paper, so in
     * practice this is SHA-256(core_meta_item || bulky_meta_item)[0:8] (SPEC §4.4).
     * Since root_hash lives in part_meta, outside the structure it's computed over,
     * there's no placeholder/re-encode pass needed — compute once, done.
     *
     * Navigation links embedded in HTML pages:
     *   tagdrop://<rootHash-hex>/<slug>  — resolved by TagDropLinkResolver
     */
    data class Paper(
        val rootHash: ByteArray,           // SHA-256(CBOR)[0:8]; paper's permanent address
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
        val inReplyTo: ByteArray? = null,       // optional — cache_id/root_hash of the single parent this is replying to (SPEC §7)
        val title: String? = null,              // optional — short subject/caption, distinct from label (SPEC §4.3, issue #35)
        val createdAt: Long? = null             // optional — author-declared Unix timestamp (seconds) this payload was authored; the authoring device's clock, not independently verified (SPEC §3)
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Paper && rootHash.contentEquals(other.rootHash)
        override fun hashCode() = rootHash.contentHashCode()
    }

    /** Raw data: URI from the original tagdrop format (backward compatibility). */
    data class Legacy(val dataUri: String) : TagDropPayload()
}

/**
 * Per-sector bookkeeping carried in the envelope alongside `sector_bytes` (SPEC §4.1):
 * which payload this sector belongs to, where it sits in the sequence, how many sectors
 * to expect. `cacheId` is the one field that may be omitted — a key-only code (SPEC §9)
 * carries no content of its own to identify, so it typically has none either.
 */
data class PartMeta(
    val cacheId: ByteArray?,
    val sectorIndex: Int,             // 0-based
    val sectorCount: Int,             // how many data sectors carry the payload
    val totalBytes: Int,              // length of the full reassembled stream
    val paritySchemeRaw: Int? = null  // only present on sectors at index >= sectorCount (SPEC §5)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PartMeta) return false
        if (!cacheId.contentEquals(other.cacheId)) return false
        if (sectorIndex != other.sectorIndex) return false
        if (sectorCount != other.sectorCount) return false
        if (totalBytes != other.totalBytes) return false
        if (paritySchemeRaw != other.paritySchemeRaw) return false
        return true
    }

    override fun hashCode(): Int {
        var result = cacheId?.contentHashCode() ?: 0
        result = 31 * result + sectorIndex
        result = 31 * result + sectorCount
        result = 31 * result + totalBytes
        result = 31 * result + (paritySchemeRaw ?: -1)
        return result
    }
}

/**
 * One scanned/printed code (one CBOR-sequence envelope), before reassembly (SPEC §2/§4.1).
 * Every code — standalone or one of several pieces — decodes to a Sector; feed it to
 * [SectorAssembler] to reassemble the payload it belongs to.
 */
data class Sector(
    val type: Int,     // 0 = Content, 1 = Paper (SPEC §2)
    val partMeta: PartMeta,
    val sectorBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Sector) return false
        if (type != other.type) return false
        if (partMeta != other.partMeta) return false
        if (!sectorBytes.contentEquals(other.sectorBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + partMeta.hashCode()
        result = 31 * result + sectorBytes.contentHashCode()
        return result
    }
}

/**
 * What one scanned/printed code decoded to. A [SectorScan] always needs [SectorAssembler]
 * to resolve into a usable [TagDropPayload] — even a `sector_count` 1 payload is technically
 * one sector — while a [LegacyScan] is already a complete, displayable [TagDropPayload.Legacy].
 */
sealed class TagDropScan {
    data class SectorScan(val sector: Sector) : TagDropScan()
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
