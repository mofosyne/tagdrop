package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class MiniCborTest {

    // ── Round-trip helpers ────────────────────────────────────────────────────

    private fun encodeDecodeMap(pairs: List<Pair<Int, Any?>>): Map<Int, Any> =
        MiniCbor.decodeMap(MiniCbor.encodeMap(pairs))

    // ── Unsigned integers ─────────────────────────────────────────────────────

    @Test fun uintSmall() {
        val m = encodeDecodeMap(listOf(1 to 0, 2 to 23))
        assertEquals(0L, m[1])
        assertEquals(23L, m[2])
    }

    @Test fun uintOneByte() {
        val m = encodeDecodeMap(listOf(1 to 24, 2 to 255))
        assertEquals(24L, m[1])
        assertEquals(255L, m[2])
    }

    @Test fun uintTwoBytes() {
        val m = encodeDecodeMap(listOf(1 to 256, 2 to 65535))
        assertEquals(256L, m[1])
        assertEquals(65535L, m[2])
    }

    @Test fun uintFourBytes() {
        val m = encodeDecodeMap(listOf(1 to 65536, 2 to 0x00FFFFFFL))
        assertEquals(65536L, m[1])
        assertEquals(0x00FFFFFFL, m[2])
    }

    @Test fun uintLong() {
        val m = encodeDecodeMap(listOf(1 to 0xFFFFFFFFL))
        assertEquals(0xFFFFFFFFL, m[1])
    }

    // ── Byte strings ──────────────────────────────────────────────────────────

    @Test fun byteStringEmpty() {
        val m = encodeDecodeMap(listOf(1 to byteArrayOf()))
        assertArrayEquals(byteArrayOf(), m[1] as ByteArray)
    }

    @Test fun byteString8Bytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val m = encodeDecodeMap(listOf(2 to bytes))
        assertArrayEquals(bytes, m[2] as ByteArray)
    }

    @Test fun byteStringLong() {
        val bytes = ByteArray(300) { it.toByte() }
        val m = encodeDecodeMap(listOf(5 to bytes))
        assertArrayEquals(bytes, m[5] as ByteArray)
    }

    // ── Text strings ──────────────────────────────────────────────────────────

    @Test fun textEmpty() {
        val m = encodeDecodeMap(listOf(3 to ""))
        assertEquals("", m[3])
    }

    @Test fun textAscii() {
        val m = encodeDecodeMap(listOf(3 to "Hello, TagDrop!"))
        assertEquals("Hello, TagDrop!", m[3])
    }

    @Test fun textUnicode() {
        val m = encodeDecodeMap(listOf(3 to "📍 café"))
        assertEquals("📍 café", m[3])
    }

    // ── Null omission ─────────────────────────────────────────────────────────

    @Test fun nullsAreOmitted() {
        val m = encodeDecodeMap(listOf(1 to "present", 2 to null, 3 to "also present"))
        assertEquals(2, m.size)
        assertTrue(m.containsKey(1))
        assertFalse(m.containsKey(2))
        assertTrue(m.containsKey(3))
    }

    // ── Arrays ────────────────────────────────────────────────────────────────

    @Test fun emptyArray() {
        val m = encodeDecodeMap(listOf(15 to emptyList<Any>()))
        val arr = m[15] as List<*>
        assertTrue(arr.isEmpty())
    }

    @Test fun arrayOfInts() {
        val m = encodeDecodeMap(listOf(15 to listOf(1, 2, 3)))
        val arr = m[15] as List<*>
        assertEquals(listOf(1L, 2L, 3L), arr)
    }

    @Test fun arrayOfStrings() {
        val m = encodeDecodeMap(listOf(15 to listOf("alpha", "beta")))
        val arr = m[15] as List<*>
        assertEquals(listOf("alpha", "beta"), arr)
    }

    // ── Nested maps (CborMap inside array) ────────────────────────────────────

    @Test fun arrayOfNestedMaps() {
        val pairs = listOf(
            15 to listOf(
                MiniCbor.CborMap(listOf(20 to "slug-a", 21 to "text/html", 22 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))),
                MiniCbor.CborMap(listOf(20 to "slug-b", 21 to "image/svg+xml", 22 to byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)))
            )
        )
        val m = encodeDecodeMap(pairs)
        @Suppress("UNCHECKED_CAST")
        val arr = m[15] as List<Map<Int, Any>>
        assertEquals(2, arr.size)

        assertEquals("slug-a", arr[0][20])
        assertEquals("text/html", arr[0][21])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), arr[0][22] as ByteArray)

        assertEquals("slug-b", arr[1][20])
        assertEquals("image/svg+xml", arr[1][21])
    }

    @Test fun nestedMapWithNulls() {
        val pairs = listOf(
            16 to listOf(
                MiniCbor.CborMap(listOf(
                    3  to "hint text",
                    13 to null,            // set is null — should be omitted
                    14 to "some-slug",
                    23 to null             // paper_id is null — should be omitted
                ))
            )
        )
        val m = encodeDecodeMap(pairs)
        @Suppress("UNCHECKED_CAST")
        val arr = m[16] as List<Map<Int, Any>>
        val sub = arr[0]
        assertEquals(2, sub.size)   // only hint (3) and slug (14)
        assertEquals("hint text", sub[3])
        assertEquals("some-slug", sub[14])
        assertFalse(sub.containsKey(13))
        assertFalse(sub.containsKey(23))
    }

    // ── Float64 ───────────────────────────────────────────────────────────────

    @Test fun float64RoundTrip() {
        val m = encodeDecodeMap(listOf(26 to -33.8688, 27 to 151.2093))
        assertEquals(-33.8688, m[26] as Double, 0.0)
        assertEquals(151.2093, m[27] as Double, 0.0)
    }

    @Test fun float64Zero() {
        val m = encodeDecodeMap(listOf(26 to 0.0))
        assertEquals(0.0, m[26] as Double, 0.0)
    }

    @Test fun float64NegativeExtremes() {
        val m = encodeDecodeMap(listOf(26 to -90.0, 27 to -180.0))
        assertEquals(-90.0, m[26] as Double, 0.0)
        assertEquals(-180.0, m[27] as Double, 0.0)
    }

    // ── Mixed types in one map ────────────────────────────────────────────────

    // ── Truncation / malformed input ─────────────────────────────────────────

    @Test fun emptyInputThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            MiniCbor.decodeMap(ByteArray(0))
        }
    }

    @Test fun truncatedByteStringThrows() {
        // Map {1: bytes(4)} but the 4 bytes are missing from the stream
        val cbor = MiniCbor.encodeMap(listOf(1 to byteArrayOf(1, 2, 3, 4)))
        // Drop the last 3 bytes to simulate truncation
        assertThrows(IllegalArgumentException::class.java) {
            MiniCbor.decodeMap(cbor.copyOf(cbor.size - 3))
        }
    }

    @Test fun truncatedMultiByteIntThrows() {
        // Map {1: 300} — value 300 needs 2-byte encoding (0x19 0x01 0x2C)
        val cbor = MiniCbor.encodeMap(listOf(1 to 300))
        // Drop the last byte to truncate the 2-byte integer
        assertThrows(IllegalArgumentException::class.java) {
            MiniCbor.decodeMap(cbor.copyOf(cbor.size - 1))
        }
    }

    @Test fun mixedTypesRoundTrip() {
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val m = encodeDecodeMap(listOf(
            1 to 1,
            2 to bytes,
            3 to "hint",
            4 to "text/html",
            6 to 3,
            12 to null
        ))
        assertEquals(5, m.size)
        assertEquals(1L, m[1])
        assertArrayEquals(bytes, m[2] as ByteArray)
        assertEquals("hint", m[3])
        assertEquals("text/html", m[4])
        assertEquals(3L, m[6])
    }

    // ── CBOR Sequences (RFC 8742) ─────────────────────────────────────────────

    @Test fun encodeUIntIsOneByteForSmallValues() {
        assertArrayEquals(byteArrayOf(0x01), MiniCbor.encodeUInt(1))
        assertArrayEquals(byteArrayOf(0x00), MiniCbor.encodeUInt(0))
        assertArrayEquals(byteArrayOf(0x17), MiniCbor.encodeUInt(23))
    }

    @Test fun decodeSequenceEmpty() {
        assertEquals(emptyList<Any>(), MiniCbor.decodeSequence(ByteArray(0)))
    }

    @Test fun decodeSequenceVersionTypePayload() {
        val payload = MiniCbor.encodeMap(listOf(2 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 3 to "hint"))
        val seq = MiniCbor.encodeUInt(1) + MiniCbor.encodeUInt(0) + payload

        val items = MiniCbor.decodeSequence(seq)
        assertEquals(3, items.size)
        assertEquals(1L, items[0])
        assertEquals(0L, items[1])
        @Suppress("UNCHECKED_CAST")
        val map = items[2] as Map<Int, Any>
        assertEquals("hint", map[3])
    }

    // ── describeSequence (generic debug pretty-printer) ───────────────────────

    @Test fun describeSequenceEmptyBytes() {
        assertEquals("(empty)", MiniCbor.describeSequence(ByteArray(0)))
    }

    @Test fun describeSequenceInvalidCbor() {
        // Single byte 0xFF is unsupported (simple value 31 = break code) — shows as unrecognised hex.
        val description = MiniCbor.describeSequence(byteArrayOf(0xFF.toByte()))
        assertTrue(description.contains("unrecognised"))
        assertTrue(description.contains("ff"))
    }

    @Test fun describeSequencePlainText() {
        // Plain text: some bytes may decode as CBOR items, others won't — scanner keeps going.
        // Always shows a hex dump at the top regardless of how much decoded.
        val description = MiniCbor.describeSequence("Hello from TagDrop!".toByteArray(Charsets.UTF_8))
        assertTrue(description.contains("── hex"))
        assertTrue(description.contains("── CBOR scan"))
    }

    @Test fun describeSequenceShowsItemsDecodedBeforeTruncation() {
        // Two valid uint items, then a byte-string head claiming 4 bytes that never arrive.
        val truncated = MiniCbor.encodeUInt(1) + MiniCbor.encodeUInt(2) + byteArrayOf((2 shl 5 or 4).toByte())
        val description = MiniCbor.describeSequence(truncated)
        assertTrue(description.contains("item 0"))
        assertTrue(description.contains("item 1"))
        assertTrue(description.contains("unrecognised"))
    }

    @Test fun describeSequenceGarbageAfterValidMapShowsMapAndRemainingHex() {
        val cbor = MiniCbor.encodeMap(listOf(3 to "hint"))
        val garbage = byteArrayOf(0xFF.toByte(), 0x01, 0x02)
        val description = MiniCbor.describeSequence(cbor + garbage)
        assertTrue(description.contains("3: \"hint\""))
        assertTrue(description.contains("unrecognised"))
        assertTrue(description.contains("ff"))
    }

    @Test fun describeSequenceSingleMap() {
        val cbor = MiniCbor.encodeMap(listOf(3 to "hint text", 4 to "text/html"))
        val description = MiniCbor.describeSequence(cbor)
        assertTrue(description.contains("3: \"hint text\""))
        assertTrue(description.contains("4: \"text/html\""))
    }

    @Test fun describeSequenceMultipleItemsLabelled() {
        val seq = MiniCbor.encodeUInt(1) + MiniCbor.encodeUInt(0)
        val description = MiniCbor.describeSequence(seq)
        assertTrue(description.contains("item 0"))
        assertTrue(description.contains("item 1"))
    }

    @Test fun describeSequenceByteStringShowsHexAndLength() {
        val cbor = MiniCbor.encodeMap(listOf(2 to byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
        val description = MiniCbor.describeSequence(cbor)
        assertTrue(description.contains("de ad (2 bytes)"))
    }

    @Test fun describeSequenceNestedArrayAndMap() {
        val pairs = listOf(
            15 to listOf(
                MiniCbor.CborMap(listOf(20 to "slug-a", 21 to "text/html"))
            )
        )
        val cbor = MiniCbor.encodeMap(pairs)
        val description = MiniCbor.describeSequence(cbor)
        assertTrue(description.contains("15: ["))
        assertTrue(description.contains("20: \"slug-a\""))
        assertTrue(description.contains("21: \"text/html\""))
    }
}
