package com.example.bot.routes

import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.testing.applicationDev
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LayoutRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)

    private fun Application.installLayoutRoutes(repo: LayoutRepository) {
        install(ContentNegotiation) { json() }
        install(AutoHeadResponse)
        layoutRoutes(repo)
    }

    private fun repository(
        eventWatermarks: Map<Long?, Instant> = emptyMap(),
        updatedAt: Instant = clock.instant(),
    ): LayoutRepository {
        val zones =
            listOf(
                Zone(id = "dancefloor", name = "Dancefloor", tags = listOf("near_stage"), order = 2),
                Zone(id = "vip", name = "VIP", tags = listOf("vip"), order = 1),
                Zone(id = "balcony", name = "Balcony", tags = emptyList(), order = 3),
            )

        val tables =
            listOf(
                Table(id = 5, zoneId = "dancefloor", label = "D-3", capacity = 8, minimumTier = "premium", status = TableStatus.FREE),
                Table(id = 2, zoneId = "vip", label = "VIP-2", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
                Table(id = 7, zoneId = "balcony", label = "B-2", capacity = 5, minimumTier = "standard", status = TableStatus.FREE),
                Table(id = 3, zoneId = "dancefloor", label = "D-1", capacity = 6, minimumTier = "premium", status = TableStatus.FREE),
                Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
                Table(id = 6, zoneId = "balcony", label = "B-1", capacity = 3, minimumTier = "standard", status = TableStatus.FREE),
                Table(id = 4, zoneId = "dancefloor", label = "D-2", capacity = 6, minimumTier = "premium", status = TableStatus.FREE),
            )

        val statusOverrides: Map<Long?, Map<Long, TableStatus>> =
            mapOf(
                100L to mapOf(3L to TableStatus.HOLD, 5L to TableStatus.BOOKED),
                200L to mapOf(2L to TableStatus.HOLD, 4L to TableStatus.BOOKED),
            )

        return InMemoryLayoutRepository(
            layouts =
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = 1,
                        zones = zones,
                        tables = tables,
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        statusOverrides = statusOverrides,
                    ),
                ),
            baseUpdatedAt = updatedAt,
            eventUpdatedAt = eventWatermarks,
            clock = clock,
        )
    }

    @Test
    fun `returns layout with sorted payload`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val response =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("max-age=60, must-revalidate", response.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", response.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val zones = payload["zones"]!!.jsonArray
            val tables = payload["tables"]!!.jsonArray
            val assets = payload["assets"]!!.jsonObject

            assertEquals(listOf("vip", "dancefloor", "balcony"), zones.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val tableIds = tables.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            assertEquals(listOf(6L, 7L, 3L, 4L, 5L, 1L, 2L), tableIds)

            val statuses =
                tables.associate { table ->
                    table.jsonObject["id"]!!.jsonPrimitive.long to table.jsonObject["status"]!!.jsonPrimitive.content
                }
            assertEquals("hold", statuses[3L])
            assertEquals("booked", statuses[5L])

            assertEquals("/assets/layouts/1/${assets["fingerprint"]!!.jsonPrimitive.content}.json", assets["geometryUrl"]!!.jsonPrimitive.content)
        }

    @Test
    fun `returns 304 when etag matches`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val initial =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            val etag = initial.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val notModified =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, etag)
                }

            assertEquals(HttpStatusCode.NotModified, notModified.status)
            assertEquals("max-age=60, must-revalidate", notModified.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", notModified.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", notModified.headers[HttpHeaders.Vary])
        }

    @Test
    fun `layout HEAD exposes cache headers`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val head =
                client.head("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, head.status)
            assertEquals("max-age=60, must-revalidate", head.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", head.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", head.headers[HttpHeaders.Vary])
            assertNotNull(head.headers[HttpHeaders.ETag])
            assertEquals("", head.bodyAsText())
        }

    @Test
    fun `layout api etag tolerates quotes and weak validators`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val initial =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            val etag = initial.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val quoted =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "\"$etag\"")
                }
            assertEquals(HttpStatusCode.NotModified, quoted.status)

            val weak =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "W/\"$etag\"")
                }
            assertEquals(HttpStatusCode.NotModified, weak.status)
        }

    @Test
    fun `layout api etag tolerates multi-value If-None-Match`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val initial =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            val etag = initial.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val multi =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "garbage, W/\"$etag\" , \"another\"")
                }

            assertEquals(HttpStatusCode.NotModified, multi.status)
        }

    @Test
    fun `layout api respects If-None-Match star`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val initial =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, initial.status)

            val star =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "*")
                }

            assertEquals(HttpStatusCode.NotModified, star.status)
        }

    @Test
    fun `table statuses depend on event`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val first =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }
            val second =
                client.get("/api/clubs/1/layout?eventId=200") {
                    withInitData(createInitData())
                }

            val firstJson = json.parseToJsonElement(first.bodyAsText()).jsonObject
            val secondJson = json.parseToJsonElement(second.bodyAsText()).jsonObject

            val firstStatuses =
                firstJson["tables"]!!.jsonArray.associate { table ->
                    table.jsonObject["id"]!!.jsonPrimitive.long to table.jsonObject["status"]!!.jsonPrimitive.content
                }
            val secondStatuses =
                secondJson["tables"]!!.jsonArray.associate { table ->
                    table.jsonObject["id"]!!.jsonPrimitive.long to table.jsonObject["status"]!!.jsonPrimitive.content
                }

            assertEquals("hold", firstStatuses[3L])
            assertEquals("booked", secondStatuses[4L])
            assertEquals("free", secondStatuses[5L])

            val etagFirst = first.headers[HttpHeaders.ETag]
            val etagSecond = second.headers[HttpHeaders.ETag]
            assertNotNull(etagFirst)
            assertNotNull(etagSecond)
            assertNotEquals(etagFirst, etagSecond)
        }

    @Test
    fun `etag changes when watermark for same event changes`() {
        val initialWatermarks: Map<Long?, Instant> = mapOf(100L to clock.instant())
        val updatedWatermarks: Map<Long?, Instant> = mapOf(100L to clock.instant().plusSeconds(86_400))

        var etagFirst: String? = null
        testApplication {
            val repo = repository(eventWatermarks = initialWatermarks)
            applicationDev {
                installLayoutRoutes(repo)
            }

            etagFirst =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }.headers[HttpHeaders.ETag]
        }

        var etagSecond: String? = null
        testApplication {
            val repo = repository(eventWatermarks = updatedWatermarks)
            applicationDev {
                installLayoutRoutes(repo)
            }

            etagSecond =
                client.get("/api/clubs/1/layout?eventId=100") {
                    withInitData(createInitData())
                }.headers[HttpHeaders.ETag]
        }

        assertNotNull(etagFirst)
        assertNotNull(etagSecond)
        assertNotEquals(etagFirst, etagSecond)
    }

    @Test
    fun `layout api works without eventId and uses base watermark`() {
        var etagFirst: String? = null
        testApplication {
        val repo = repository(eventWatermarks = emptyMap(), updatedAt = clock.instant())
            applicationDev {
                installLayoutRoutes(repo)
            }

            etagFirst =
                client.get("/api/clubs/1/layout") {
                    withInitData(createInitData())
                }.headers[HttpHeaders.ETag]
        }

        var etagSecond: String? = null
        testApplication {
        val repo = repository(eventWatermarks = emptyMap(), updatedAt = clock.instant().plusSeconds(86_400))
            applicationDev {
                installLayoutRoutes(repo)
            }

            etagSecond =
                client.get("/api/clubs/1/layout") {
                    withInitData(createInitData())
                }.headers[HttpHeaders.ETag]
        }

        assertNotNull(etagFirst)
        assertNotNull(etagSecond)
        assertNotEquals(etagFirst, etagSecond)
    }

    @Test
    fun `serves immutable layout asset`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val layout = repo.getLayout(clubId = 1, eventId = 100)!!
            val assetUrl = layout.assets.geometryUrl

            val assetResponse = client.get(assetUrl)
            assertEquals(HttpStatusCode.OK, assetResponse.status)
            assertEquals("public, max-age=31536000, immutable", assetResponse.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", assetResponse.headers[HttpHeaders.ContentType])
            assertNull(assetResponse.headers[HttpHeaders.Vary])

            val etag = assetResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val cached = client.get(assetUrl) { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals("public, max-age=31536000, immutable", cached.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", cached.headers[HttpHeaders.ContentType])
            assertNull(cached.headers[HttpHeaders.Vary])
        }

    @Test
    fun `asset etag tolerates quotes and weak validators`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val layout = repo.getLayout(clubId = 1, eventId = 100)!!
            val assetUrl = layout.assets.geometryUrl

            val initial = client.get(assetUrl)
            val etag = initial.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val quoted = client.get(assetUrl) { header(HttpHeaders.IfNoneMatch, "\"$etag\"") }
            assertEquals(HttpStatusCode.NotModified, quoted.status)

            val weak = client.get(assetUrl) { header(HttpHeaders.IfNoneMatch, "W/\"$etag\"") }
            assertEquals(HttpStatusCode.NotModified, weak.status)
        }

    @Test
    fun `asset HEAD exposes cache headers`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val layout = repo.getLayout(clubId = 1, eventId = 100)!!
            val assetUrl = layout.assets.geometryUrl

            val headResponse = client.head(assetUrl)
            assertEquals(HttpStatusCode.OK, headResponse.status)
            assertEquals("public, max-age=31536000, immutable", headResponse.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", headResponse.headers[HttpHeaders.ContentType])
            assertNotNull(headResponse.headers[HttpHeaders.ETag])
        }

    @Test
    fun `asset respects If-None-Match star`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val layout = repo.getLayout(clubId = 1, eventId = 100)!!
            val assetUrl = layout.assets.geometryUrl

            val ok = client.get(assetUrl)
            assertEquals(HttpStatusCode.OK, ok.status)

            val star = client.get(assetUrl) { header(HttpHeaders.IfNoneMatch, "*") }
            assertEquals(HttpStatusCode.NotModified, star.status)
        }

    @Test
    fun `asset returns 404 when missing`() =
        testApplication {
            applicationDev {
                installLayoutRoutes(repository())
            }

            val response = client.get("/assets/layouts/1/nonexistent.json")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `asset rejects invalid clubId and fingerprint`() =
        testApplication {
            applicationDev {
                installLayoutRoutes(repository())
            }

            assertEquals(HttpStatusCode.NotFound, client.get("/assets/layouts/../1/abc.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/assets/layouts/1/../../secret.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/assets/layouts/1/not_base64url.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/assets/layouts/1/short.json").status)
        }

    @Test
    fun `returns 404 when layout is missing`() =
        testApplication {
            val repo = repository()
            applicationDev {
                installLayoutRoutes(repo)
            }

            val response =
                client.get("/api/clubs/999/layout") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
