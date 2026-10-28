package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DEFAULT_PAGE_SIZE = 50

internal fun page(
    rows: List<JsonObject>,
    limit: Int,
): JsonObject =
    buildJsonObject {
        put("items", JsonArray(rows.take(limit)))
        put("nextCursor", if (rows.size > limit) rows[limit - 1].getValue("id") else JsonNull)
    }

internal fun pageLimit(body: JsonObject): Int {
    val value =
        body["limit"]?.let {
            val primitive = it as? JsonPrimitive ?: fail("validation_error")
            if (primitive.isString) fail("validation_error")
            primitive.content.toIntOrNull() ?: fail("validation_error")
        } ?: DEFAULT_PAGE_SIZE
    if (value !in 1..100) fail("validation_error")
    return value
}

internal fun cursor(body: JsonObject): Long =
    if (body["cursor"] == null || body["cursor"] is JsonNull) 0 else body.id("cursor")
