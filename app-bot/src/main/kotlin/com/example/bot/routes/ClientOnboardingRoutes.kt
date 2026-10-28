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
    post("/invitations/resolve") { api.read(call, OnboardingRead.INVITATION_RESOLVE, body = true) }
    get("/invitations/{id}") { api.read(call, OnboardingRead.INVITATION_STATUS, "id") }
    post("/invitations/{id}/claim") { api.mutate(call, OnboardingMutation.INVITATION_CLAIM, "id") }
    get("/clients/{id}/onboarding") { api.read(call, OnboardingRead.CLIENT_ONBOARDING, "id") }
}

private fun Route.clientApplicationEndpoints(api: OnboardingHttp) {
    post("/clients/{id}/applications") { api.mutate(call, OnboardingMutation.APPLICATION_CREATE, "id") }
    post("/clients/{id}/applications/search") {
        api.read(call, OnboardingRead.CLIENT_APPLICATION_SEARCH, "id", body = true)
    }
    get("/applications/{id}") { api.read(call, OnboardingRead.CLIENT_APPLICATION_GET, "id") }
    patch("/applications/{id}") { api.mutate(call, OnboardingMutation.APPLICATION_UPDATE, "id") }
    post("/applications/{id}/submit") { api.mutate(call, OnboardingMutation.APPLICATION_SUBMIT, "id") }
    post("/applications/{id}/cancel") { api.mutate(call, OnboardingMutation.APPLICATION_CANCEL, "id") }
    post("/applications/{id}/club") { api.mutate(call, OnboardingMutation.APPLICATION_CREATE_CLUB, "id") }
    get("/clubs/{id}/profile") { api.read(call, OnboardingRead.CLUB_PROFILE_GET, "id") }
    patch("/clubs/{id}/profile") { api.mutate(call, OnboardingMutation.CLUB_PROFILE_UPDATE, "id") }
}

private fun Route.platformInvitationEndpoints(api: OnboardingHttp) {
    post("/invitations") { api.mutate(call, OnboardingMutation.INVITATION_ISSUE) }
    get("/invitations/{id}") { api.read(call, OnboardingRead.PLATFORM_INVITATION_GET, "id") }
    post("/invitations/{id}/confirm") { api.mutate(call, OnboardingMutation.INVITATION_CONFIRM, "id") }
    post("/invitations/{id}/revoke") { api.mutate(call, OnboardingMutation.INVITATION_REVOKE, "id") }
    post("/invitations/{id}/reissue") { api.mutate(call, OnboardingMutation.INVITATION_REISSUE, "id") }
    post("/clients/search") { api.read(call, OnboardingRead.PLATFORM_CLIENT_SEARCH, body = true) }
    get("/clients/{id}") { api.read(call, OnboardingRead.PLATFORM_CLIENT_GET, "id") }
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
    ) = call.safeOnboarding {
        repository.read(
            call.onboardingActor(),
            operation,
            idParameter?.let { call.positiveId(it) },
            if (body) call.onboardingBody() else JsonObject(emptyMap()),
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
        if (issuesLink && username == null) error("Onboarding launch configuration unavailable")
        var body = call.onboardingBody()
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

private suspend fun ApplicationCall.onboardingBody(): JsonObject {
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) invalidOnboardingInput()
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
                in conflictCodes -> HttpStatusCode.Conflict
                else -> HttpStatusCode.InternalServerError
            }
        val code = if (status == HttpStatusCode.InternalServerError) ErrorCodes.internal_error else failure.code
        respondError(status, code)
    } catch (_: Exception) {
        // No exception text/body/credential is logged or returned.
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
    }
}

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
    )
