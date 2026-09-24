package jtag.model

import kotlinx.serialization.json.*

/** 领域量：导入时保留原始表示（raw + unit），派生层才做规范化。 */
data class Quantity(val raw: String, val unit: String?)

data class NormResult(val valuePs: Long?, val diagnostic: String?)

/** 时间量规范化到 ps；无法转换时保留原值并给出诊断。 */
fun normalizeTimeToPs(q: Quantity): NormResult {
    val v = q.raw.trim().toDoubleOrNull() ?: return NormResult(null, "unparseable-value:${q.raw}")
    return when (q.unit) {
        "ps" -> NormResult(v.toLong(), null)
        "ns" -> NormResult((v * 1e3).toLong(), null)
        "us" -> NormResult((v * 1e6).toLong(), null)
        "ms" -> NormResult((v * 1e9).toLong(), null)
        else -> NormResult(null, "unknown-unit:${q.unit}")
    }
}

fun JsonObject.str(k: String): String = (this[k] as JsonPrimitive).content
fun JsonObject.strOr(k: String, d: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: d
fun JsonObject.int(k: String): Int = (this[k] as JsonPrimitive).int
fun JsonObject.intOr(k: String, d: Int): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: d
fun JsonObject.arr(k: String): JsonArray = this[k] as JsonArray
fun JsonObject.obj(k: String): JsonObject = this[k] as JsonObject
fun JsonArray.ints(): List<Int> = this.map { it.jsonPrimitive.int }
fun JsonArray.strings(): List<String> = this.map { it.jsonPrimitive.content }
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> put(k, JsonNull)
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Boolean -> put(k, v)
        is JsonElement -> put(k, v)
        is List<*> -> put(k, JsonArray(v.map {
            when (it) {
                is String -> JsonPrimitive(it)
                is Int -> JsonPrimitive(it)
                is Long -> JsonPrimitive(it)
                is Boolean -> JsonPrimitive(it)
                is JsonElement -> it
                else -> JsonPrimitive(it.toString())
            }
        }))
        else -> put(k, v.toString())
    }
}
