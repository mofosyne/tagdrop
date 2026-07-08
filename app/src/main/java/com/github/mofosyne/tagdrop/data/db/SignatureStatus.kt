package com.github.mofosyne.tagdrop.data.db

/**
 * Verified Authorship status (SPEC §10), computed once at scan time and persisted on
 * [FoundCache]/[ScannedPaper] — mirrors the web reader's `{status: ...}` verification result.
 */
object SignatureStatus {
    const val NONE = 0      // unsigned — the common case, most codes aren't signed
    const val PENDING = 1   // signed, but no signer_pubkey available yet (embedded or cached) to check against
    const val VERIFIED = 2
    const val INVALID = 3
}
