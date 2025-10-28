package com.example.bot.routes

import com.example.bot.cache.HallRenderCache
import com.example.bot.cache.HallRenderCache.Result
import com.example.bot.config.BotLimits
import com.example.bot.render.HallRenderer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

private const val MIN_SCALE = 0.1
private const val DEFAULT_SCALE = 2.0

/**
 * Роут рендера схемы зала с кэшем и ETag.
 * stateKeyProvider должен вернуть «хэш состояния» (например, хэш статусов столов на выбранную ночь).
 */
fun Route.hallImageRoute(
    renderer: HallRenderer,
    stateKeyProvider: suspend (clubId: Long, startUtc: String) -> String,
) {
    val configuredTtl: Duration =
        System.getenv("HALL_CACHE_TTL_SECONDS")?.toLongOrNull()?.let(Duration::ofSeconds)
            ?: BotLimits.Cache.DEFAULT_TTL
    val cache =
        HallRenderCache(
            maxEntries =
                System.getenv("HALL_CACHE_MAX_ENTRIES")?.toIntOrNull()
                    ?: BotLimits.Cache.DEFAULT_MAX_ENTRIES,
            ttl = configuredTtl,
        )
    val baseVersion = System.getenv("HALL_BASE_IMAGE_VERSION") ?: "1"

    get("/api/clubs/{clubId}/nights/{startUtc}/hall.png") {
        val clubId = call.parameters.getOrFail<Long>("clubId")
        val startUtc = call.parameters.getOrFail("startUtc")
        val scale =
            call.request.queryParameters["scale"]?.toDoubleOrNull()?.takeIf { it > MIN_SCALE } ?: DEFAULT_SCALE
        val stateKey = withContext(Dispatchers.IO) { stateKeyProvider(clubId, startUtc) }
        val cacheKey = "$clubId|$startUtc|$scale|$baseVersion|$stateKey"

        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]

        when (
            val res =
                cache.getOrRender(cacheKey, ifNoneMatch) {
                    renderer.render(clubId, startUtc, scale, stateKey)
                }
        ) {
            is Result.NotModified -> {
                call.response.headers.append(HttpHeaders.ETag, res.etag, safeOnly = false)
                call.respondText("", status = HttpStatusCode.NotModified)
            }
            is Result.Ok -> {
                call.response.headers.append(HttpHeaders.ETag, res.etag, safeOnly = false)
                call.response.headers.append(
                    HttpHeaders.CacheControl,
                    "public, max-age=${configuredTtl.seconds}",
                )
                call.respondBytes(
                    bytes = res.bytes,
                    contentType = ContentType.Image.PNG,
                    status = HttpStatusCode.OK,
                )
            }
        }
    }
}
