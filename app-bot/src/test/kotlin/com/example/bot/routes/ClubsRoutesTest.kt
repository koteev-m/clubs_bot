package com.example.bot.routes

import com.example.bot.clubs.Club
import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.testing.applicationDev
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClubsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    private val clubs =
        listOf(
            Club(
                id = 1,
                city = "Moscow",
                name = "A Club",
                genres = listOf("house"),
                tags = listOf("vip", "roof"),
                logoUrl = null,
            ),
            Club(
                id = 2,
                city = "Moscow",
                name = "B Club",
                genres = listOf("techno"),
                tags = listOf("underground"),
                logoUrl = null,
            ),
            Club(
                id = 3,
                city = "Kazan",
                name = "C Club",
                genres = listOf("pop"),
                tags = listOf("vip"),
                logoUrl = null,
            ),
        )

    private val events =
        listOf(
            Event(
                id = 12,
                clubId = 3,
                startUtc = Instant.parse("2024-01-15T20:00:00Z"),
                endUtc = Instant.parse("2024-01-15T23:00:00Z"),
                title = "Party C",
                isSpecial = false,
            ),
            Event(
                id = 10,
                clubId = 1,
                startUtc = Instant.parse("2024-01-10T20:00:00Z"),
                endUtc = Instant.parse("2024-01-10T23:00:00Z"),
                title = "Party A",
                isSpecial = false,
            ),
            Event(
                id = 11,
                clubId = 2,
                startUtc = Instant.parse("2024-01-12T20:00:00Z"),
                endUtc = Instant.parse("2024-01-12T23:00:00Z"),
                title = "Party B",
                isSpecial = true,
            ),
        )

    @Test
    fun `filters clubs by city, tag and pagination`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

                val response =
                    client.get("/api/clubs?city=Moscow&tag=vip&size=1&page=0") {
                        withInitData(createInitData())
                    }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, payload.size)
            assertEquals("A Club", payload.first().jsonObject["name"]?.jsonPrimitive?.content)

            val secondPage =
                client.get("/api/clubs?city=Moscow&size=1&page=1") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, secondPage.status)
            val secondPayload = json.parseToJsonElement(secondPage.bodyAsText()).jsonArray
            assertEquals(1, secondPayload.size)
        }

    @Test
    fun `clubs without filters returns empty array with cache headers`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val response =
                client.get("/api/clubs") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(0, payload.size)
            assertNotNull(response.headers[HttpHeaders.ETag])
            assertEquals("max-age=60, must-revalidate", response.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", response.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
        }

    @Test
    fun `events endpoint supports filters and etag`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val from = "2024-01-01T00:00:00Z"
            val to = "2024-01-31T00:00:00Z"
            val firstResponse =
                client.get("/api/events?city=Moscow&from=$from&to=$to") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val etag = firstResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val eventsPayload = json.parseToJsonElement(firstResponse.bodyAsText()).jsonArray
            assertEquals(2, eventsPayload.size)

            val cachedResponse =
                client.get("/api/events?city=Moscow&from=$from&to=$to") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, etag)
                }

            assertEquals(HttpStatusCode.NotModified, cachedResponse.status)
        }

    @Test
    fun `etag matches tolerate quotes`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val firstResponse =
                client.get("/api/events?city=Moscow") {
                    withInitData(createInitData())
                }

            val etag = firstResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val cached =
                client.get("/api/events?city=Moscow") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "\"$etag\"")
                }

            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals(etag, cached.headers[HttpHeaders.ETag])
            assertEquals("max-age=60, must-revalidate", cached.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", cached.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", cached.headers[HttpHeaders.Vary])
        }

    @Test
    fun `etag matches tolerate weak validators`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val firstResponse =
                client.get("/api/clubs?city=Moscow&q=Club") {
                    withInitData(createInitData())
                }

            val etag = firstResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val cached =
                client.get("/api/clubs?city=Moscow&q=Club") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, "W/\"$etag\"")
                }

            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals(etag, cached.headers[HttpHeaders.ETag])
            assertEquals("max-age=60, must-revalidate", cached.headers[HttpHeaders.CacheControl])
            assertEquals("application/json; charset=UTF-8", cached.headers[HttpHeaders.ContentType])
            assertEquals("X-Telegram-Init-Data", cached.headers[HttpHeaders.Vary])
        }

    @Test
    fun `empty filters return empty events list`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val response =
                client.get("/api/events") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(0, payload.size)
        }

    @Test
    fun `date parsing accepts local date and sorts events`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val response =
                client.get("/api/events?city=Moscow&from=2024-01-01") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, payload.size)
            val ids = payload.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(listOf(10L, 11L), ids)
        }

    @Test
    fun `from greater than to is swapped`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val response =
                client.get("/api/events?city=Moscow&from=2024-01-20T00:00:00Z&to=2024-01-01T00:00:00Z") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            val ids = payload.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(listOf(10L, 11L), ids)
        }

    @Test
    fun `clubs ordering is deterministic across pages`() =
        testApplication {
            val unsorted = listOf(clubs[1], clubs[0], clubs[2])

            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(unsorted, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, unsorted.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val firstPage =
                client.get("/api/clubs?city=Moscow&size=1&page=0") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, firstPage.status)
            val firstName = json.parseToJsonElement(firstPage.bodyAsText()).jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content
            assertEquals("A Club", firstName)

            val secondPage =
                client.get("/api/clubs?city=Moscow&size=1&page=1") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, secondPage.status)
            val secondName = json.parseToJsonElement(secondPage.bodyAsText()).jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content
            assertEquals("B Club", secondName)
        }

    @Test
    fun `vary header is returned for cached and fresh responses`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                clubsRoutes(
                    clubsRepository = InMemoryClubsRepository(clubs, updatedAt = clock.instant()),
                    eventsRepository = InMemoryEventsRepository(events, clubs.associateBy { it.id }, updatedAt = clock.instant()),
                )
            }

            val firstResponse =
                client.get("/api/clubs?city=Moscow&q=Club") {
                    withInitData(createInitData())
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val etag = firstResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            assertEquals("X-Telegram-Init-Data", firstResponse.headers[HttpHeaders.Vary])

            val cached =
                client.get("/api/clubs?city=Moscow&q=Club") {
                    withInitData(createInitData())
                    header(HttpHeaders.IfNoneMatch, etag)
                }

            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals("X-Telegram-Init-Data", cached.headers[HttpHeaders.Vary])
        }
}
