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
    val lat: Double? = null,              // effective latitude (live GPS, or author-declared if it won — SPEC §3 prefer_declared_location)
    val lng: Double? = null,              // effective longitude, same resolution rule as lat
    val locationRadiusM: Double? = null,  // circle-of-uncertainty radius in meters; only set when lat/lng came from a declared (not live-GPS) source
    val locationLabel: String? = null,    // optional human-readable, non-coordinate location description, e.g. "🚋 Tram 40" (SPEC §4.2)
    val icon: String? = null,             // optional emoji icon
    val createdByMe: Boolean = false,     // true if authored in-app (Create Cache/Paper), not scanned
    val pendingOverrideBlob: ByteArray? = null,  // candidate encrypted override-map blob not yet unlocked by any retained key (SPEC §9)
    val pendingOverrideDeclared: Boolean = false, // true if the author declared this candidate (encryption hint or kdf_alg), not just size-eligible (SPEC §9)
    val pendingCompression: Int = 0,             // compression to apply when decoding pendingOverrideBlob's plaintext (SPEC §9)
    val wasEncrypted: Boolean = false,           // true if this cache ever carried an encrypted override-map blob (SPEC §9); stays true even after unlock
    val kdfAlg: Int = 0,                         // KDF algorithm for passphrase-derived key (0 = none, 1 = PBKDF2-SHA256); non-zero only while pendingOverrideBlob is set
    val kdfSalt: ByteArray? = null,              // 16-byte random salt for PBKDF2; present whenever kdfAlg != 0
    val inReplyTo: String? = null,               // hex-encoded cache_id/root_hash of the single parent this is replying to (SPEC §7)
    val title: String? = null,                   // optional short subject/caption, distinct from hint (SPEC §4.3, issue #35)
    val description: String? = null,             // optional content teaser / message body (SPEC §4.3, issue #35)
    val createdAt: Long? = null,                 // author-declared Unix timestamp (seconds) this payload was authored, unverified (SPEC §3); distinct from discoveredAt
    val pixelArt: Boolean = false,               // author hint to render this image with no smoothing/nearest-neighbor scaling (SPEC §7)
    val mimeTypeIsGuessed: Boolean = false       // true when mimeType was inferred from magic bytes / text heuristics, not declared by the author or paper manifest
) {
    override fun equals(other: Any?) = other is FoundCache && cacheId == other.cacheId
    override fun hashCode() = cacheId.hashCode()
}

/** [FoundCache.contentBytes] is the resolved clear-map content — always safe to render/export, even with a [hasPendingOverride] (SPEC §9). */
val FoundCache.isOpenable: Boolean get() = contentBytes != null

/** True if this cache carries a hidden override-map blob not yet unlocked by any retained key (SPEC §9) — internal trial-decryption candidate; see [showsLockHint] for the user-facing signal. */
val FoundCache.hasPendingOverride: Boolean get() = pendingOverrideBlob != null

/**
 * True if [hasPendingOverride] AND the author actually declared it (cosmetic `encryption` field
 * or a passphrase `kdf_alg`, SPEC §9) — the correct trigger for the 🔒 "locked" UI hint. Plain
 * content's content slot can also be ≥28 bytes and thus a [hasPendingOverride] trial-decryption
 * candidate (SPEC §9 "discovery, not declaration"), but that alone must not surface a lock badge,
 * or every scan would look "locked".
 */
val FoundCache.showsLockHint: Boolean get() = hasPendingOverride && pendingOverrideDeclared

/** True if [hasPendingOverride] and the blob is passphrase-derived (PBKDF2) — user can retry by entering the passphrase. */
val FoundCache.hasPendingPassphrase: Boolean get() = pendingOverrideBlob != null && kdfAlg != 0 && kdfSalt != null

/**
 * True if this cache's resolved content is image bytes that can be decoded into a list-row
 * thumbnail (see [com.github.mofosyne.tagdrop.util.ThumbnailLoader]). Includes `image/svg+xml`:
 * [android.graphics.BitmapFactory] can't decode it (it's XML, not raster), but AndroidSVG can.
 */
val FoundCache.isThumbnailEligible: Boolean get() = isOpenable && mimeType.startsWith("image/")
