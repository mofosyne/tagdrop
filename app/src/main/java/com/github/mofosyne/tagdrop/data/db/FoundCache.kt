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
    val contentBytes: ByteArray?,         // null if user chose not to save content; ciphertext while encrypted == true
    val collectionId: String? = null,     // hex-encoded 8-byte ID, groups related scans
    val collectionLabel: String? = null,  // human-readable name for the collection
    val collectionTag: String? = null,    // hashtag-style cross-collection tag
    val lat: Double? = null,              // latitude where this was scanned, if available
    val lng: Double? = null,              // longitude where this was scanned, if available
    val icon: String? = null,             // optional emoji icon
    val createdByMe: Boolean = false,     // true if authored in-app (Create Cache/Paper), not scanned
    val encrypted: Boolean = false,       // true if contentBytes is still ciphertext awaiting a key (SPEC §9)
    val pendingNonce: ByteArray? = null,  // AES-GCM nonce for contentBytes, present iff encrypted
    val pendingCompression: Int = 0       // compression applied before encryption; needed to decompress after decrypting
) {
    override fun equals(other: Any?) = other is FoundCache && cacheId == other.cacheId
    override fun hashCode() = cacheId.hashCode()
}

/** False while [FoundCache.contentBytes] is still ciphertext awaiting a key (SPEC §9) — not safe to render or export. */
val FoundCache.isOpenable: Boolean get() = contentBytes != null && !encrypted
