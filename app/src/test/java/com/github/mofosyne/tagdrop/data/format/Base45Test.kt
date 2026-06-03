package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class Base45Test {

    private fun roundTrip(bytes: ByteArray) {
        val encoded = Base45.encode(bytes)
        val decoded = Base45.decode(encoded)
        assertArrayEquals("round-trip failed for ${bytes.size} bytes", bytes, decoded)
    }

    @Test fun empty() = roundTrip(byteArrayOf())

    @Test fun singleByte() = roundTrip(byteArrayOf(0x00))
    @Test fun singleByteMax() = roundTrip(byteArrayOf(0xFF.toByte()))
    @Test fun singleByteAlpha() = roundTrip(byteArrayOf('A'.code.toByte()))

    @Test fun twoBytesMin() = roundTrip(byteArrayOf(0x00, 0x00))
    @Test fun twoBytesMax() = roundTrip(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
    @Test fun threeBytesOdd() = roundTrip(byteArrayOf(0x01, 0x02, 0x03))
    @Test fun fourBytesEven() = roundTrip(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))

    @Test fun eightBytes() = roundTrip(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    @Test fun allOnesLongSequence() {
        roundTrip(ByteArray(100) { 0xFF.toByte() })
    }

    @Test fun ascendingSequence() {
        roundTrip(ByteArray(64) { it.toByte() })
    }

    // RFC 9285 §4.4 test vectors
    @Test fun rfcTestVector1() {
        // "AB" -> "BB8"
        val enc = Base45.encode("AB".toByteArray(Charsets.UTF_8))
        assertEquals("BB8", enc)
        assertArrayEquals("AB".toByteArray(Charsets.UTF_8), Base45.decode("BB8"))
    }

    @Test fun rfcTestVector2() {
        // "Hello!!" -> "%69 VD92EX0"
        val enc = Base45.encode("Hello!!".toByteArray(Charsets.UTF_8))
        assertEquals("%69 VD92EX0", enc)
        assertArrayEquals("Hello!!".toByteArray(Charsets.UTF_8), Base45.decode("%69 VD92EX0"))
    }

    @Test fun rfcTestVector3() {
        // "base-45" -> "UJCLQE7W581"
        val enc = Base45.encode("base-45".toByteArray(Charsets.UTF_8))
        assertEquals("UJCLQE7W581", enc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLength() {
        Base45.decode("A")  // length 1 is invalid (must be 0, 2, or multiple of 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCharacter() {
        Base45.decode("~~X")  // '~' is not in the Base45 alphabet
    }
}
