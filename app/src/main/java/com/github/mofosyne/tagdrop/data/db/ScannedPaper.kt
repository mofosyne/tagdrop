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
    val domain: String? = null           // optional human-readable tagdrop://<domain>/<slug> name; falls back to slug if absent (SPEC §7)
) {
    override fun equals(other: Any?) = other is ScannedPaper && rootHash == other.rootHash
    override fun hashCode() = rootHash.hashCode()
}
