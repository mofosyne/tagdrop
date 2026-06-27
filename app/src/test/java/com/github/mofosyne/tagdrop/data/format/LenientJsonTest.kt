package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class LenientJsonTest {

    private fun describe(text: String): String = LenientJson.describe(text.toByteArray(Charsets.UTF_8))

    // ── Empty / blank input ───────────────────────────────────────────────────

    @Test fun emptyBytes() {
        assertEquals("(empty)", LenientJson.describe(ByteArray(0)))
    }

    @Test fun blankInput() {
        assertEquals("(empty)", describe("   \n\t"))
    }

    // ── Valid JSON ─────────────────────────────────────────────────────────────

    @Test fun simpleObject() {
        val out = describe("""{"a":1,"b":"two"}""")
        assertTrue(out.contains("\"a\": 1"))
        assertTrue(out.contains("\"b\": \"two\""))
    }

    @Test fun nestedArrayAndObject() {
        val out = describe("""{"list":[1,2.5,true,false,null,"x"]}""")
        assertTrue(out.contains("\"list\": ["))
        assertTrue(out.contains("2.5"))
        assertTrue(out.contains("true"))
        assertTrue(out.contains("false"))
        assertTrue(out.contains("null"))
        assertTrue(out.contains("\"x\""))
    }

    @Test fun emptyObjectAndArray() {
        assertEquals("{}", describe("{}"))
        assertEquals("[]", describe("[]"))
    }

    @Test fun negativeAndExponentNumbers() {
        val out = describe("[-1, -1.5e10, 0.03]")
        assertTrue(out.contains("-1"))
        assertTrue(out.contains("1.5"))
        assertTrue(out.contains("0.03"))
    }

    @Test fun stringEscapesDecodeCorrectly() {
        val out = describe("\"a\\nb\\tc\\u0041d\"")
        assertTrue(out.contains("a\nb\tcAd"))
    }

    // ── Best-effort / corrupted JSON ─────────────────────────────────────────

    @Test fun truncatedObjectShowsPartialContentAndMarker() {
        val out = describe("""{"a":1""")
        assertTrue(out.contains("\"a\": 1"))
        assertTrue(out.contains("⚠"))
        assertTrue(out.contains("expected ',' or '}'"))
    }

    @Test fun garbageMidArrayShowsItemsBeforeBreak() {
        val out = describe("[1,2,@@@]")
        assertTrue(out.contains("1"))
        assertTrue(out.contains("2"))
        assertTrue(out.contains("⚠"))
    }

    @Test fun invalidLiteralIsMarked() {
        val out = describe("tru")
        assertTrue(out.contains("⚠ invalid literal"))
    }

    @Test fun unterminatedStringIsMarked() {
        val out = describe("\"abc")
        assertTrue(out.contains("⚠ unterminated string"))
    }

    @Test fun missingKeyColonIsMarked() {
        val out = describe("""{"a" 1}""")
        assertTrue(out.contains("⚠ expected ':' after key"))
    }
}
