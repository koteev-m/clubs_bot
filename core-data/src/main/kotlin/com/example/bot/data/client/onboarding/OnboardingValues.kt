package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal typealias DbRow = Map<String, String?>

internal const val MANAGE = "client.application.manage"
internal const val READ_CLUB = "client.club.read"
internal const val WRITE_PROFILE = "client.club.profile.write"
internal const val WRITE_LAYOUT = "client.club.layout.write"
internal val onboardingJson = Json
private const val TOKEN_BYTES = 32

internal fun fail(code: String): Nothing = throw OnboardingFailure(code)

internal fun digest(value: String): String =
    MessageDigest
        .getInstance(
            "SHA-256",
        ).digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun randomToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) },
    )

internal fun canonical(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

internal fun JsonObject.string(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail("validation_error")
    }

internal fun JsonObject.id(key: String): Long =
    string(key)?.takeIf { Regex("[1-9][0-9]*").matches(it) }?.toLongOrNull() ?: fail("validation_error")

internal fun JsonObject.text(
    key: String,
    max: Int,
    required: Boolean = false,
): String {
    val value = string(key)?.trim().orEmpty()
    val missing = required && value.isEmpty()
    if (value.length > max || missing || value.any { it == '\u0000' }) fail("validation_error")
    return value
}

internal fun JsonObject.only(keys: Collection<String>) {
    if (this.keys.any { it !in keys }) fail("validation_error")
}

internal fun DbRow.long(key: String): Long = checkNotNull(getValue(key)).toLong()

internal fun DbRow.active(key: String = "is_active"): Boolean = this[key].equals("true", true) || this[key] == "t"

internal fun DbRow.instant(key: String): Instant =
    java.time.OffsetDateTime
        .parse(checkNotNull(getValue(key)).replace(' ', 'T'))
        .toInstant()

internal fun jsonNullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
