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
    val collectionId: String? = null   // hex-encoded 8-byte ID, groups related scans
) {
    override fun equals(other: Any?) = other is ScannedPaper && rootHash == other.rootHash
    override fun hashCode() = rootHash.hashCode()
}
