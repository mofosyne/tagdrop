package com.github.mofosyne.tagdrop.data.format

/**
 * TagDrop's Base41 encoding — a QR/Aztec/Data Matrix-safe, URI-safe text encoding for binary
 * data, packed identically to RFC 9285 Base45 (2 bytes -> 3 chars, 1 trailing byte -> 2 chars)
 * but over a 41-character alphabet instead of 45.
 *
 * The alphabet is the QR alphanumeric charset (0-9, A-Z, space, $%*+-./:) minus the 4 symbols
 * that are awkward outside QR: space and `%` aren't valid unescaped in a URI (RFC 3986), and
 * `+`/`/` carry special meaning in URLs (`+` as a space in query strings, `/` as a path
 * separator). 41 is the smallest alphabet for which 3 characters can still represent every
 * 16-bit value (41^3 = 68921 >= 65536; 40^3 = 64000 does not), so dropping these 4 characters
 * costs no extra characters per encoded byte versus Base45 — see SPEC.md §2.
 *
 * Credit: this is the "BYOA" (bring your own alphabet) variant of Philippe Majerus' Base41
 * scheme (https://github.com/sveljko/base41), using his QR/URL-safe alphabet. The tail
 * handling here (1 byte -> 2 chars) follows RFC 9285's approach rather than Majerus' padding
 * scheme, for consistency with the rest of this codec.
 *
 * Alphabet (41 chars): 0-9, A-Z, $, *, -, ., :
 *
 * Decoding is case-insensitive (lowercase letters are accepted as their uppercase
 * equivalent) to tolerate manual transcription; encoding always emits uppercase.
 */
object Base41 {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\$*-.:"

    private val DECODE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c ->
            table[c.code] = i
            if (c in 'A'..'Z') table[c.lowercaseChar().code] = i
        }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 3 + 1) / 2)
        var i = 0
        while (i + 1 < data.size) {
            val n = (data[i].toInt() and 0xFF) * 256 + (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % 41])
            sb.append(ALPHABET[(n / 41) % 41])
            sb.append(ALPHABET[n / 1681])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 41])
            sb.append(ALPHABET[n / 41])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        require(s.length % 3 != 1) { "Invalid Base41 length ${s.length}: remainder must be 0 or 2" }
        val out = ByteArray((s.length * 2 + 2) / 3)
        var outIdx = 0
        var i = 0
        while (i + 2 < s.length) {
            val c0 = DECODE[s[i].code]
            val c1 = DECODE[s[i + 1].code]
            val c2 = DECODE[s[i + 2].code]
            require(c0 >= 0 && c1 >= 0 && c2 >= 0) { "Invalid Base41 character at position $i" }
            val n = c0 + c1 * 41 + c2 * 1681
            require(n <= 0xFFFF) { "Base41 value overflow at position $i" }
            out[outIdx++] = (n shr 8).toByte()
            out[outIdx++] = (n and 0xFF).toByte()
            i += 3
        }
        if (i < s.length) {
            val c0 = DECODE[s[i].code]
            val c1 = DECODE[s[i + 1].code]
            require(c0 >= 0 && c1 >= 0) { "Invalid Base41 character at position $i" }
            val n = c0 + c1 * 41
            require(n <= 0xFF) { "Base41 value overflow at position $i" }
            out[outIdx++] = n.toByte()
        }
        return out.copyOf(outIdx)
    }
}
