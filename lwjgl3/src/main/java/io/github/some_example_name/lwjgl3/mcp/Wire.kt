package io.github.some_example_name.lwjgl3.mcp

import kotlinx.serialization.json.*

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::json))
    else -> error("Unsupported JSON value")
}

internal fun obj(vararg fields: Pair<String, Any?>) = JsonObject(fields.associate { it.first to json(it.second) })
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.let {
    require(it.isString) { "$key must be a string" }
    it.content
}
internal fun JsonObject.number(key: String, default: Int, range: IntRange): Int {
    val value = get(key)?.jsonPrimitive?.int ?: default
    require(value in range) { "$key outside $range" }
    return value
}

internal fun result(data: JsonObject) = obj("content" to listOf(obj("type" to "text", "text" to data.toString())), "structuredContent" to data)
internal fun failure(reason: String) = obj("isError" to true, "content" to listOf(obj("type" to "text", "text" to reason.take(1200))))
