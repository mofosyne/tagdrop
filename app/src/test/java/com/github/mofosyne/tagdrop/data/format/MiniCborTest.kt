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

    // ── stripTrailingKeys (SPEC §10 "signing happens last") ─────────────────────

    @Test fun stripTrailingKeysRemovesTrailingRun() {
        val withTrailing = MiniCbor.encodeMap(listOf(3 to "hint", 4 to "text/plain", 32 to 1, 35 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val withoutTrailing = MiniCbor.encodeMap(listOf(3 to "hint", 4 to "text/plain"))
        val stripped = MiniCbor.stripTrailingKeys(withTrailing, setOf(32, 33, 34, 35, 36))
        assertArrayEquals(withoutTrailing, stripped)
    }

    @Test fun stripTrailingKeysNoOpWhenNoneOfTheKeysPresent() {
        val cbor = MiniCbor.encodeMap(listOf(3 to "hint", 4 to "text/plain"))
        val stripped = MiniCbor.stripTrailingKeys(cbor, setOf(32, 33, 34, 35, 36))
        assertArrayEquals(cbor, stripped)
    }

    @Test fun stripTrailingKeysHandlesEmptyMap() {
        val cbor = MiniCbor.encodeMap(emptyList())
        val stripped = MiniCbor.stripTrailingKeys(cbor, setOf(32, 33, 34, 35, 36))
        assertArrayEquals(cbor, stripped)
    }

    @Test fun stripTrailingKeysAllKeysStripped() {
        val cbor = MiniCbor.encodeMap(listOf(32 to 1, 35 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val stripped = MiniCbor.stripTrailingKeys(cbor, setOf(32, 33, 34, 35, 36))
        assertArrayEquals(MiniCbor.encodeMap(emptyList()), stripped)
        assertTrue(MiniCbor.decodeMap(stripped).isEmpty())
    }

    @Test fun stripTrailingKeysOnlyDropsFromFirstMatchOnward() {
        // Pathological/adversarial input where a stripped key is followed by a non-stripped
        // one — not something an honest encoder produces, but stripTrailingKeys must still
        // behave safely: everything from the first match onward is dropped, including the
        // out-of-place survivor, rather than throwing or fabricating bytes.
        val cbor = MiniCbor.encodeMap(listOf(3 to "hint", 32 to 1, 4 to "text/plain"))
        val stripped = MiniCbor.stripTrailingKeys(cbor, setOf(32, 33, 34, 35, 36))
        assertArrayEquals(MiniCbor.encodeMap(listOf(3 to "hint")), stripped)
    }

    @Test fun stripTrailingKeysHeaderCountShrinksAcrossByteWidthBoundary() {
        // 24 pairs needs a 2-byte map header (argument 24 requires the 0x18 one-byte-length
        // form); stripping down to 23 survivors must shrink the header back to its 1-byte form.
        val pairs = (0 until 22).map { it to it } + listOf(32 to 1, 35 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val cbor = MiniCbor.encodeMap(pairs)
        val stripped = MiniCbor.stripTrailingKeys(cbor, setOf(32, 33, 34, 35, 36))
        val expected = MiniCbor.encodeMap((0 until 22).map { it to it })
        assertArrayEquals(expected, stripped)
    }

    // ── encodeRecord/decodeRecordPrefix payload slot + negative keys (QDEF-SPEC.md §3.1/§3.6, SPEC.md v11) ──

    @Test fun recordWithNoMapNoPayloadNoSubrecords() {
        val rec = MiniCbor.encodeRecord(9, emptyList())
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertEquals(9, decoded.typeId)
        assertTrue(decoded.record.isEmpty())
        assertNull(decoded.payload)
        assertTrue(decoded.subrecords.isEmpty())
    }

    @Test fun recordOmitsMapOnlyWhenFieldsListItselfIsEmpty() {
        // The map is omitted only when the caller declares zero fields at all for this call
        // (e.g. Compress Wrapper) -- NOT whenever every declared field's value happens to be
        // null (a Type that always declares fields, e.g. Content Extension, must keep writing
        // `{}` so stripKeys/signature-hash formulas always find a stable map to work with).
        val declaredFieldsAllNull = MiniCbor.encodeRecord(8, listOf(1 to null, 2 to null), payload = byteArrayOf(1, 2, 3))
        val noFieldsDeclaredAtAll = MiniCbor.encodeRecord(8, emptyList(), payload = byteArrayOf(1, 2, 3))
        assertFalse(declaredFieldsAllNull.contentEquals(noFieldsDeclaredAtAll)) // the former still writes `{}`, one byte longer
        val decodedWithEmptyMap = MiniCbor.decodeRecordPrefix(declaredFieldsAllNull)!!
        assertTrue(decodedWithEmptyMap.record.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), decodedWithEmptyMap.payload)
        val decodedWithNoMap = MiniCbor.decodeRecordPrefix(noFieldsDeclaredAtAll)!!
        assertTrue(decodedWithNoMap.record.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), decodedWithNoMap.payload)
    }

    @Test fun recordWithMapAndPayloadNoSubrecords() {
        val rec = MiniCbor.encodeRecord(6, listOf(0 to "text/plain"), payload = "hello".toByteArray())
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertEquals("text/plain", decoded.record[0])
        assertArrayEquals("hello".toByteArray(), decoded.payload)
        assertTrue(decoded.subrecords.isEmpty())
    }

    @Test fun recordWithMapAndPayloadAndSubrecord() {
        val sub = MiniCbor.encodeRecord(3, listOf(3 to byteArrayOf(9, 9)))
        val rec = MiniCbor.encodeRecord(6, listOf(0 to "text/plain"), listOf(sub), payload = "hello".toByteArray())
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertArrayEquals("hello".toByteArray(), decoded.payload)
        assertEquals(1, decoded.subrecords.size)
        assertEquals(3, decoded.subrecords[0].typeId)
    }

    @Test fun recordWithMapAndSubrecordButNoPayload() {
        // No mandatory-null marker needed (SPEC.md v11): an array right after the map is
        // unconditionally subrecord 0, never mistaken for the payload.
        val sub = MiniCbor.encodeRecord(6, listOf(0 to "text/plain"), payload = "x".toByteArray())
        val rec = MiniCbor.encodeRecord(14, listOf(0 to "text/plain", 3 to "photo.png"), listOf(sub))
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertNull(decoded.payload)
        assertEquals(1, decoded.subrecords.size)
        assertEquals(6, decoded.subrecords[0].typeId)
        assertArrayEquals("x".toByteArray(), decoded.subrecords[0].payload)
    }

    @Test fun recordWithNegativeCommonFieldKeys() {
        // -11 Content Hash, -7 Label, -15 Filename (QDEF-SPEC.md §3.6) alongside an ordinary
        // non-negative Type-specific key (0, mediaType).
        val rec = MiniCbor.encodeRecord(14, listOf(
            0 to "image/png", -11 to byteArrayOf(0x12, 1, 2, 3, 4), -15 to "photo.png", -7 to "Beach sunset"
        ))
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertEquals("image/png", decoded.record[0])
        assertArrayEquals(byteArrayOf(0x12, 1, 2, 3, 4), decoded.record[-11] as ByteArray)
        assertEquals("photo.png", decoded.record[-15])
        assertEquals("Beach sunset", decoded.record[-7])
    }

    @Test fun fieldsSortByCanonicalEncodedKeyBytesOnTheWire() {
        // encodeRecord sorts fields by RFC 8949 §4.2.1 canonical order -- comparing each key's
        // own ENCODED bytes (shorter first, then bytewise), not by plain integer value
        // (QDEF-SPEC.md §3.4) -- regardless of input order, so this must land the same way in
        // the actual encoded bytes, not just in the decoded Map. (Plain encodeMap preserves
        // caller order -- encodeRecord does the sorting, exercised here since that's the real
        // call path every Record goes through.)
        val declaredNegativeFirst = MiniCbor.encodeRecord(14, listOf(-11 to byteArrayOf(1), 0 to "image/png"))
        val declaredPositiveFirst = MiniCbor.encodeRecord(14, listOf(0 to "image/png", -11 to byteArrayOf(1)))
        assertArrayEquals(declaredNegativeFirst, declaredPositiveFirst)
        // Confirm the non-negative key's byte (major 0, argument 0 -> 0x00) really does precede
        // the negative key's byte (major 1, argument 10 -> 0x2a) in the map header's pair order:
        // both encode to a single byte, so canonical order falls back to a bytewise compare, and
        // 0x00 < 0x2a -- despite -11 < 0 as plain integers, it sorts *after* 0 on the wire, since
        // major type 1's encoded byte is always numerically larger than major type 0's for the
        // same-magnitude argument.
        val posKeyByte = 0 // major 0, argument 0 -> 0x00
        val firstKeyOffset = 3 // [array header][typeId byte][map header byte] precede the map's own pairs
        assertEquals(posKeyByte, declaredNegativeFirst[firstKeyOffset].toInt() and 0xFF)
    }

    @Test fun compressWrapperShapeNoMapJustPayload() {
        // The actual Compress Wrapper shape adopted in SPEC.md v11: [8, deflated_bytes], no map at all.
        val deflated = byteArrayOf(0x78, 0x9c.toByte(), 1, 2, 3)
        val rec = MiniCbor.encodeRecord(8, emptyList(), payload = deflated)
        val decoded = MiniCbor.decodeRecordPrefix(rec)!!
        assertEquals(8, decoded.typeId)
        assertTrue(decoded.record.isEmpty())
        assertArrayEquals(deflated, decoded.payload)
    }

    @Test fun stripSubrecordTypePreservesPayloadSlot() {
        val sig = MiniCbor.encodeRecord(3, listOf(3 to byteArrayOf(9, 9)))
        val rec = MiniCbor.encodeRecord(6, listOf(0 to "text/plain"), listOf(sig), payload = "hello".toByteArray())
        val stripped = MiniCbor.stripSubrecordType(rec, 3)
        val decoded = MiniCbor.decodeRecordPrefix(stripped)!!
        assertArrayEquals("hello".toByteArray(), decoded.payload)
        assertTrue(decoded.subrecords.isEmpty())
    }

    @Test fun stripAllSubrecordsPreservesPayloadSlot() {
        val sub = MiniCbor.encodeRecord(6, listOf(0 to "text/plain"), payload = "x".toByteArray())
        val rec = MiniCbor.encodeRecord(14, listOf(0 to "text/plain"), listOf(sub))
        val stripped = MiniCbor.stripAllSubrecords(rec)
        val decoded = MiniCbor.decodeRecordPrefix(stripped)!!
        assertTrue(decoded.subrecords.isEmpty())
        assertEquals("text/plain", decoded.record[0])
    }

    @Test fun stripKeysHandlesNegativeKeysCorrectly() {
        // A stripped positive key must not disturb a surviving negative key, and vice versa.
        val rec = MiniCbor.encodeRecord(1, listOf(45 to 1L, -13 to "https://example.com/x"))
        val stripped = MiniCbor.stripKeys(rec, setOf(45))
        val decoded = MiniCbor.decodeRecordPrefix(stripped)!!
        assertFalse(decoded.record.containsKey(45))
        assertEquals("https://example.com/x", decoded.record[-13])
    }
}
