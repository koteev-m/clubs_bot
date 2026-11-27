package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Disposable browser acceptance harness. Test classpath only; no application config or Telegram client. */
object ClientOnboardingBrowserFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getenv("CLB_ONBOARDING_BROWSER_FIXTURE") == "synthetic")
        val source =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:onboarding_browser;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }

        fun migrations() =
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/h2")

        migrations().target("79").load().migrate()
        // Seed genuine historical clients, memberships and clubs before applying the owner-proof migration.
        val legacy = runBlocking { historicalClubs(JdbcClientOnboardingRepository(source, OWNER)) }
        migrations().load().migrate()
        embeddedServer(Netty, host = "127.0.0.1", port = 54863) {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            platformOwnerRoutes(OWNER) { TEST_BOT_TOKEN }
            clientOnboardingRoutes(
                JdbcClientContextRepository(source),
                JdbcClientOnboardingRepository(source, OWNER),
                "synthetic_bot",
                OWNER,
            ) { TEST_BOT_TOKEN }
            webAppRoutes()
            routing {
                get("/fixture/historical-clubs/{key}") {
                    val clubs = legacy[call.parameters["key"]]
                    if (clubs == null) call.respond(HttpStatusCode.NotFound) else call.respond(clubs)
                }
                get("/fixture/identity/{persona}") {
                    call.respond(mapOf("initData" to signed(call.parameters["persona"], null)))
                }
                post("/fixture/identity/{persona}") {
                    val body = call.receive<Map<String, String>>()
                    call.respond(mapOf("initData" to signed(call.parameters["persona"], body["startParam"])))
                }
                post("/fixture/current-floor-night/{club}") {
                    val club = checkNotNull(call.parameters["club"]?.toLongOrNull())
                    val body = call.receive<Map<String, String>>()
                    val table = checkNotNull(body["tableId"]?.toLongOrNull())
                    val night = source.connection.use { connection -> seedBrowserFloorNight(connection, club, table) }
                    call.respond(mapOf("nightId" to night.toString()))
                }
            }
        }.start(wait = true)
    }

    private suspend fun historicalClubs(repository: JdbcClientOnboardingRepository): Map<String, JsonObject> {
        val names =
            mapOf(
                "preparation" to listOf("Север", "Полярная ночь"),
                "receipt" to listOf("Проверка результата"),
                "team" to listOf("Team A1", "Team A2"),
                "layout" to listOf("Layout acceptance club"),
            )
        val member = OnboardingActor(RECIPIENT, "platform")
        return buildMap {
            for ((scenario, venues) in names) {
                for (browser in listOf("chromium", "webkit")) {
                    val key = "$scenario-$browser"
                    val invitation =
                        repository.mutate(
                            OnboardingActor(OWNER, "platform"),
                            OnboardingMutation.INVITATION_ISSUE,
                            null,
                            buildJsonObject {
                                put("clientName", "До подтверждения владельцев · $key")
                                put("recipientLabel", "Участник")
                                put("allowedClubs", venues.size + 1)
                            },
                            "historical-invite-$key",
                        )
                    val clientId = invitation.getValue("clientId").jsonPrimitive.long
                    repository.mutate(
                        member,
                        OnboardingMutation.INVITATION_CLAIM,
                        invitation.getValue("id").jsonPrimitive.long,
                        buildJsonObject {
                            put("token", invitation.getValue("token"))
                            put("expectedVersion", invitation.getValue("version"))
                        },
                        "historical-claim-$key",
                    )
                    val clubs =
                        venues.mapIndexed { index, name ->
                            repository.mutate(
                                member,
                                OnboardingMutation.CLUB_CREATE,
                                clientId,
                                OnboardingLocations.creationBody(
                                    buildJsonObject {
                                        put("venueName", name)
                                        put("countryCode", "RU")
                                        put("cityId", "524901")
                                        put("address", "Тестовая улица, $index")
                                    },
                                ),
                                "historical-club-$key-$index",
                            )
                        }
                    put(
                        key,
                        buildJsonObject {
                            put("clientId", clientId.toString())
                            put("clientName", invitation.getValue("clientName"))
                            put("clubs", JsonArray(clubs))
                        },
                    )
                }
            }
        }
    }

    private fun signed(
        persona: String?,
        startParam: String?,
    ): String {
        val user =
            when (persona) {
                "owner" -> OWNER
                "recipient" -> RECIPIENT
                "foreign" -> 998103L
                "staff" -> 998104L
                "second-manager" -> 998105L
                else -> error("Unknown synthetic persona")
            }
        val fields =
            mutableMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
            )
        if (startParam != null) {
            require(Regex("c[im]_[A-Za-z0-9_-]{43}").matches(startParam))
            fields["start_param"] = startParam
        }
        return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, fields)
    }

    private const val OWNER = 998101L
    private const val RECIPIENT = 998102L
}

