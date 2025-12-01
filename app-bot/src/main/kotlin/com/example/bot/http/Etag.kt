package com.example.bot.http

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * Generates a stable ETag using SHA-256 over `updatedAt|count|seed` and returns a base64url string without padding.
 *
 * The seed should capture all request-shaping inputs (filters, pagination, etc.) to avoid collisions between
 * different query variants.
 */
fun etagFor(updatedAt: Instant?, count: Int, seed: String): String {
    val source = "${updatedAt ?: Instant.EPOCH}|$count|$seed"
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Performs tolerant If-None-Match comparison supporting weak validators (W/) and optional quotes.
 */
fun matchesEtag(ifNoneMatch: String?, etag: String): Boolean {
    if (ifNoneMatch.isNullOrBlank()) return false

    return ifNoneMatch
        .split(',')
        .map { it.trim() }
        .any { candidate ->
            if (candidate == "*") return true
            val weakStripped = candidate.removePrefix("W/").removePrefix("w/").trim()
            val unquoted = weakStripped.trim('"')
            unquoted == etag
        }
}
