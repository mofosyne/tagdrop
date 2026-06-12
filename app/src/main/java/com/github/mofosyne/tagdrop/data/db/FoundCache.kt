package com.github.mofosyne.tagdrop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "found_caches")
data class FoundCache(
    @PrimaryKey val cacheId: String,     // hex-encoded 8-byte ID
    val discoveredAt: Long,               // epoch ms
    val hint: String?,
    val filename: String?,
    val mimeType: String,
    val contentBytes: ByteArray?,         // null if user chose not to save content
    val collectionId: String? = null,     // hex-encoded 8-byte ID, groups related scans
    val collectionLabel: String? = null,  // human-readable name for the collection
    val collectionTag: String? = null     // hashtag-style cross-collection tag
) {
    override fun equals(other: Any?) = other is FoundCache && cacheId == other.cacheId
    override fun hashCode() = cacheId.hashCode()
}
