package com.example.bot.routes

import com.example.bot.music.MusicService
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private const val DEFAULT_LIMIT = 100

fun Application.musicRoutes(service: MusicService) {
    val logger = LoggerFactory.getLogger("MusicRoutes")

    routing {
        route("/api/music") {
            install(InitDataAuthPlugin) {
                botTokenProvider = {
                    System.getenv("TELEGRAM_BOT_TOKEN")
                        ?: error("TELEGRAM_BOT_TOKEN missing")
                }
            }

            get("/items") {
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                val (etag, items) = service.listItems(limit = DEFAULT_LIMIT)
                if (ifNoneMatch == etag) {
                    logger.debug("music.items.not_modified etag={}", etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, items)
            }

            get("/playlists") {
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                val (etag, playlists) = service.listPlaylists(limit = DEFAULT_LIMIT)
                if (ifNoneMatch == etag) {
                    logger.debug("music.playlists.not_modified etag={}", etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, playlists)
            }

            get("/playlists/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    logger.warn("music.playlists.invalid_id value={}", call.parameters["id"])
                    call.respond(HttpStatusCode.BadRequest, "Invalid id")
                    return@get
                }
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                val result = service.getPlaylist(id)
                if (result == null) {
                    logger.debug("music.playlists.not_found id={}", id)
                    call.respond(HttpStatusCode.NotFound, "Playlist not found")
                    return@get
                }
                val (etag, payload) = result
                if (ifNoneMatch == etag) {
                    logger.debug("music.playlists.not_modified id={} etag={}", id, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, payload)
            }
        }
    }
}
