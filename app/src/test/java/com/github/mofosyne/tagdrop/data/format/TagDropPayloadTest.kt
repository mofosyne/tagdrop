package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import org.junit.Assert.*
import org.junit.Test

class TagDropPayloadTest {

    private fun paper(rootHash: String, set: String? = null, slug: String? = null) = ScannedPaper(
        rootHash = rootHash, scannedAt = 0L, label = null, set = set, slug = slug,
        cborBytes = ByteArray(0)
    )

    // ── matchesScannedPaper ───────────────────────────────────────────────────

    @Test fun matchesScannedPaperByPaperId() {
        val target = paper(rootHash = "0011223344556677", set = "other-set", slug = "other-slug")
        val related = TagDropPayload.RelatedPaper(
            hint = "hint", paperId = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        )
        assertTrue(related.matchesScannedPaper(target))
    }

    @Test fun matchesScannedPaperBySetAndSlugWhenPaperIdAbsent() {
        val target = paper(rootHash = "ffffffffffffffff", set = "sunset-trail", slug = "oak-tree")
        val related = TagDropPayload.RelatedPaper(hint = "hint", set = "sunset-trail", slug = "oak-tree")
        assertTrue(related.matchesScannedPaper(target))
    }

    @Test fun matchesScannedPaperFallsBackToSetAndSlugWhenPaperIdIsStale() {
        // Root hash changes whenever a referenced paper is re-scanned/updated, but a
        // re-scanned replacement keeps the same set+slug — that's the durable cross-reference.
        val target = paper(rootHash = "ffffffffffffffff", set = "sunset-trail", slug = "oak-tree")
        val related = TagDropPayload.RelatedPaper(
            hint = "hint", set = "sunset-trail", slug = "oak-tree",
            paperId = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1) // stale — doesn't match target.rootHash
        )
        assertTrue(related.matchesScannedPaper(target))
    }

    @Test fun matchesScannedPaperFalseWhenNothingMatches() {
        val target = paper(rootHash = "ffffffffffffffff", set = "sunset-trail", slug = "oak-tree")
        val related = TagDropPayload.RelatedPaper(hint = "hint", set = "other-trail", slug = "oak-tree")
        assertFalse(related.matchesScannedPaper(target))
    }

    @Test fun matchesScannedPaperFalseWhenSlugMissingOnBothSides() {
        val target = paper(rootHash = "ffffffffffffffff", set = "sunset-trail", slug = null)
        val related = TagDropPayload.RelatedPaper(hint = "hint", set = "sunset-trail", slug = null)
        assertFalse(related.matchesScannedPaper(target))
    }
}
