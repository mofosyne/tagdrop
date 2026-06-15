package com.github.mofosyne.tagdrop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A `key_material` (SPEC §9) the app has learned with `retain_key = true`, kept so it can
 * be tried against encrypted content scanned in a later session ("discovery, not
 * declaration" — SPEC §9).
 */
@Entity(tableName = "retained_keys")
data class RetainedKey(
    @PrimaryKey val keyHex: String,  // hex-encoded 32-byte AES-256-GCM key_material
    val discoveredAt: Long,          // epoch ms
    val hint: String?                // optional hint from the code that carried this key
)
