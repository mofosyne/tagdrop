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
    val lat: Double? = null,             // latitude where this was scanned, if available
    val lng: Double? = null,             // longitude where this was scanned, if available
    val icon: String? = null             // optional emoji icon
) {
    override fun equals(other: Any?) = other is ScannedPaper && rootHash == other.rootHash
    override fun hashCode() = rootHash.hashCode()
}
