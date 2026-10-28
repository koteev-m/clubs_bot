package com.example.bot.routes

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.ClientClub
import com.example.bot.data.client.ClientContextRepository
import com.example.bot.data.security.PermissionCodes
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.plugins.installClientContextAuth
import com.example.bot.plugins.verifiedClientIdentity
import com.example.bot.plugins.withClientContextAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** The sole production adapter: the caller supplies the existing platform AppConfig credential. */
fun Application.clientContextRoutes(
    repository: ClientContextRepository,
    botTokenProvider: () -> String,
) {
    clientContextRoutes(repository, TrustedBotConnection("platform", ClientAudience.Platform, botTokenProvider))
}

/** A restricted audience can be injected by synthetic tests; no production connection resolver exists. */
internal fun Application.clientContextRoutes(
    repository: ClientContextRepository,
    connection: TrustedBotConnection,
) {
    installClientContextAuth(connection)
    routing {
        route("/api/client") {
            withClientContextAuth(connection)
            get("/context") {
                call.respondClientRead {
                    val principal = call.verifiedClientIdentity()
                    val clients = repository.context(principal.telegramUserId, principal.audience)
                    call.respond(
                        ClientContextResponse(
                            clients.map { client ->
                                ClientResponse(client.id.toString(), client.name, client.clubs.map { it.toResponse() })
                            },
                        ),
                    )
                }
            }
            get("/clubs/{clubId}") {
                val rawId = call.parameters["clubId"].orEmpty()
                val clubId = rawId.takeIf { CLUB_ID.matches(it) }?.toLongOrNull()
                if (clubId == null) {
                    return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }
                call.respondClientRead {
                    val principal = call.verifiedClientIdentity()
                    val club = repository.club(principal.telegramUserId, principal.audience, clubId)
                    if (club == null) {
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    } else {
                        call.respond(
                            ClientClubDetailResponse(
                                club.clientId.toString(),
                                club.id.toString(),
                                club.name,
                                readPermissions,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondClientRead(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never expose JDBC/provider errors or use a previous result on failure.
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
    }
}

private val CLUB_ID = Regex("[1-9][0-9]*")
private val readPermissions = listOf(PermissionCodes.CLIENT_CLUB_READ.value)

@Serializable
private data class ClientContextResponse(
    val clients: List<ClientResponse>,
)

@Serializable
private data class ClientResponse(
    val id: String,
    val name: String,
    val clubs: List<ClientClubResponse>,
)

@Serializable
private data class ClientClubResponse(
    val id: String,
    val name: String,
    val permissions: List<String>,
)

@Serializable
private data class ClientClubDetailResponse(
    val clientId: String,
    val id: String,
    val name: String,
    val permissions: List<String>,
)

private fun ClientClub.toResponse() = ClientClubResponse(id.toString(), name, readPermissions)
