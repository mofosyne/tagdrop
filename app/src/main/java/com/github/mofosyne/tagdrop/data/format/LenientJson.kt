package com.github.mofosyne.tagdrop.data.format

/**
 * Minimal best-effort JSON parser/pretty-printer for the "View as -> JSON" discovery mode in
 * ViewDataUriActivity (mirrors [MiniCbor.describeSequence]'s role for CBOR). A syntax error
 * doesn't abort the whole parse -- the container being read at the point of failure is returned
 * as-is, with the error recorded as a marker value in its place, so a truncated or corrupted
 * JSON-like blob still shows whatever came before the break instead of nothing at all.
 */
object LenientJson {

    private class ParseError(val message: String, val offset: Int)

    fun describe(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(empty)"
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return "(empty)"
        val parsed = Parser(text).parseTopLevel()
        if (parsed is ParseError) {
            // Not JSON — show the error then the bytes so the user has something to work with.
            return buildString {
                appendLine("⚠ ${parsed.message} (at byte ${parsed.offset})")
                appendLine()
                appendLine("hex dump:")
                appendLine(hexDump(bytes))
                // Also show the raw text if the bytes are valid UTF-8 (common for plain-text QRs).
                val asText = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
                if (asText != null && asText.any { it.isLetterOrDigit() }) {
                    appendLine()
                    appendLine("as text:")
                    append(asText)
                }
            }
        }
        return buildString { describeValue(parsed, 0, this) }
    }

    private fun hexDump(bytes: ByteArray): String = buildString {
        val w = 16
        for (offset in bytes.indices step w) {
            val end = minOf(offset + w, bytes.size)
            append("%08x  ".format(offset))
            for (i in offset until offset + w) {
                append(if (i < end) "%02x ".format(bytes[i]) else "   ")
                if (i - offset == 7) append(' ')
            }
            append(' ')
            for (i in offset until end) {
                val c = bytes[i].toInt() and 0xFF
                append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            appendLine()
        }
    }

    private fun describeValue(value: Any?, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        when (value) {
            null -> out.append("null")
            is ParseError -> out.append("⚠ ${value.message} (at byte ${value.offset})")
            is String -> out.append("\"$value\"")
            is Map<*, *> -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.appendLine("{")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append("$pad  \"$k\": ")
                    describeValue(v, indent + 1, out)
                    if (i < value.size - 1) out.append(",")
                    out.appendLine()
                }
                out.append("$pad}")
            }
            is List<*> -> {
                if (value.isEmpty()) { out.append("[]"); return }
                out.appendLine("[")
                value.forEachIndexed { i, v ->
                    out.append("$pad  ")
                    describeValue(v, indent + 1, out)
                    if (i < value.size - 1) out.append(",")
                    out.appendLine()
                }
                out.append("$pad]")
            }
            else -> out.append(value.toString())
        }
    }

    /**
     * Recursive-descent parser that degrades gracefully: a syntax error returns the object/array
     * built so far (with a [ParseError] marker in place of the broken entry) instead of throwing.
     */
    private class Parser(private val text: String) {
        private var pos = 0

        fun parseTopLevel(): Any? {
            skipWs()
            return if (pos >= text.length) ParseError("empty input", pos) else parseValue()
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) return ParseError("unexpected end of input", pos)
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f', 'n' -> parseLiteral()
                else -> if (text[pos] == '-' || text[pos].isDigit()) parseNumber()
                    else ParseError("unexpected character '${text[pos]}'", pos)
            }
        }

        private fun parseObject(): Any {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') {
                    map["⚠"] = ParseError("expected string key", pos)
                    return map
                }
                val parsedKey = parseString()
                if (parsedKey is ParseError) { map["⚠"] = parsedKey; return map }
                val key = parsedKey as String
                skipWs()
                if (pos >= text.length || text[pos] != ':') {
                    map[key] = ParseError("expected ':' after key", pos)
                    return map
                }
                pos++ // ':'
                val value = parseValue()
                map[key] = value
                if (value is ParseError) return map
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == '}' -> { pos++; return map }
                    else -> { map["⚠"] = ParseError("expected ',' or '}'", pos); return map }
                }
            }
        }

        private fun parseArray(): Any {
            val list = mutableListOf<Any?>()
            pos++ // '['
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                val value = parseValue()
                list.add(value)
                if (value is ParseError) return list
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == ']' -> { pos++; return list }
                    else -> { list.add(ParseError("expected ',' or ']'", pos)); return list }
                }
            }
        }

        private fun parseString(): Any {
            val start = pos
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) return ParseError("unterminated string", start)
                val c = text[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        if (pos >= text.length) return ParseError("unterminated escape", pos)
                        when (val esc = text[pos]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) return ParseError("truncated unicode escape", pos)
                                val code = text.substring(pos + 1, pos + 5).toIntOrNull(16)
                                    ?: return ParseError("invalid unicode escape", pos)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> return ParseError("invalid escape '\\$esc'", pos)
                        }
                        pos++
                    }
                    c.code < 0x20 -> return ParseError("control character in string", pos)
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length || !text[pos].isDigit()) return ParseError("invalid number", start)
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true; pos++
                if (pos >= text.length || !text[pos].isDigit()) return ParseError("invalid number", start)
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || !text[pos].isDigit()) return ParseError("invalid number", start)
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            return if (isDouble) raw.toDouble() else (raw.toLongOrNull() ?: raw.toDouble())
        }

        private fun parseLiteral(): Any? = when {
            text.regionMatches(pos, "true", 0, 4) -> { pos += 4; true }
            text.regionMatches(pos, "false", 0, 5) -> { pos += 5; false }
            text.regionMatches(pos, "null", 0, 4) -> { pos += 4; null }
            else -> ParseError("invalid literal", pos)
        }

        private fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
    }
}
