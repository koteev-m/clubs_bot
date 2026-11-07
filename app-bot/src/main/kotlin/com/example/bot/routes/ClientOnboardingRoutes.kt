package com.example.bot.routes

import com.example.bot.data.client.onboarding.ClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.data.client.onboarding.OnboardingRead
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.plugins.verifiedClientIdentity
import com.example.bot.plugins.withPlatformOnboardingAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.CharacterCodingException

/** HTTP only: identity comes from the shared early signature guard; persistence rechecks authority. */
internal fun Application.registerClientOnboardingRoutes(
    repository: ClientOnboardingRepository,
    connection: TrustedBotConnection,
    botUsername: String?,
    configuredOwnerId: Long?,
) {
    val api = OnboardingHttp(repository, botUsername)
    registerClientLayoutRoutes(repository)
    routing {
        route("/api/client") {
            // The shared /api/client route receives its single guard in clientContextRoutes.
            clientInvitationEndpoints(api)
            clientApplicationEndpoints(api)
        }
        route("/api/platform/onboarding") {
            withPlatformOnboardingAuth(connection, configuredOwnerId)
            platformInvitationEndpoints(api)
            platformApplicationEndpoints(api)
        }
    }
}

private fun Route.clientInvitationEndpoints(api: OnboardingHttp) {
    get("/locations") { call.safeOnboarding { OnboardingLocations.countries() } }
    post("/locations/search") { call.safeOnboarding { OnboardingLocations.search(call.onboardingBody()) } }
    post("/invitations/resolve") { api.read(call, OnboardingRead.INVITATION_RESOLVE, body = true) }
    get("/invitations/{id}") { api.read(call, OnboardingRead.INVITATION_STATUS, "id") }
    post("/invitations/{id}/claim") { api.mutate(call, OnboardingMutation.INVITATION_CLAIM, "id") }
    get("/clients/{id}/onboarding") { api.read(call, OnboardingRead.CLIENT_ONBOARDING, "id") }
}

private fun Route.clientApplicationEndpoints(api: OnboardingHttp) {
    post("/clients/{id}/clubs") { api.mutate(call, OnboardingMutation.CLUB_CREATE, "id") }
    post("/clients/{id}/applications") { api.mutate(call, OnboardingMutation.APPLICATION_CREATE, "id") }
    post("/clients/{id}/applications/search") {
        api.read(call, OnboardingRead.CLIENT_APPLICATION_SEARCH, "id", body = true)
    }
    get("/applications/{id}") { api.read(call, OnboardingRead.CLIENT_APPLICATION_GET, "id") }
    patch("/applications/{id}") { api.mutate(call, OnboardingMutation.APPLICATION_UPDATE, "id") }
    post("/applications/{id}/submit") { api.mutate(call, OnboardingMutation.APPLICATION_SUBMIT, "id") }
    post("/applications/{id}/cancel") { api.mutate(call, OnboardingMutation.APPLICATION_CANCEL, "id") }
    post("/applications/{id}/club") { api.mutate(call, OnboardingMutation.APPLICATION_CREATE_CLUB, "id") }
    get("/clubs/{id}/modules-settings") { api.read(call, OnboardingRead.CLUB_MODULE_SETTINGS_GET, "id") }
    patch("/clubs/{id}/modules-settings") { api.mutate(call, OnboardingMutation.CLUB_MODULE_SETTINGS_UPDATE, "id") }
    get("/clubs/{id}/finance/template") { api.read(call, OnboardingRead.CLUB_FINANCE_GET, "id") }
    post("/clubs/{id}/finance/template") { api.mutate(call, OnboardingMutation.CLUB_FINANCE_UPDATE, "id") }
    get("/clubs/{id}/booking-rules") { api.read(call, OnboardingRead.CLUB_BOOKING_RULES_GET, "id") }
    patch("/clubs/{id}/booking-rules") { api.mutate(call, OnboardingMutation.CLUB_BOOKING_RULES_UPDATE, "id") }
    get("/clubs/{id}/calendar") { api.read(call, OnboardingRead.CLUB_CALENDAR_GET, "id") }
    patch("/clubs/{id}/calendar") { api.mutate(call, OnboardingMutation.CLUB_CALENDAR_UPDATE, "id") }
    get("/calendar/nights/{id}/legacy-event") { api.read(call, OnboardingRead.CLUB_NIGHT_EVENT_CANDIDATE, "id") }
    post("/calendar/nights/{id}/legacy-event") { api.mutate(call, OnboardingMutation.CLUB_NIGHT_EVENT_LINK, "id") }
    get("/clubs/{id}/booking-preview") { api.read(call, OnboardingRead.CLUB_BOOKING_PREVIEW_GET, "id") }
    get("/clubs/{id}/booking-preview/nights/{nightId}/halls/{hallId}/availability") {
        api.read(call, OnboardingRead.CLUB_TABLE_AVAILABILITY_GET, "id", pathIds = listOf("nightId", "hallId"))
    }
    get("/clubs/{id}/profile") { api.read(call, OnboardingRead.CLUB_PROFILE_GET, "id") }
    patch("/clubs/{id}/profile") { api.mutate(call, OnboardingMutation.CLUB_PROFILE_UPDATE, "id") }
}

