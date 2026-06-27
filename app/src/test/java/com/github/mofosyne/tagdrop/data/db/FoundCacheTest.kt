package com.github.mofosyne.tagdrop.data.db

import org.junit.Assert.*
import org.junit.Test

class FoundCacheTest {

    private fun cache(
        pendingOverrideBlob: ByteArray? = null,
        pendingOverrideDeclared: Boolean = false
    ) = FoundCache(
        cacheId = "deadbeef",
        discoveredAt = 0L,
        hint = null,
        filename = null,
        mimeType = "text/plain",
        contentBytes = ByteArray(0),
        pendingOverrideBlob = pendingOverrideBlob,
        pendingOverrideDeclared = pendingOverrideDeclared
    )

    /** Plain content long enough to be a trial-decryption candidate must NOT show a lock hint. */
    @Test fun undeclaredCandidateDoesNotShowLockHint() {
        val c = cache(pendingOverrideBlob = ByteArray(28), pendingOverrideDeclared = false)
        assertTrue(c.hasPendingOverride)
        assertFalse(c.showsLockHint)
    }

    /** A genuinely declared (encryption hint / passphrase) override blob does show a lock hint. */
    @Test fun declaredCandidateShowsLockHint() {
        val c = cache(pendingOverrideBlob = ByteArray(28), pendingOverrideDeclared = true)
        assertTrue(c.hasPendingOverride)
        assertTrue(c.showsLockHint)
    }

    @Test fun noPendingBlobNeverShowsLockHint() {
        val c = cache(pendingOverrideBlob = null, pendingOverrideDeclared = true)
        assertFalse(c.hasPendingOverride)
        assertFalse(c.showsLockHint)
    }
}
