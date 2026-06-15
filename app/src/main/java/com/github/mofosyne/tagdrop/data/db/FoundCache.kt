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
    val contentBytes: ByteArray?,         // null if user chose not to save content; otherwise always the resolved clear-map content (SPEC §9)
    val collectionId: String? = null,     // hex-encoded 8-byte ID, groups related scans
    val collectionLabel: String? = null,  // human-readable name for the collection
    val collectionTag: String? = null,    // hashtag-style cross-collection tag
    val lat: Double? = null,              // latitude where this was scanned, if available
    val lng: Double? = null,              // longitude where this was scanned, if available
    val icon: String? = null,             // optional emoji icon
    val createdByMe: Boolean = false,     // true if authored in-app (Create Cache/Paper), not scanned
    val pendingOverrideBlob: ByteArray? = null,  // candidate encrypted override-map blob not yet unlocked by any retained key (SPEC §9)
    val pendingCompression: Int = 0              // compression to apply when decoding pendingOverrideBlob's plaintext (SPEC §9)
) {
    override fun equals(other: Any?) = other is FoundCache && cacheId == other.cacheId
    override fun hashCode() = cacheId.hashCode()
}

/** [FoundCache.contentBytes] is the resolved clear-map content — always safe to render/export, even with a [hasPendingOverride] (SPEC §9). */
val FoundCache.isOpenable: Boolean get() = contentBytes != null

/** True if this cache carries a hidden override-map blob not yet unlocked by any retained key (SPEC §9) — shown as a 🔒 hint, not a block. */
val FoundCache.hasPendingOverride: Boolean get() = pendingOverrideBlob != null
