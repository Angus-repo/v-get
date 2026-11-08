package com.vget.app.network

import kotlinx.serialization.json.*

// Typed accessors keep the established parsers' traversal semantics during migration.
internal val JsonElement.isJsonArray get() = this is JsonArray
internal val JsonElement.isJsonObject get() = this is JsonObject
internal val JsonElement.isJsonPrimitive get() = this is JsonPrimitive && this !is JsonNull
internal val JsonElement.asJsonArray get() = jsonArray
internal val JsonElement.asJsonObject get() = jsonObject
internal val JsonElement.asJsonPrimitive get() = jsonPrimitive
internal val JsonElement.asString get() = jsonPrimitive.content
internal val JsonElement.asLong get() = jsonPrimitive.content.toLong()
internal val JsonElement.asDouble get() = jsonPrimitive.content.toDouble()
internal val JsonElement.asBoolean get() = jsonPrimitive.content.toBooleanStrict()
internal fun JsonObject.has(key: String) = containsKey(key)
internal fun JsonObject.entrySet() = entries
internal fun parseJson(value: String): JsonElement = Json.parseToJsonElement(value)

/** Read exactly one JS object. Replace bare undefined only outside JSON strings. */
internal fun parseInitialState(value: String): JsonElement {
    val start = value.indexOfFirst { !it.isWhitespace() }
    require(start >= 0 && value[start] == '{')
    var depth = 0
    var quoted = false
    var escaped = false
    var index = start
    val json = StringBuilder()
    while (index < value.length) {
        val c = value[index]
        if (quoted) {
            json.append(c)
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else {
            when (c) {
                '"' -> { quoted = true; json.append(c) }
                '{', '[' -> { depth++; require(depth <= 100); json.append(c) }
                '}', ']' -> {
                    depth--; json.append(c)
                    if (depth == 0) return parseJson(json.toString())
                }
                else -> {
                    if (value.startsWith("undefined", index) &&
                        (index + 9 == value.length || value[index + 9].isWhitespace() || value[index + 9] in ",}]")) {
                        json.append("null"); index += 8
                    } else json.append(c)
                }
            }
        }
        index++
    }
    throw IllegalArgumentException("Incomplete page data")
}
