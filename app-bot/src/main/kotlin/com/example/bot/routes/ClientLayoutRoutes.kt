package com.example.bot.routes

import com.example.bot.data.client.onboarding.ClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.data.client.onboarding.OnboardingRead
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.verifiedClientIdentity
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
import java.util.Base64

/** Client-only layout endpoints share the canonical signed identity guard, never legacy RBAC. */
internal fun Application.registerClientLayoutRoutes(repository: ClientOnboardingRepository) {
    val http = ClientLayoutHttp(repository)
    routing {
        route("/api/client/clubs/{clubId}") {
            get("/layout") { http.read(call) }
            post("/halls") { http.mutate(call, OnboardingMutation.CLUB_HALL_CREATE) }
            patch("/halls/{hallId}") { http.mutate(call, OnboardingMutation.CLUB_HALL_UPDATE, "hallId") }
            post("/halls/{hallId}/tables") { http.mutate(call, OnboardingMutation.CLUB_TABLE_CREATE, "hallId") }
            patch("/halls/{hallId}/tables/{tableId}") {
                http.mutate(call, OnboardingMutation.CLUB_TABLE_UPDATE, "hallId", "tableId")
            }
            get("/halls/{hallId}/plan") { http.plan(call) }
            post("/halls/{hallId}/plan") { http.upload(call) }
        }
    }
}

private class ClientLayoutHttp(
    private val repository: ClientOnboardingRepository,
) {
    suspend fun read(call: ApplicationCall) =
        call.layoutResponse {
            call.respond(repository.read(call.layoutActor(), OnboardingRead.CLUB_LAYOUT_GET, call.layoutId("clubId")))
        }

    suspend fun mutate(
        call: ApplicationCall,
        operation: OnboardingMutation,
        vararg resourceIds: String,
    ) = call.layoutResponse {
        val clubId = call.layoutId("clubId")
        val key = call.layoutKey()
        val body = call.layoutBody()
        val ids = resourceIds.associateWith { JsonPrimitive(call.layoutId(it).toString()) }
        // Resource identity belongs to the route. A JSON body cannot redirect a parent lookup.
        if (body.keys.any { it in setOf("clubId", "clientId", "hallId", "tableId") }) invalidLayout()
        call.respond(repository.mutate(call.layoutActor(), operation, clubId, JsonObject(body + ids), key))
    }

    suspend fun plan(call: ApplicationCall) =
        call.layoutResponse {
            val plan =
                repository.read(
                    call.layoutActor(),
                    OnboardingRead.CLUB_PLAN_GET,
                    call.layoutId("clubId"),
                    JsonObject(mapOf("hallId" to JsonPrimitive(call.layoutId("hallId").toString()))),
                )
            // Authorization and resource-parent matching have completed inside the fresh repository read.
            // Client images deliberately have no 304 path: no cached bytes outlive a grant check.
            val type = plan.getValue("contentType").jsonPrimitive.content
            check(type in PLAN_TYPES)
            val bytes = Base64.getDecoder().decode(plan.getValue("bytesBase64").jsonPrimitive.content)
            check(bytes.isNotEmpty() && bytes.size <= MAX_CLIENT_PLAN_BYTES)
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondBytes(bytes, ContentType.parse(type), HttpStatusCode.OK)
        }

    suspend fun upload(call: ApplicationCall) =
        call.layoutResponse {
            val clubId = call.layoutId("clubId")
            val hallId = call.layoutId("hallId")
            val key = call.layoutKey()
            val version =
                call.request.headers
                    .getAll("X-Layout-Version")
                    ?.singleOrNull()
                    ?.takeIf { POSITIVE_LAYOUT_ID.matches(it) && it.toLongOrNull() != null }
                    ?: invalidLayout()
            val contentType =
                call.request.headers
                    .getAll(HttpHeaders.ContentType)
                    ?.singleOrNull()
            if (contentType !in PLAN_TYPES) throw OnboardingFailure("unsupported_media_type")
            val bytes = call.receiveChannel().readRemaining(MAX_CLIENT_PLAN_BYTES + 1).readByteArray()
            if (bytes.size > MAX_CLIENT_PLAN_BYTES) throw OnboardingFailure("payload_too_large")
            if (bytes.isEmpty()) invalidLayout()
            val body =
                JsonObject(
                    mapOf(
                        "hallId" to JsonPrimitive(hallId.toString()),
                        "expectedVersion" to JsonPrimitive(version),
                        "contentType" to JsonPrimitive(contentType),
                        "bytesBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
                    ),
                )
            call.respond(repository.mutate(call.layoutActor(), OnboardingMutation.CLUB_PLAN_UPLOAD, clubId, body, key))
        }
}

private fun ApplicationCall.layoutActor(): OnboardingActor {
    val identity = verifiedClientIdentity()
    return OnboardingActor(identity.telegramUserId, identity.connectionKey, username = identity.username)
}

private fun ApplicationCall.layoutId(name: String): Long =
    parameters[name]?.takeIf { POSITIVE_LAYOUT_ID.matches(it) }?.toLongOrNull() ?: invalidLayout()

private fun ApplicationCall.layoutKey(): String =
    request.headers
        .getAll("Idempotency-Key")
        ?.singleOrNull()
        ?.takeIf { LAYOUT_KEY.matches(it) } ?: invalidLayout()

private suspend fun ApplicationCall.layoutBody(): JsonObject {
    val bytes = receiveChannel().readRemaining(MAX_LAYOUT_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_LAYOUT_BODY_BYTES) invalidLayout()
    return try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalidLayout()
    } catch (_: SerializationException) {
        invalidLayout()
    } catch (_: CharacterCodingException) {
        invalidLayout()
    }
}

private suspend fun ApplicationCall.layoutResponse(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OnboardingFailure) {
        val status =
            when (failure.code) {
                "not_found" -> HttpStatusCode.NotFound
                "forbidden" -> HttpStatusCode.Forbidden
                "validation_error" -> HttpStatusCode.BadRequest
                "payload_too_large" -> HttpStatusCode.PayloadTooLarge
                "unsupported_media_type" -> HttpStatusCode.UnsupportedMediaType
                "version_conflict", "idempotency_conflict", "hall_name_conflict", "table_number_conflict" ->
                    HttpStatusCode.Conflict
                else -> HttpStatusCode.InternalServerError
            }
        val code = if (status == HttpStatusCode.InternalServerError) ErrorCodes.internal_error else failure.code
        respondError(status, code)
    } catch (_: Exception) {
        // Repository, decoder and transport errors never disclose credentials, bodies, keys or SQL.
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
    }
}

private fun invalidLayout(): Nothing = throw OnboardingFailure("validation_error")

private const val MAX_CLIENT_PLAN_BYTES = 5L * 1024 * 1024
private const val MAX_LAYOUT_BODY_BYTES = 16_384L
private val POSITIVE_LAYOUT_ID = Regex("[1-9][0-9]*")
private val LAYOUT_KEY = Regex("[A-Za-z0-9._:-]{8,128}")
private val PLAN_TYPES = setOf("image/png", "image/jpeg")
