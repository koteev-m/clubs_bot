package com.example.bot.plugins

import com.example.bot.data.privacy.LegacyClubAccess
import com.example.bot.data.privacy.LegacyReference
import com.example.bot.data.privacy.LegacyResource
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException

private val containmentKey = AttributeKey<PrivateClubContainment>("private.club.containment")

private class PrivateClubContainment(
    val access: LegacyClubAccess,
    val bookingClubId: (Long) -> Long?,
) {
    suspend fun denied(references: Collection<LegacyReference>): Boolean {
        val memoryClubs =
            references.filter { it.kind == LegacyResource.BOOKING }.mapNotNull {
                it.id
                    .toLongOrNull()
                    ?.let(bookingClubId)
                    ?.let { id -> LegacyReference(LegacyResource.CLUB, id) }
            }
        return access.containsMappedResource(references + memoryClubs)
    }
}

/** Production must install before route registration; applies even to HEAD, cached assets and GLOBAL roles. */
internal fun Application.installPrivateClubContainment(
    access: LegacyClubAccess,
    bookingClubId: (Long) -> Long?,
) {
    check(!attributes.contains(containmentKey)) { "Private club containment already installed" }
    attributes.put(containmentKey, PrivateClubContainment(access, bookingClubId))
    intercept(ApplicationCallPipeline.Monitoring) {
        val references = call.legacyPathReferences()
        if (references.isNotEmpty() && !call.requireLegacyResources(references)) finish()
    }
}

/** Body-owned references are supplied explicitly after decoding, before content lookups or side effects. */
internal suspend fun ApplicationCall.requireLegacyResources(vararg references: LegacyReference): Boolean =
    requireLegacyResources(references.toList())

internal suspend fun ApplicationCall.requireLegacyResources(references: Collection<LegacyReference>): Boolean {
    // Isolated route tests may inject synthetic repositories without installing production data wiring.
    val containment = application.attributes.getOrNull(containmentKey) ?: return true
    return try {
        if (containment.denied(references)) {
            ensureMiniAppNoStoreHeaders()
            respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
            false
        } else {
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ensureMiniAppNoStoreHeaders()
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
        false
    }
}

private fun ApplicationCall.legacyPathReferences(): List<LegacyReference> {
    val segments =
        request
            .path()
            .split('/')
            .filter(String::isNotEmpty)
            .map { it.decodeURLPart() }
    return when {
        segments.take(2) == listOf("api", "client") ||
            segments.take(RESOURCE_PREFIX_SIZE) == listOf("api", "platform", "onboarding") -> emptyList()
        segments.take(2) == listOf("assets", "layouts") ->
            segments.getOrNull(2)?.let { listOf(LegacyReference(LegacyResource.CLUB, it)) }.orEmpty()
        segments.firstOrNull() == "api" -> apiReferences(segments)
        else -> emptyList()
    }
}

private fun ApplicationCall.apiReferences(segments: List<String>): List<LegacyReference> {
    val references = mutableListOf<LegacyReference>()
    for ((index, segment) in segments.withIndex()) {
        val id = segments.getOrNull(index + 1) ?: continue
        val resource =
            when (segment) {
                "clubs" -> LegacyResource.CLUB
                "events" -> LegacyResource.EVENT
                "halls" -> LegacyResource.HALL
                "tables" ->
                    if (segments.take(RESOURCE_PREFIX_SIZE) == listOf("api", "admin", "tables") ||
                        "halls" in segments
                    ) {
                        LegacyResource.HALL_TABLE
                    } else {
                        LegacyResource.TABLE
                    }
                "bookings" -> LegacyResource.BOOKING
                "tickets" -> if (id == "my") null else LegacyResource.TICKET
                "my" ->
                    if (segments.take(RESOURCE_PREFIX_SIZE) ==
                        listOf("api", "support", "tickets")
                    ) {
                        LegacyResource.TICKET
                    } else {
                        null
                    }
                "guest-lists" -> LegacyResource.GUEST_LIST
                "entries" -> LegacyResource.GUEST_ENTRY
                "items" -> if ("music" in segments) LegacyResource.MUSIC_ITEM else null
                "playlists" -> if ("music" in segments) LegacyResource.MUSIC_PLAYLIST else null
                "battles" -> if ("music" in segments) LegacyResource.MUSIC_BATTLE else null
                "sessions" -> LegacyResource.SESSION
                "waitlist" -> LegacyResource.WAITLIST
                "sets" -> if ("music" in segments) LegacyResource.MUSIC_PLAYLIST else null
                else -> null
            }
        if (resource != null && (id.toLongOrNull() != null || UUID_ID.matches(id))) {
            references += LegacyReference(resource, id)
        }
    }
    for ((parameter, resource) in queryResources) {
        request.queryParameters.getAll(parameter).orEmpty().forEach { id ->
            if (id.toLongOrNull() != null || UUID_ID.matches(id)) references += LegacyReference(resource, id)
        }
    }
    return references
}

private const val RESOURCE_PREFIX_SIZE = 3

private val UUID_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val queryResources =
    mapOf(
        "clubId" to LegacyResource.CLUB,
        "club" to LegacyResource.CLUB,
        "eventId" to LegacyResource.EVENT,
        "hallId" to LegacyResource.HALL,
        "listId" to LegacyResource.GUEST_LIST,
        "entryId" to LegacyResource.GUEST_ENTRY,
        "ticketId" to LegacyResource.TICKET,
        "bookingId" to LegacyResource.BOOKING,
    )