private fun Route.platformInvitationEndpoints(api: OnboardingHttp) {
    post("/invitations") { api.mutate(call, OnboardingMutation.INVITATION_ISSUE) }
    get("/invitations/{id}") { api.read(call, OnboardingRead.PLATFORM_INVITATION_GET, "id") }
    post("/invitations/{id}/confirm") { api.mutate(call, OnboardingMutation.INVITATION_CONFIRM, "id") }
    post("/invitations/{id}/revoke") { api.mutate(call, OnboardingMutation.INVITATION_REVOKE, "id") }
    post("/invitations/{id}/reissue") { api.mutate(call, OnboardingMutation.INVITATION_REISSUE, "id") }
    get("/summary") { api.read(call, OnboardingRead.PLATFORM_SUMMARY) }
    post("/clubs/search") { api.read(call, OnboardingRead.PLATFORM_CLUB_SEARCH, body = true) }
    post("/clients/{id}/archive") { api.mutate(call, OnboardingMutation.CLIENT_ARCHIVE, "id") }
    post("/clients/{id}/restore") { api.mutate(call, OnboardingMutation.CLIENT_RESTORE, "id") }
    post("/clients/search") { api.read(call, OnboardingRead.PLATFORM_CLIENT_SEARCH, body = true) }
    get("/clients/{id}") { api.read(call, OnboardingRead.PLATFORM_CLIENT_GET, "id") }
    post("/clients/{id}/limit") { api.mutate(call, OnboardingMutation.CLIENT_LIMIT_UPDATE, "id") }
    post("/clients/{id}/members/{userId}/revoke") { api.mutate(call, OnboardingMutation.MEMBER_REVOKE, "id") }
    post("/clients/{id}/members/{userId}/restore") { api.mutate(call, OnboardingMutation.MEMBER_RESTORE, "id") }
}

private fun Route.platformApplicationEndpoints(api: OnboardingHttp) {
    post("/applications/search") { api.read(call, OnboardingRead.PLATFORM_APPLICATION_SEARCH, body = true) }
    get("/applications/{id}") { api.read(call, OnboardingRead.PLATFORM_APPLICATION_GET, "id") }
    post("/applications/{id}/review") { api.mutate(call, OnboardingMutation.APPLICATION_REVIEW, "id") }
    post("/applications/{id}/approve") { api.mutate(call, OnboardingMutation.APPLICATION_APPROVE, "id") }
    post("/applications/{id}/reject") { api.mutate(call, OnboardingMutation.APPLICATION_REJECT, "id") }
    post("/applications/{id}/cancel") { api.mutate(call, OnboardingMutation.PLATFORM_APPLICATION_CANCEL, "id") }
}

