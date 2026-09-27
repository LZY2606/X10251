package com.boarddebug.jtap.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val domainJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun jsonObject(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to jsonValue(it) } }.toMap())

fun jsonValue(value: Any?): JsonElementValue = when (value) {
    null -> JsonPrimitive(null)
    is JsonElementValue -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.map { it.key.toString() to jsonValue(it.value) }.toMap())
    is Iterable<*> -> JsonArray(value.map { jsonValue(it) })
    is Array<*> -> JsonArray(value.map { jsonValue(it) })
    else -> JsonPrimitive(value.toString())
}

typealias JsonElementValue = kotlinx.serialization.json.JsonElement

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.number(key: String): Number? = when (val value = this[key]) {
    is JsonPrimitive -> value.intOrNull ?: value.longOrNull ?: value.doubleOrNull
    else -> null
}
fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