/** Test-only setup for data that is activated separately from the client cabinet. */
private fun seedBrowserFloorNight(
    connection: Connection,
    club: Long,
    table: Long,
): Long {
    connection.autoCommit = false
    val (client, zone, bookingTable) =
        connection
            .prepareStatement(
                "SELECT cc.client_id,c.timezone,ht.booking_table_id FROM client_clubs cc " +
                    "JOIN clubs c ON c.id=cc.club_id JOIN halls h ON h.club_id=c.id " +
                    "JOIN hall_tables ht ON ht.hall_id=h.id AND ht.booking_club_id=c.id " +
                    "WHERE c.id=? AND ht.id=? AND c.name LIKE 'Мобильная смена %'",
            ).use { statement ->
                statement.setLong(1, club)
                statement.setLong(2, table)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    Triple(
                        rows.getLong("client_id"),
                        ZoneId.of(rows.getString("timezone")),
                        rows.getLong("booking_table_id"),
                    )
                }
            }
    val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val start = now.minusSeconds(120)
    val end = now.plusSeconds(3600)
    val startedAt = start.atOffset(ZoneOffset.UTC)
    val endsAt = end.atOffset(ZoneOffset.UTC)
    val createdAt = now.atOffset(ZoneOffset.UTC)
    connection.fixtureWrite("UPDATE tables SET active=TRUE WHERE id=? AND club_id=?", bookingTable, club)
    val event =
        connection.fixtureInsert(
            "INSERT INTO events(club_id,start_at,end_at) VALUES (?,?,?)",
            club,
            startedAt,
            endsAt,
        )
    val night =
        connection.fixtureInsert(
            "INSERT INTO club_operational_nights(client_id,club_id,business_date,timezone,start_at,end_at," +
                "source,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'EXCEPTION','SCHEDULED',?,?)",
            client,
            club,
            start.atZone(zone).toLocalDate(),
            zone.id,
            startedAt,
            endsAt,
            createdAt,
            createdAt,
        )
    connection.fixtureWrite(
        "INSERT INTO canonical_night_event_links(club_id,canonical_night_id,event_id,legacy_start_at,legacy_end_at) " +
            "VALUES (?,?,?,?,?)",
        club,
        night,
        event,
        startedAt,
        endsAt,
    )
    connection.fixtureWrite(
        "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) VALUES (?,?,?)",
        club,
        night,
        bookingTable,
    )
    connection.fixtureWrite(
        "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) VALUES (?,?,'DRAFT',?,?)",
        club,
        startedAt,
        createdAt,
        createdAt,
    )
    connection.commit()
    return night
}

private fun Connection.fixtureWrite(
    sql: String,
    vararg values: Any,
) {
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        check(statement.executeUpdate() == 1)
    }
}

private fun Connection.fixtureInsert(
    sql: String,
    vararg values: Any,
): Long =
    prepareStatement(sql, arrayOf("id")).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        check(statement.executeUpdate() == 1)
        statement.generatedKeys.use { keys ->
            check(keys.next())
            keys.getLong(1)
        }
    }
