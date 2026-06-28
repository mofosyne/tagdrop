package com.github.mofosyne.tagdrop.util

import org.junit.Assert.*
import org.junit.Test

/** Exercises [iconForMimeType]'s per-mimetype placeholder selection. */
class MimeIconsTest {

    @Test fun image() {
        assertEquals("🖼", iconForMimeType("image/png"))
        assertEquals("🖼", iconForMimeType("image/svg+xml"))
    }

    @Test fun audio() {
        assertEquals("🎵", iconForMimeType("audio/mpeg"))
    }

    @Test fun video() {
        assertEquals("🎬", iconForMimeType("video/mp4"))
    }

    @Test fun pdf() {
        assertEquals("📕", iconForMimeType("application/pdf"))
    }

    @Test fun calendar() {
        assertEquals("📅", iconForMimeType("text/calendar"))
    }

    @Test fun vcard() {
        assertEquals("👤", iconForMimeType("text/vcard"))
    }

    @Test fun genericText() {
        assertEquals("📄", iconForMimeType("text/plain"))
        assertEquals("📄", iconForMimeType("text/markdown"))
    }

    @Test fun fallsBackForUnknownTypes() {
        assertEquals("📦", iconForMimeType("application/octet-stream"))
        assertEquals("📦", iconForMimeType("application/json"))
    }
}
