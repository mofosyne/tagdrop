package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class Base41Test {

    private fun roundTrip(bytes: ByteArray) {
        val encoded = Base41.encode(bytes)
        val decoded = Base41.decode(encoded)
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

    // Self-derived test vectors (no RFC for this alphabet — cross-checked against an
    // independent Python port of the same algorithm).
    @Test fun vectorAB() {
        val enc = Base41.encode("AB".toByteArray(Charsets.UTF_8))
        assertEquals("J-9", enc)
        assertArrayEquals("AB".toByteArray(Charsets.UTF_8), Base41.decode("J-9"))
    }

    @Test fun vectorHelloBang() {
        val enc = Base41.encode("Hello!!".toByteArray(Charsets.UTF_8))
        assertEquals("11B:KG\$*GX0", enc)
        assertArrayEquals("Hello!!".toByteArray(Charsets.UTF_8), Base41.decode("11B:KG\$*GX0"))
    }

    @Test fun vectorBase41() {
        val enc = Base41.encode("base-41".toByteArray(Charsets.UTF_8))
        assertEquals("B:ELNHA\$681", enc)
    }

    @Test fun encodedOutputNeverContainsUriUnsafeOrReservedCharacters() {
        val data = ByteArray(500) { it.toByte() }
        val encoded = Base41.encode(data)
        for (c in encoded) {
            assertFalse("encoded output must never contain '$c'", c == ' ' || c == '%' || c == '+' || c == '/')
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLength() {
        Base41.decode("A")  // length 1 is invalid (must be 0, 2, or multiple of 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCharacter() {
        Base41.decode("~~X")  // '~' is not in the Base41 alphabet
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSpaceCharacter() {
        Base41.decode("A B")  // space isn't in the Base41 alphabet, unlike Base45's
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPercentCharacter() {
        Base41.decode("A%B")  // '%' isn't in the Base41 alphabet, unlike Base45's
    }

    @Test fun decodeIsCaseInsensitive() {
        assertArrayEquals(Base41.decode("J-9"), Base41.decode("j-9"))
        assertArrayEquals("Hello!!".toByteArray(Charsets.UTF_8), Base41.decode("11b:kg\$*gx0"))
        assertArrayEquals("Hello!!".toByteArray(Charsets.UTF_8), Base41.decode("11B:kG\$*Gx0"))  // mixed case
    }

    @Test fun encodeAlwaysEmitsUppercase() {
        val encoded = Base41.encode(ByteArray(200) { it.toByte() })
        assertEquals(encoded, encoded.uppercase())
    }
}
