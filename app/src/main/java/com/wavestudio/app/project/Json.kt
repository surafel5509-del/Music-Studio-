package com.wavestudio.app.project

/**
 * Minimal, dependency-free JSON reader/writer (RFC 8259 subset: no comments, UTF-16 strings).
 * It is used for the project file and for the small status strings returned by the native
 * engine, so the whole persistence layer runs in plain JVM unit tests.
 *
 * Values: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
class JsonException(message: String) : Exception(message)

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonException("trailing characters at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("top-level value is not an object")

    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    writeValue(sb, x, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    writeValue(sb, x, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append(']')
            }
            else -> throw JsonException("unsupported type ${v::class.java.name}")
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        // JSON has no NaN / Infinity: persist them as 0 rather than writing an unparsable file.
        if (d.isNaN() || d.isInfinite()) { sb.append('0'); return }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() { while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++ }

        fun value(depth: Int): Any? {
            if (depth > 64) throw JsonException("nesting too deep")
            if (pos >= s.length) throw JsonException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonException("expected key at $pos")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonException("expected ':' at $pos")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonException("unterminated object")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == '}') { pos++; return m }
                throw JsonException("expected ',' or '}' at $pos")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw JsonException("unterminated array")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == ']') { pos++; return l }
                throw JsonException("expected ',' or ']' at $pos")
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') { isDouble = true; pos++; while (pos < s.length && s[pos] in '0'..'9') pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (t == "-" || t.isEmpty()) throw JsonException("bad number at $start")
            return if (isDouble) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}

// ---- typed accessors with defaults (a missing or mistyped field never crashes loading) ----
@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()
fun Map<String, Any?>.str(k: String, d: String = ""): String = (this[k] as? String) ?: d
fun Map<String, Any?>.long(k: String, d: Long = 0L): Long = (this[k] as? Number)?.toLong() ?: d
fun Map<String, Any?>.int(k: String, d: Int = 0): Int = (this[k] as? Number)?.toInt() ?: d
fun Map<String, Any?>.float(k: String, d: Float = 0f): Float = (this[k] as? Number)?.toFloat() ?: d
fun Map<String, Any?>.bool(k: String, d: Boolean = false): Boolean = (this[k] as? Boolean) ?: d
