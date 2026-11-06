package com.example.adsh.engine

/**
 * Minimal JSON reader/writer for the dsh wire protocol.
 *
 * Kept dependency-free so protocol parsing and the chat reducer stay ordinary
 * JVM unit tests; the Host frames are small and only need standard JSON.
 */
object DshJson {
    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Float -> out.append(if (value.isFinite()) value.toString() else "null")
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key?.toString().orEmpty())
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.toList())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') {
                    out.append("\\u")
                    out.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) fail("trailing content")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) fail("unexpected end")
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else fail("unexpected '$char'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string")
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string")
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) fail("unterminated escape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("short unicode escape")
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: fail("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> fail("bad escape '\\$escaped'")
                        }
                    }
                    else -> {
                        if (char < ' ') fail("control character in string")
                        out.append(char)
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var floating = false
            if (index < text.length && text[index] == '.') {
                floating = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                floating = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (!floating) raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull() ?: fail("bad number '$raw'")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) fail("bad literal")
            index += word.length
            return value
        }

        private fun expect(char: Char) {
            if (peek() != char) fail("expected '$char'")
            index++
        }

        private fun peek(): Char {
            skipWhitespace()
            if (index >= text.length) fail("unexpected end")
            return text[index]
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
                index++
            }
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("JSON at $index: $message")
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asDshMap(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asDshMapOrEmpty(): Map<String, Any?> = asDshMap() ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asDshList(): List<Any?>? = this as? List<Any?>

fun Any?.dshString(): String? = this as? String

fun Any?.dshLong(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is Double -> toLong()
    is String -> toLongOrNull()
    else -> null
}

fun Any?.dshInt(): Int? = dshLong()?.toInt()

fun Any?.dshBool(): Boolean? = this as? Boolean
