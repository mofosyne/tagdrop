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
    val icon: String? = null,            // optional emoji icon
    val createdByMe: Boolean = false     // true if authored in-app (Create Paper), not scanned
) {
    override fun equals(other: Any?) = other is ScannedPaper && rootHash == other.rootHash
    override fun hashCode() = rootHash.hashCode()
}
