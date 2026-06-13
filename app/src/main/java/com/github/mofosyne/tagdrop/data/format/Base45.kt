package com.github.mofosyne.tagdrop.data.format

/**
 * RFC 9285 Base45 encoding.
 *
 * Base45 encodes 2 bytes → 3 alphanumeric characters, making it ideal for QR alphanumeric
 * mode (5.5 bits/char vs 8 bits/char in binary mode). This gives roughly the same capacity
 * as raw binary but with much better QR reader compatibility.
 *
 * Alphabet (45 chars): 0-9, A-Z, <space>, $, %, *, +, -, ., /, :
 */
object Base45 {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    private val DECODE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 3 + 1) / 2)
        var i = 0
        while (i + 1 < data.size) {
            val n = (data[i].toInt() and 0xFF) * 256 + (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[(n / 45) % 45])
            sb.append(ALPHABET[n / 2025])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[n / 45])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        require(s.length % 3 != 1) { "Invalid Base45 length ${s.length}: remainder must be 0 or 2" }
        val out = ByteArray((s.length * 2 + 2) / 3)
        var outIdx = 0
        var i = 0
        while (i + 2 < s.length) {
            val c0 = DECODE[s[i].code]
            val c1 = DECODE[s[i + 1].code]
            val c2 = DECODE[s[i + 2].code]
            require(c0 >= 0 && c1 >= 0 && c2 >= 0) { "Invalid Base45 character at position $i" }
            val n = c0 + c1 * 45 + c2 * 2025
            require(n <= 0xFFFF) { "Base45 value overflow at position $i" }
            out[outIdx++] = (n shr 8).toByte()
            out[outIdx++] = (n and 0xFF).toByte()
            i += 3
        }
        if (i < s.length) {
            val c0 = DECODE[s[i].code]
            val c1 = DECODE[s[i + 1].code]
            require(c0 >= 0 && c1 >= 0) { "Invalid Base45 character at position $i" }
            val n = c0 + c1 * 45
            require(n <= 0xFF) { "Base45 value overflow at position $i" }
            out[outIdx++] = n.toByte()
        }
        return out.copyOf(outIdx)
    }
}
