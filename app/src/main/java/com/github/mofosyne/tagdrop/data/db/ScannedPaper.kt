package com.github.mofosyne.tagdrop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_papers")
data class ScannedPaper(
    @PrimaryKey val rootHash: String,  // hex-encoded 8-byte root hash
    val scannedAt: Long,               // epoch ms
    val label: String?,
    val set: String?,
    val slug: String?,
    val title: String? = null,           // optional short subject/caption, distinct from label (SPEC §7 "Postcards")
    val cborBytes: ByteArray,          // full paper manifest CBOR, used to re-parse files/related
    val collectionId: String? = null,    // hex-encoded 8-byte ID, groups related scans
    val collectionLabel: String? = null, // human-readable name for the collection
    val collectionTag: String? = null,   // hashtag-style cross-collection tag
    val lat: Double? = null,             // effective latitude (live GPS, or author-declared if it won — SPEC §3 prefer_declared_location)
    val lng: Double? = null,             // effective longitude, same resolution rule as lat
    val locationRadiusM: Double? = null, // circle-of-uncertainty radius in meters; only set when lat/lng came from a declared (not live-GPS) source
    val locationLabel: String? = null,   // optional human-readable, non-coordinate location description, e.g. "🚋 Tram 40" (SPEC §4.2)
    val icon: String? = null,            // optional emoji icon
    val createdByMe: Boolean = false,    // true if authored in-app (Create Paper), not scanned
    val inReplyTo: String? = null,       // hex-encoded cache_id/root_hash of the single parent this is replying to (SPEC §7)
    val domain: String? = null,          // optional human-readable tagdrop://<domain>/<slug> name; falls back to slug if absent (SPEC §7)
    val createdAt: Long? = null,         // author-declared Unix timestamp (seconds) this payload was authored, unverified (SPEC §3); distinct from scannedAt
    val signatureStatus: Int = SignatureStatus.NONE, // Verified Authorship result computed at scan time (SPEC §10)
    val signerIdHex: String? = null,     // hex-encoded 8-byte signer_id, present whenever signatureStatus != NONE
    val signerLabel: String? = null      // self-asserted human-readable signer name (SPEC §10), if any
) {
    override fun equals(other: Any?) = other is ScannedPaper && rootHash == other.rootHash
    override fun hashCode() = rootHash.hashCode()
}

/** True if this paper's Verified Authorship signature (SPEC §10) checked out against a known/cached signer_pubkey. */
val ScannedPaper.isVerifiedSigned: Boolean get() = signatureStatus == SignatureStatus.VERIFIED

/** True if this paper claims a signature that does NOT verify — tampered, or forged under a signer_id it doesn't belong to (SPEC §10). */
val ScannedPaper.hasInvalidSignature: Boolean get() = signatureStatus == SignatureStatus.INVALID

/** True if this paper is signed but its signer_pubkey hasn't been seen yet, so verification is on hold (SPEC §10 "Key caching"). */
val ScannedPaper.hasPendingSignatureVerification: Boolean get() = signatureStatus == SignatureStatus.PENDING
