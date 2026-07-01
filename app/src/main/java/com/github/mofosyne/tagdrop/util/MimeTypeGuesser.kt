package com.github.mofosyne.tagdrop.util

/**
 * Detects a MIME type from raw bytes using magic-byte signatures and lightweight text heuristics.
 * Used for raw QR content where no MIME type was declared by the author or by ZXing's classifier.
 *
 * Returns null when the bytes don't match any recognised signature — caller should fall back to
 * text/plain (if the bytes are valid UTF-8 text) or application/octet-stream (for binary).
 */
object MimeTypeGuesser {

    fun guess(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // Binary magic signatures (checked first — fast, unambiguous)
        if (bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
        if (bytes.startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (bytes.startsWith(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (bytes.startsWith(0x42, 0x4D)) return "image/bmp"
        if (bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return "image/webp"
        if (bytes.startsWith(0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        if (bytes.startsWith(0x50, 0x4B, 0x03, 0x04)) return "application/zip"
        if (bytes.startsWith(0x1F, 0x8B)) return "application/gzip"
        if (bytes.startsWith(0x4F, 0x67, 0x67, 0x53)) return "audio/ogg"
        if (bytes.startsWith(0x49, 0x44, 0x33) || bytes.startsWith(0xFF, 0xFB) ||
            bytes.startsWith(0xFF, 0xF3) || bytes.startsWith(0xFF, 0xF2)) return "audio/mpeg"
        if (bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte() &&
            bytes[10] == 0x56.toByte() && bytes[11] == 0x45.toByte()) return "audio/wav"

        // Text heuristics — only meaningful if the bytes are valid UTF-8
        val text = bytes.toUtf8OrNull() ?: return "application/octet-stream"
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<?xml", ignoreCase = true)) {
            if (trimmed.contains("<svg", ignoreCase = true)) return "image/svg+xml"
            return "application/xml"
        }
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)) return "text/html"
        if (trimmed.startsWith("<svg", ignoreCase = true)) return "image/svg+xml"
        if (looksLikeJson(trimmed)) return "application/json"
        return null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it].toByte() }
    }

    private fun ByteArray.toUtf8OrNull(): String? = try {
        val s = toString(Charsets.UTF_8)
        // Reject if re-encoding doesn't round-trip (indicates non-UTF-8 binary data)
        if (s.toByteArray(Charsets.UTF_8).contentEquals(this)) s else null
    } catch (_: Exception) { null }

    private fun looksLikeJson(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text[0]
        val last = text.trimEnd().lastOrNull()
        return (first == '{' && last == '}') || (first == '[' && last == ']')
    }
}
