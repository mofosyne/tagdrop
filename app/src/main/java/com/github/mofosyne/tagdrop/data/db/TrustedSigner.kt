package com.github.mofosyne.tagdrop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TOFU cache for Verified Authorship (SPEC §10): `signer_id` (hex) -> pubkey/label, populated
 * the first time a signed code embeds `signer_pubkey` (key 34); later codes from the same
 * signer omit it and are verified against this cached copy ("Key caching" — SPEC §10). Mirrors
 * the web reader's IndexedDB `signers` store.
 */
@Entity(tableName = "trusted_signers")
data class TrustedSigner(
    @PrimaryKey val signerIdHex: String,  // hex-encoded 8-byte signer_id
    val publicKey: ByteArray,             // 1312-byte ML-DSA-44 public key
    val label: String?,                   // self-asserted, meaningful only as a consistent label (SPEC §10)
    val firstSeenAt: Long                 // epoch ms
) {
    override fun equals(other: Any?) = other is TrustedSigner && signerIdHex == other.signerIdHex
    override fun hashCode() = signerIdHex.hashCode()
}
