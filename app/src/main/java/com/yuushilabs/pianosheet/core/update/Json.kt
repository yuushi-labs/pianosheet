package com.yuushilabs.pianosheet.core.update

/**
 * The smallest JSON reader that can open the documents the update path meets.
 *
 * The app deliberately ships no JSON library (`org.json` is an Android stub a
 * JVM unit test cannot run), and only two shapes are needed: the GitHub "latest
 * release" object and an error object such as `{"message":"Not Found"}`.  With
 * the reader here the parsing and the version decision stay plain Kotlin and
 * can be tested without a device.
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue

    /** `value[key]`, or null when this is not an object or the key is absent. */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    fun asString(): String? = (this as? Str)?.value

    fun asLong(): Long? = when (this) {
        is Num -> value.toLong()
        is Str -> value.trim().toLongOrNull()
        else -> null
    }

    fun asBoolean(): Boolean? = (this as? Bool)?.value

    fun asArray(): List<JsonValue> = (this as? Arr)?.items ?: emptyList()
}

class JsonSyntaxException(message: String) : Exception(message)

object Json {

    /** The parsed document, or null when [text] is not one whole JSON value. */
    fun parseOrNull(text: String): JsonValue? = try {
        JsonReader(text).readWholeDocument()
    } catch (_: JsonSyntaxException) {
        null
    }
}

private class JsonReader(private val text: String) {

    private var pos = 0

    fun readWholeDocument(): JsonValue {
        val value = readValue()
        skipSpace()
        if (pos != text.length) fail("trailing content")
        return value
    }

    private fun readValue(): JsonValue {
        skipSpace()
        if (pos >= text.length) fail("unexpected end")
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipSpace()
        if (peek() == '}') {
            pos++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipSpace()
            if (peek() != '"') fail("object key must be a string")
            val key = readString()
            skipSpace()
            expect(':')
            fields[key] = readValue()
            skipSpace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.Obj(fields)
                }

                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipSpace()
        if (peek() == ']') {
            pos++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue())
            skipSpace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.Arr(items)
                }

                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            when (val c = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val escape = text[pos++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("short \\u escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                            out.append(code.toChar())
                            pos += 4
                        }

                        else -> fail("bad escape '\\$escape'")
                    }
                }

                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        val raw = text.substring(start, pos)
        val number = raw.toDoubleOrNull() ?: fail("bad number '$raw'")
        return JsonValue.Num(number)
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, pos)) fail("expected $literal")
        pos += literal.length
        return value
    }

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("unexpected end")
        return text[pos]
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun fail(message: String): Nothing =
        throw JsonSyntaxException("$message at $pos")
}