private class OnboardingHttp(
    private val repository: ClientOnboardingRepository,
    botUsername: String?,
) {
    private val username = botUsername?.removePrefix("@")?.takeIf { BOT_USERNAME.matches(it) }

    suspend fun read(
        call: ApplicationCall,
        operation: OnboardingRead,
        idParameter: String? = null,
        body: Boolean = false,
        pathIds: List<String> = emptyList(),
    ) = call.safeOnboarding {
        repository.read(
            call.onboardingActor(),
            operation,
            idParameter?.let { call.positiveId(it) },
            if (body) {
                call.onboardingBody()
            } else {
                JsonObject(pathIds.associateWith { JsonPrimitive(call.positiveId(it).toString()) })
            },
        )
    }

    suspend fun mutate(
        call: ApplicationCall,
        operation: OnboardingMutation,
        idParameter: String? = null,
    ) = call.safeOnboarding {
        val key =
            call.request.headers
                .getAll("Idempotency-Key")
                ?.singleOrNull()
        if (key == null || !COMMAND_KEY.matches(key)) throw OnboardingFailure("validation_error")
        val issuesLink = operation in invitationIssuers
        // This refusal happens before persistence, so callers can distinguish it from a lost response.
        if (issuesLink && username == null) throw OnboardingFailure(ErrorCodes.INVITATION_LINK_UNAVAILABLE)
        var body =
            call.onboardingBody(
                if (operation ==
                    OnboardingMutation.CLUB_CALENDAR_UPDATE
                ) {
                    CALENDAR_BODY_BYTES
                } else {
                    MAX_BODY_BYTES
                },
            )
        if (operation == OnboardingMutation.CLUB_CREATE) body = OnboardingLocations.creationBody(body)
        if (operation in memberMutations) {
            val userId = call.positiveId("userId").toString()
            if (body["userId"] != null && body["userId"] != JsonPrimitive(userId)) {
                throw OnboardingFailure("validation_error")
            }
            body = JsonObject(body + ("userId" to JsonPrimitive(userId)))
        }
        val result =
            repository.mutate(
                call.onboardingActor(),
                operation,
                idParameter?.let { call.positiveId(it) },
                body,
                key,
            )
        val token = result["token"]?.jsonPrimitive?.content
        if (issuesLink && token != null) {
            JsonObject(result + ("invitationLink" to JsonPrimitive("https://t.me/$username?startapp=ci_$token")))
        } else {
            result
        }
    }
}

private fun ApplicationCall.onboardingActor(): OnboardingActor {
    val identity = verifiedClientIdentity()
    return OnboardingActor(identity.telegramUserId, identity.connectionKey, username = identity.username)
}

private fun ApplicationCall.positiveId(parameter: String): Long =
    parameters[parameter]?.takeIf { POSITIVE_ID.matches(it) }?.toLongOrNull()
        ?: throw OnboardingFailure("validation_error")

private suspend fun ApplicationCall.onboardingBody(limit: Long = MAX_BODY_BYTES): JsonObject {
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    if (bytes.size > limit) invalidOnboardingInput()
    return try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: invalidOnboardingInput()
    } catch (_: SerializationException) {
        invalidOnboardingInput()
    } catch (_: CharacterCodingException) {
        invalidOnboardingInput()
    }
}

private fun invalidOnboardingInput(): Nothing = throw OnboardingFailure("validation_error")

private suspend fun ApplicationCall.safeOnboarding(block: suspend () -> JsonObject) {
    try {
        respond(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OnboardingFailure) {
        val status =
            when (failure.code) {
                "not_found" -> HttpStatusCode.NotFound
                "forbidden" -> HttpStatusCode.Forbidden
                "validation_error" -> HttpStatusCode.BadRequest
                "location_required" -> HttpStatusCode.BadRequest
                ErrorCodes.INVITATION_LINK_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                in conflictCodes -> HttpStatusCode.Conflict
                else -> HttpStatusCode.InternalServerError
            }
        val code = if (status == HttpStatusCode.InternalServerError) ErrorCodes.internal_error else failure.code
        respondError(status, code, details = failure.details)
    } catch (_: Exception) {
        // No exception text/body/credential is logged or returned.
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
    }
}

private const val CALENDAR_BODY_BYTES = 65_536L
private const val MAX_BODY_BYTES = 16_384L
private val POSITIVE_ID = Regex("[1-9][0-9]*")
private val BOT_USERNAME = Regex("[A-Za-z][A-Za-z0-9_]{4,31}")
private val COMMAND_KEY = Regex("[A-Za-z0-9._:-]{8,128}")
private val invitationIssuers = setOf(OnboardingMutation.INVITATION_ISSUE, OnboardingMutation.INVITATION_REISSUE)
private val memberMutations = setOf(OnboardingMutation.MEMBER_REVOKE, OnboardingMutation.MEMBER_RESTORE)
private val conflictCodes =
    setOf(
        ErrorCodes.VERSION_CONFLICT,
        ErrorCodes.INVALID_TRANSITION,
        ErrorCodes.idempotency_conflict,
        ErrorCodes.DUPLICATE_APPLICATION,
        "client_not_empty",
        "finance_group_in_use",
        "calendar_night_dependencies",
        "calendar_night_started",
        "calendar_night_invalid",
        "calendar_night_overlap",
        "calendar_night_cutover_required",
        "calendar_night_event_conflict",
        "club_limit_unset",
        "club_limit_reached",
        "club_limit_below_usage",
    )
