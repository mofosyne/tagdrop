package com.github.mofosyne.tagdrop.data.db

import org.junit.Assert.*
import org.junit.Test

class FoundCacheTest {

    private fun cache(
        pendingOverrideBlob: ByteArray? = null,
        pendingOverrideDeclared: Boolean = false,
        mimeType: String = "text/plain",
        contentBytes: ByteArray? = ByteArray(0)
    ) = FoundCache(
        cacheId = "deadbeef",
        discoveredAt = 0L,
        hint = null,
        filename = null,
        mimeType = mimeType,
        contentBytes = contentBytes,
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

    /** Raster and SVG image content are both thumbnail-eligible — AndroidSVG renders the latter. */
    @Test fun imageContentIsThumbnailEligible() {
        assertTrue(cache(mimeType = "image/png").isThumbnailEligible)
        assertTrue(cache(mimeType = "image/svg+xml").isThumbnailEligible)
    }

    @Test fun nonImageContentIsNotThumbnailEligible() {
        assertFalse(cache(mimeType = "text/plain").isThumbnailEligible)
        assertFalse(cache(mimeType = "application/pdf").isThumbnailEligible)
    }

    /** No resolved content (not [isOpenable]) means nothing to decode, regardless of mimeType. */
    @Test fun notOpenableIsNotThumbnailEligible() {
        assertFalse(cache(mimeType = "image/png", contentBytes = null).isThumbnailEligible)
    }
}
