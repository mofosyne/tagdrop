package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Represents a decoded TagDrop payload from a single scanned QR code.
 *
 * Encoding URIs: tagdrop:<base41-cbor-sequence>
 *   <base41-cbor-sequence> = Base41( CBOR(version) || CBOR(type) || CBOR(payload) )
 *   type: 0 = single-code cache (complete content in one QR)
 *         1 = manifest (header for a multi-code cache)
 *         2 = chunk  (one segment of a multi-code cache)
 *         3 = paper manifest (directory of files on a physical paper)
 *
 * Navigation links (not QR payloads):
 *   tagdrop://<rootHash-hex>/<slug>  resolved by TagDropLinkResolver
 */
sealed class TagDropPayload {

    /** Complete cache encoded in a single QR. */
    data class Single(
        val cacheId: ByteArray,   // SHA-256(uncompressed content)[0:8] — content-addressed; random if a hidden override map is present (SPEC §9)
        val hint: String?,
        val filename: String?,
        val mimeType: String,     // empty for a key-only code (SPEC §9)
        val compression: Int,     // 0 = none, 1 = deflate
        val content: ByteArray,   // raw (possibly compressed) clear-map payload bytes — cover/decoy/genuine; empty for a key-only code
        val overrideBlob: ByteArray? = null,    // raw trailing bytes after this Single's CBOR sequence, >=28 bytes — candidate encrypted override map (SPEC §9)
        val encryption: Int = 0,                // 0 = none, 1 = AES-256-GCM — optional cosmetic hint only, NOT a precondition (SPEC §9)
        val keyMaterial: ByteArray? = null,     // optional — a decryption key for OTHER content (SPEC §9)
        val retainKey: Boolean = true,          // recommendation for whether keyMaterial should be remembered (SPEC §9)
        val collectionId: ByteArray? = null,    // optional — groups related QR codes (see SPEC §7)
        val collectionLabel: String? = null,    // optional — human-readable name for the collection
        val collectionTag: String? = null,      // optional — hashtag-style cross-collection tag
        val icon: String? = null,               // optional — emoji icon for this page/collection
        val kdfAlg: Int = 0,                    // 0 = none, 1 = PBKDF2-SHA256 (SPEC §10)
        val kdfSalt: ByteArray? = null,         // 16-byte random salt (present when kdfAlg != 0)
        val kdfIters: Int = 100000              // PBKDF2 iteration count
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Single && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId.contentHashCode()
    }

    /**
     * Manifest for a multi-code cache. Must be scanned before its chunks.
     * Designed for geographic distribution: each chunk can be at a different location.
     */
    data class Manifest(
        val cacheId: ByteArray,   // SHA-256(uncompressed content)[0:8]; random if a hidden override map is present (SPEC §9)
        val hint: String?,
        val filename: String?,
        val mimeType: String,
        val compression: Int,
        val chunkCount: Int,
        val totalBytes: Int,
        val sha256: ByteArray,    // SHA-256 of the assembled bytes exactly as transmitted (SPEC §5/§9)
        val encryption: Int = 0,                // 0 = none, 1 = AES-256-GCM — optional cosmetic hint only, NOT a precondition (SPEC §9)
        val keyMaterial: ByteArray? = null,     // optional — a decryption key for OTHER content (SPEC §9)
        val retainKey: Boolean = true,          // recommendation for whether keyMaterial should be remembered (SPEC §9)
        val collectionId: ByteArray? = null,    // optional — groups related QR codes (see SPEC §7)
        val collectionLabel: String? = null,    // optional — human-readable name for the collection
        val collectionTag: String? = null,      // optional — hashtag-style cross-collection tag
        val icon: String? = null,               // optional — emoji icon for this page/collection
        val kdfAlg: Int = 0,                    // 0 = none, 1 = PBKDF2-SHA256 (SPEC §10)
        val kdfSalt: ByteArray? = null,         // 16-byte random salt (present when kdfAlg != 0)
        val kdfIters: Int = 100000              // PBKDF2 iteration count
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Manifest && cacheId.contentEquals(other.cacheId)
        override fun hashCode() = cacheId.contentHashCode()
    }

    /** One chunk of a multi-code cache. Order-independent; reassembled by index. */
    data class Chunk(
        val cacheId: ByteArray,   // links back to the Manifest
        val index: Int,           // 0-based chunk index
        val data: ByteArray
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is Chunk && cacheId.contentEquals(other.cacheId) && index == other.index
        override fun hashCode() = 31 * cacheId.contentHashCode() + index
    }

    /**
     * A hidden override map (SPEC §9), decrypted from a Single's [Single.overrideBlob]
     * trailing bytes or a Manifest's assembled chunk bytes. Its present fields overlay
     * the clear map's same-numbered fields — `hint`/`mime_type`/`content`/`filename` —
     * with the override map's values winning on collisions.
     */
    data class OverrideMap(
        val hint: String? = null,
        val mimeType: String? = null,
        val content: ByteArray? = null,  // Single only — a Manifest's clear map has no content field
        val filename: String? = null
    )

    /** One file listed in a paper's directory. */
    data class FileEntry(
        val slug: String,        // URL-safe name for this file within the paper
        val mimeType: String,
        val fileId: ByteArray,   // cache_id of the file's root QR (Single or Manifest)
        val description: String? = null  // optional content teaser, e.g. "A poem to read" (SPEC §4.4, issue #35)
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
        val keyMaterial: ByteArray? = null,  // optional — a decryption key for the related paper (SPEC §9)
        val retainKey: Boolean = true        // recommendation for whether keyMaterial should be remembered (SPEC §9)
    ) {
        override fun equals(other: Any?) = other is RelatedPaper && hint == other.hint
        override fun hashCode() = hint.hashCode()
    }

    /**
     * Paper manifest — the directory QR for a physical paper (A4 sheet, sticker, etc.).
     *
     * Analogous to a floppy-disk FAT: lists every file on the paper and can
     * point to related papers at other locations, forming an offline TagDropNet.
     *
     * rootHash = SHA-256(this payload's raw CBOR bytes)[0:8], computed externally
     * after the manifest is finalized — identical to how IPFS computes CIDs.
     *
     * Navigation links embedded in HTML pages:
     *   tagdrop://<rootHash-hex>/<slug>  — resolved by TagDropLinkResolver
     */
    data class PaperManifest(
        val rootHash: ByteArray,           // SHA-256(CBOR)[0:8]; paper's permanent address
        val label: String?,                // human-readable name for this paper
        val set: String?,                  // network/trail name
        val slug: String?,                 // this paper's address within the set
        val files: List<FileEntry>,        // directory of files on this paper
        val related: List<RelatedPaper>,   // hints to other papers / locations
        val description: String? = null,        // optional content teaser for the whole paper (SPEC §4.4, issue #35)
        val collectionId: ByteArray? = null,    // optional — groups related QR codes (see SPEC §7)
        val collectionLabel: String? = null,    // optional — human-readable name for the collection
        val collectionTag: String? = null,      // optional — hashtag-style cross-collection tag
        val icon: String? = null,               // optional — emoji icon for this page/collection
        val keyMaterial: ByteArray? = null,     // optional — a decryption key for OTHER content (SPEC §9)
        val retainKey: Boolean = true           // recommendation for whether keyMaterial should be remembered (SPEC §9)
    ) : TagDropPayload() {
        override fun equals(other: Any?) = other is PaperManifest && rootHash.contentEquals(other.rootHash)
        override fun hashCode() = rootHash.contentHashCode()
    }

    /** Raw data: URI from the original tagdrop format (backward compatibility). */
    data class Legacy(val dataUri: String) : TagDropPayload()
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
