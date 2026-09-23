package jtag

/** A domain quantity: raw representation is preserved at import;
 *  normalization happens only in the derived layer. Unconvertible
 *  values keep value=null and carry a diagnostic. */
data class Qty(
    val raw: String,
    val unit: String,
    val basis: String,          // coordinate basis, e.g. "sample-clock", "chain:t do-first"
    val value: Double?,         // normalized value (ns, bits, ...) or null
    val diagnostic: String? = null
) {
    val id: String get() = "qty[$raw $unit @$basis]"
    companion object {
        private val TIME = mapOf("ns" to 1.0, "us" to 1000.0, "ms" to 1_000_000.0, "s" to 1e9)
        fun time(raw: String, unit: String, basis: String = "sample-clock"): Qty {
            val v = raw.toDoubleOrNull()
            if (v == null) return Qty(raw, unit, basis, null, "unparseable numeric literal: '$raw'")
            val f = TIME[unit]
                ?: return Qty(raw, unit, basis, null, "no conversion to ns for unit '$unit'; value kept in place")
            return Qty(raw, unit, basis, v * f)
        }
        fun bits(raw: String, basis: String): Qty {
            val v = raw.toDoubleOrNull()
            return if (v == null) Qty(raw, "bit", basis, null, "unparseable bit count: '$raw'")
            else Qty(raw, "bit", basis, v)
        }
    }
}

/** Stable identity for every displayed value. */
data class Ref(val derivation: String, val node: String, val path: String) {
    override fun toString() = "$derivation/$node#$path"
}

enum class Severity { INFO, WARN, ERROR }

/** Machine-readable judgment / finding attached to derived output. */
data class Finding(
    val code: String,           // e.g. MID_SCAN_RESET, ILLEGAL_TRANSITION, IRLEN_AMBIGUOUS
    val severity: Severity,
    val message: String,
    val at: String              // Ref-like path of the affected object
) {
    fun toJson() = """{"code":"$code","severity":"$severity","message":${message.j()},"at":${at.j()}}"""
}

fun String.j(): String = buildString {
    append('"')
    for (c in this@j) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\")
        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> append(c)
    }
    append('"')
}

/** Tiny JSON reader for the JSON we ourselves write (objects/arrays/strings/numbers/bools/null). */
object Json {
    fun parse(s: String): Any? {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when {
                i >= s.length -> null
                s[i] == '"' -> str()
                s[i] == '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (i < s.length && s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }; i++; return m } }
                s[i] == '[' -> { i++; val l = mutableListOf<Any?>(); ws()
                    if (i < s.length && s[i] == ']') { i++; return l }
                    while (true) { l.add(value()); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }; i++; return l } }
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    s.substring(st, i).toDouble() }
            }
        }
        fun str(): String { i++; val b = StringBuilder()
            while (i < s.length) { val c = s[i++]
                if (c == '"') break
                if (c == '\\') { when (val e = s[i++]) { 'n' -> b.append('\n'); 't' -> b.append('\t')
                    'r' -> b.append('\r'); '"' -> b.append('"'); '\\' -> b.append('\\')
                    else -> b.append(e) } } else b.append(c) }
            return b.toString() }
        return value()
    }
    @Suppress("UNCHECKED_CAST")
    fun obj(s: String) = parse(s) as Map<String, Any?>
    fun str(v: Any?) = v as? String
    fun list(v: Any?) = v as? List<Any?> ?: emptyList()
    fun num(v: Any?) = (v as? Number)?.toDouble()
}
