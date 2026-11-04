package com.example.bot.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.SQLException
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class UnifiedClubTablesRoutesTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `create 15 physical tables without a plan place move and clear without creating another pair`() =
        database { source ->
            withApp(source) {
                val clubId = createClub(RECIPIENT)
                val base = "$C/clubs/$clubId"
                var hall = createHall(base, "Main")
                var second = createHall(base, "Second")
                val firstId = hall.string("id")
                val secondId = second.string("id")
                val path = "$base/halls/$firstId"
                val numbers = (101..115).toList()
                val body = batch(hall, numbers)
                val created = ok("$path/tables/batch", RECIPIENT, body, key = "bulk-fifteen")
                hall = created.hall(firstId)
                assertEquals(created, ok("$path/tables/batch", RECIPIENT, body, key = "bulk-fifteen"))
                assertEquals("15", hall.string("tableCount"))
                assertEquals(numbers.map(Int::toString), hall.tables().map { it.string("tableNumber") })
                assertTrue(hall.tables().all { it["x"] == JsonNull && it.string("bookingLinked") == "true" })
                assertEquals("15", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$clubId AND active=FALSE"))
                assertEquals(
                    "15",
                    source.scalar(
                        "SELECT COUNT(*) FROM hall_tables WHERE hall_id=$firstId AND booking_table_id IS NOT NULL",
                    ),
                )
                second =
                    ok(
                        "$base/halls/$secondId/tables/batch",
                        RECIPIENT,
                        batch(second, listOf(201, 208)),
                    ).hall(secondId)
                assertEquals(listOf("201", "208"), second.tables().map { it.string("tableNumber") })
                requestAs("$base/halls/$secondId/tables/batch", RECIPIENT, batch(second, listOf(110)))
                    .assertClientError(HttpStatusCode.Conflict, "table_number_conflict")
                val first = hall.tables().first().string("id")
                requestAs("$path/tables/$first", RECIPIENT, coords(hall, 0.2, 0.7), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "hall_plan_required")
                upload(path, hall, 0x001100)
                hall = ok("$base/layout", RECIPIENT).hall(firstId)
                for ((index, id) in hall.tables().map { it.string("id") }.withIndex()) {
                    hall =
                        ok(
                            "$path/tables/$id",
                            RECIPIENT,
                            coords(hall, index / 15.0, 0.8),
                            HttpMethod.Patch,
                        ).hall(firstId)
                }
                assertTrue(hall.tables().all { it.string("placed") == "true" })
                val moved = ok("$path/tables/$first", RECIPIENT, coords(hall, 0.9, 0.2), HttpMethod.Patch).hall(firstId)
                assertEquals(JsonPrimitive(0.9), moved.tables().first()["x"])
                hall = ok("$path/tables/$first", RECIPIENT, coords(moved, null, null), HttpMethod.Patch).hall(firstId)
                upload(path, hall, 0x220000)
                val reopened = ok("$base/layout", RECIPIENT).hall(firstId)
                assertEquals(hall.tables(), reopened.tables())
                assertEquals("15", reopened.string("tableCount"))
                assertEquals(JsonNull, reopened.tables().first()["x"])
                assertEquals("17", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$clubId"))
                val guestTable = ok("$base/booking-preview", RECIPIENT).hall(firstId).tables().first()
                assertEquals("true", guestTable.string("bookingLinked"))
                assertFalse("bookingDeposit" in guestTable)
            }
        }

    @Test
    fun `club lock rejects a cross hall duplicate and revoke prevents replay`() =
        database { source ->
            withApp(source) {
                val clubId = createClub(RECIPIENT)
                val other = createClub(FOREIGN)
                val base = "$C/clubs/$clubId"
                val hallA = createHall(base, "A")
                val hallB = createHall(base, "B")
                val paths = listOf(hallA, hallB).associateWith { "$base/halls/${it.string("id")}/tables/batch" }
                source.grantSecondEditor(clubId)
                val responses =
                    coroutineScope {
                        paths.entries
                            .mapIndexed { index, (hall, path) ->
                                val actor = if (index == 0) RECIPIENT else SECOND_EDITOR
                                async { requestAs(path, actor, batch(hall, listOf(327))) }
                            }.map { it.await().status }
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), responses.toSet())
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$clubId AND table_number=327"),
                )
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM hall_tables WHERE booking_club_id=$clubId"))
                val path = "$base/halls/${hallA.string("id")}/tables/batch"
                val liveHall = ok("$base/layout", RECIPIENT).hall(hallA.string("id"))
                val body = batch(liveHall, listOf(328, 329))
                requestAs(path, RECIPIENT, batch(liveHall, listOf(328, 328)))
                    .assertClientError(HttpStatusCode.Conflict, "table_number_conflict")
                requestAs(path, FOREIGN, body).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs("$C/clubs/$other/halls/${hallA.string("id")}/tables/batch", FOREIGN, body)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                ok(path, RECIPIENT, body, key = "replay-328")
                requestAs(path, RECIPIENT, batch(liveHall, listOf(330)), key = "replay-328")
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                source.execute("UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$clubId")
                requestAs(path, RECIPIENT, body, key = "replay-328")
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("3", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$clubId"))
            }
        }

    @Test
    fun `authoritative fields cascade without converting historical values and a failed audit rolls back the batch`() =
        database { source ->
            withApp(source) {
                val clubId = createClub(RECIPIENT)
                val foreignClub = createClub(FOREIGN)
                val base = "$C/clubs/$clubId"
                var hall = createHall(base, "Main")
                val hallId = hall.string("id")
                val path = "$base/halls/$hallId/tables"
                source.seed214(clubId, hallId)
                requestAs("$path/batch", RECIPIENT, batch(hall, listOf(214)))
                    .assertClientError(HttpStatusCode.Conflict, "table_number_conflict")
                val single =
                    layoutVersion(hall) +
                        buildJsonObject {
                            put("label", "New")
                            put("tableNumber", 215)
                            put("capacity", 6)
                            put("minDeposit", "123.45")
                        }
                for (bad in listOf("1.234", "-1", "10000000000")) {
                    requestAs(path, RECIPIENT, JsonObject(single + ("minDeposit" to JsonPrimitive(bad))))
                        .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                }
                requestAs(path, RECIPIENT, JsonObject(single + ("capacity" to JsonPrimitive(51))))
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                hall = ok(path, RECIPIENT, JsonObject(single)).hall(hallId)
                val id = hall.tables().last().string("id")
                val bookingId = source.scalar("SELECT booking_table_id FROM hall_tables WHERE id=$id")
                val change =
                    layoutVersion(hall) +
                        buildJsonObject {
                            put("tableNumber", 216)
                            put("capacity", 10)
                            put("minDeposit", "50.07")
                        }
                hall = ok("$path/$id", RECIPIENT, JsonObject(change), HttpMethod.Patch).hall(hallId)
                assertEquals("216", hall.tables().last().string("tableNumber"))
                assertEquals("10", hall.tables().last().string("capacity"))
                assertEquals("50.07", hall.tables().last().string("bookingDeposit"))
                assertEquals("216", source.scalar("SELECT table_number FROM tables WHERE id=$bookingId"))
                assertFailsWith<SQLException> { source.execute("UPDATE hall_tables SET capacity=11 WHERE id=$id") }
                assertFailsWith<SQLException> {
                    source.execute("UPDATE hall_tables SET booking_club_id=$foreignClub WHERE id=$id")
                }
                assertFailsWith<SQLException> { source.execute("DELETE FROM tables WHERE id=$bookingId") }
                val secondHall = createHall(base, "Pair uniqueness").string("id")
                assertFailsWith<SQLException> {
                    source.execute(
                        "INSERT INTO hall_tables(hall_id,table_number,label,capacity,zone_id," +
                            "booking_table_id,booking_club_id) VALUES " +
                            "($secondHall,216,'Duplicate pair',10,'main',$bookingId,$clubId)",
                    )
                }
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT unified_table_audit_fail " +
                        "CHECK (action <> 'client.club.layout.table.batch_create')",
                )
                requestAs("$path/batch", RECIPIENT, batch(hall, listOf(217, 218)))
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(hall, ok("$base/layout", RECIPIENT).hall(hallId))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$clubId"))
                val historical = hall.tables().first()
                assertEquals("88214", historical.string("id"))
                assertEquals("false", historical.string("bookingLinked"))
                assertEquals("8765", source.scalar("SELECT min_deposit FROM hall_tables WHERE id=88214"))
                assertEquals("88215", source.scalar("SELECT id FROM tables WHERE club_id=$clubId AND table_number=214"))
                assertEquals("88215", source.scalar("SELECT table_id FROM bookings WHERE club_id=$clubId"))
            }
        }

    @Test
    fun `V072 leaves a V071 historical table and booking unlinked with original coordinates`() =
        databaseAt("71") { source ->
            source.execute(
                "INSERT INTO clubs(id,name,timezone,is_active) VALUES (88200,'Historical club','UTC',FALSE)",
            )
            source.execute(
                "INSERT INTO halls(id,club_id,name,geometry_json,geometry_fingerprint) " +
                    "VALUES (88201,88200,'Historical hall','{}','legacy')",
            )
            source.seed214("88200", "88201")
            val dialect =
                source.connection.use {
                    if (it.metaData.databaseProductName == "PostgreSQL") "postgresql" else "h2"
                }
            migrateUnifiedTables(source, dialect, null)
            assertEquals(
                "1",
                source.scalar(
                    "SELECT COUNT(*) FROM hall_tables WHERE id=88214 AND table_number=214 AND is_placed=TRUE " +
                        "AND x=0.2 AND y=0.4 AND min_deposit=8765 AND booking_table_id IS NULL",
                ),
            )
            assertEquals("88215", source.scalar("SELECT table_id FROM bookings WHERE club_id=88200"))
            assertEquals("8.76", source.scalar("SELECT min_deposit FROM tables WHERE id=88215"))
        }

    private suspend fun ApplicationTestBuilder.createClub(user: Long): String {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Tenant $user", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            user,
            obj("token" to invite.string("token"), "expectedVersion" to invite.string("version")),
        )
        return ok(
            "$C/clients/${invite.string("clientId")}/clubs",
            user,
            obj("venueName" to "Test club", "countryCode" to "RU", "cityId" to "524901", "address" to "Test street"),
        ).string("id")
    }

    private suspend fun ApplicationTestBuilder.createHall(
        base: String,
        name: String,
    ): JsonObject =
        ok("$base/halls", RECIPIENT, obj("name" to name))
            .getValue("halls")
            .jsonArray
            .last()
            .jsonObject

    private suspend fun ApplicationTestBuilder.upload(
        path: String,
        hall: JsonObject,
        color: Int,
    ) {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val response =
            client.request("$path/plan") {
                method = HttpMethod.Post
                header("X-Telegram-Init-Data", signed(RECIPIENT))
                header("X-Layout-Version", hall.string("layoutRevision"))
                header("Idempotency-Key", "image-$color")
                header("Content-Type", "image/png")
                setBody(bytes)
            }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private fun DataSource.grantSecondEditor(clubId: String) {
        execute("INSERT INTO users(id,telegram_user_id) VALUES (88777,$SECOND_EDITOR)")
        execute(
            "INSERT INTO client_memberships(client_id,user_id,is_active) " +
                "SELECT client_id,88777,TRUE FROM client_clubs WHERE club_id=$clubId",
        )
        for (permission in listOf("client.club.read", "client.club.layout.write")) {
            execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "SELECT client_id,88777,club_id,'$permission',TRUE FROM client_clubs WHERE club_id=$clubId",
            )
        }
    }

    private fun DataSource.seed214(
        clubId: String,
        hallId: String,
    ) {
        execute(
            "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id,min_deposit,x,y) " +
                "VALUES (88214,$hallId,214,'Historical 214',9,'main',8765,0.2,0.4)",
        )
        execute("INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (88215,$clubId,214,9,8.76)")
        execute(
            "INSERT INTO events(id,club_id,start_at,end_at) VALUES " +
                "(88216,$clubId,'2027-01-01T22:00:00Z','2027-01-02T06:00:00Z')",
        )
        execute(
            "INSERT INTO bookings(id,club_id,event_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
                "slot_start,slot_end,status,qr_secret,idempotency_key) VALUES " +
                "('2e9bd069-bd48-4a4b-9a22-56246159a30b',$clubId,88216,88215,214,2,8.76,8.76," +
                "'2027-01-01T22:00:00Z','2027-01-02T06:00:00Z','BOOKED','synthetic-old-table','synthetic-old-table')",
        )
    }

    private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

    private fun layoutVersion(hall: JsonObject): Map<String, JsonPrimitive> =
        mapOf("expectedVersion" to JsonPrimitive(hall.string("layoutRevision")))

    private fun coords(
        hall: JsonObject,
        x: Double?,
        y: Double?,
    ): JsonObject =
        JsonObject(
            layoutVersion(hall) +
                buildJsonObject {
                    put("x", x)
                    put("y", y)
                },
        )

    private fun batch(
        hall: JsonObject,
        numbers: List<Int>,
    ): JsonObject =
        JsonObject(
            layoutVersion(hall) +
                buildJsonObject {
                    put("tableNumbers", JsonArray(numbers.map(::JsonPrimitive)))
                    put("capacity", 4)
                },
        )

    private fun JsonObject.hall(id: String): JsonObject =
        getValue("halls").jsonArray.map { it.jsonObject }.single { it.string("id") == id }

    private fun JsonObject.tables(): List<JsonObject> = getValue("tables").jsonArray.map { it.jsonObject }

    private companion object {
        const val SECOND_EDITOR = 998104L
    }
}

@Tag("it")
class UnifiedClubTablesPostgresIT : UnifiedClubTablesRoutesTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source =
                PGSimpleDataSource().apply {
                    setUrl(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }
            migrateUnifiedTables(source, "postgresql", target)
            block(source)
        }
    }
}

private fun migrateUnifiedTables(
    source: DataSource,
    dialect: String,
    target: String?,
) {
    Flyway
        .configure()
        .dataSource(source)
        .locations("classpath:db/migration/common", "classpath:db/migration/$dialect")
        .apply { target?.let { target(it) } }
        .load()
        .migrate()
}
