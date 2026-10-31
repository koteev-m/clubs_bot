package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientLayoutRoutesDataTest {
    @Test
    fun `cabinet creates edits uploads and reopens layout with persisted optimistic versions`() =
        database { source ->
            lateinit var clubId: String
            lateinit var hallId: String
            lateinit var tableId: String
            val image = image()
            withApp(source) {
                clubId = createClub(RECIPIENT, "A")
                val initial = ok("$C/clubs/$clubId/layout", RECIPIENT)
                assertTrue(initial["halls"]!!.jsonArray.isEmpty())
                assertTrue(initial["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.layout.write")))
                val create = obj("name" to "Первый зал")
                val created = ok("$C/clubs/$clubId/halls", RECIPIENT, create, key = "hall-lost-response")
                assertEquals(created, ok("$C/clubs/$clubId/halls", RECIPIENT, create, key = "hall-lost-response"))
                var hall = created.hall()
                hallId = hall.string("id")
                val path = "$C/clubs/$clubId/halls/$hallId"
                val renamed =
                    ok(
                        path,
                        RECIPIENT,
                        obj("name" to "Основной", "expectedVersion" to hall.version()),
                        HttpMethod.Patch,
                    )
                hall = renamed.hall()
                val tableBody =
                    JsonObject(
                        obj("expectedVersion" to hall.version(), "label" to "У окна") +
                            mapOf(
                                "tableNumber" to JsonPrimitive(7),
                                "capacity" to JsonPrimitive(4),
                                "x" to JsonPrimitive(0.25),
                                "y" to JsonPrimitive(0.75),
                            ),
                    )
                val withTable = ok("$path/tables", RECIPIENT, tableBody, key = "table-lost-response")
                assertEquals(withTable, ok("$path/tables", RECIPIENT, tableBody, key = "table-lost-response"))
                hall = withTable.hall()
                tableId =
                    hall["tables"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val move =
                    JsonObject(
                        obj("expectedVersion" to hall.version(), "label" to "Сохранённый стол") +
                            mapOf("x" to JsonPrimitive(0.6), "y" to JsonPrimitive(0.4), "capacity" to JsonPrimitive(6)),
                    )
                val moved = ok("$path/tables/$tableId", RECIPIENT, move, HttpMethod.Patch)
                requestAs("$path/tables/$tableId", RECIPIENT, move, HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val uploaded = upload(path, RECIPIENT, image, moved.hall().version(), key = "plan-lost-response")
                assertEquals(HttpStatusCode.OK, uploaded.status, uploaded.bodyAsText())
                assertEquals(
                    HttpStatusCode.OK,
                    upload(path, RECIPIENT, image, moved.hall().version(), key = "plan-lost-response").status,
                )
                val plan = requestAs("$path/plan", RECIPIENT)
                assertEquals(HttpStatusCode.OK, plan.status)
                assertEquals("no-store", plan.headers["Cache-Control"])
                assertEquals("nosniff", plan.headers["X-Content-Type-Options"])
                assertEquals("image/png", plan.headers["Content-Type"])
                assertContentEquals(image, plan.bodyAsBytes())
            }
            withApp(source) {
                val hall = ok("$C/clubs/$clubId/layout", RECIPIENT).hall()
                assertEquals(hallId, hall.string("id"))
                assertEquals("Основной", hall.string("name"))
                val table = hall["tables"]!!.jsonArray.single().jsonObject
                assertEquals(tableId, table.string("id"))
                assertEquals("Сохранённый стол", table.string("label"))
                assertEquals(JsonPrimitive(6), table["capacity"])
                assertEquals(JsonPrimitive(0.6), table["x"])
                assertEquals(JsonPrimitive(0.4), table["y"])
                assertContentEquals(image, requestAs("$C/clubs/$clubId/halls/$hallId/plan", RECIPIENT).bodyAsBytes())
            }
        }

    @Test
    fun `foreign club hall table and image have no legacy GLOBAL or cross parent bypass`() =
        database { source ->
            withApp(source) {
                val a = createClub(RECIPIENT, "A")
                val b = createClub(FOREIGN, "B")
                val hallA = ok("$C/clubs/$a/halls", RECIPIENT, obj("name" to "A")).hall()
                val hallB = ok("$C/clubs/$b/halls", FOREIGN, obj("name" to "B")).hall()
                val idA = hallA.string("id")
                val idB = hallB.string("id")
                val tableLayout =
                    ok(
                        "$C/clubs/$a/halls/$idA/tables",
                        RECIPIENT,
                        JsonObject(
                            obj("expectedVersion" to hallA.version(), "label" to "A table") +
                                mapOf(
                                    "tableNumber" to JsonPrimitive(1),
                                    "capacity" to JsonPrimitive(2),
                                    "x" to JsonPrimitive(0.5),
                                    "y" to JsonPrimitive(0.5),
                                ),
                        ),
                    )
                val tableA =
                    tableLayout
                        .hall()["tables"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val image = image()
                assertEquals(
                    HttpStatusCode.OK,
                    upload("$C/clubs/$a/halls/$idA", RECIPIENT, image, tableLayout.hall().version()).status,
                )
                source.sql(
                    "INSERT INTO user_roles(user_id,role_code,scope_type) " +
                        "SELECT id,'OWNER','GLOBAL' FROM users WHERE telegram_user_id=$FOREIGN",
                )
                for (path in listOf(
                    "$C/clubs/$a/layout",
                    "$C/clubs/$a/halls/$idA/plan",
                    "$C/clubs/$b/halls/$idA/plan",
                )) {
                    requestAs(path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                }
                requestAs("$C/clubs/$a/halls", FOREIGN, obj("name" to "forbidden"))
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                for ((club, hall, user) in listOf(
                    Triple(a, idB, RECIPIENT),
                    Triple(b, idA, FOREIGN),
                    Triple(a, idA, FOREIGN),
                )) {
                    val path = "$C/clubs/$club/halls/$hall"
                    requestAs(path, user, obj("name" to "forbidden", "expectedVersion" to "1"), HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.NotFound, "not_found")
                    requestAs(
                        "$path/tables/$tableA",
                        user,
                        obj("label" to "forbidden", "expectedVersion" to "1"),
                        HttpMethod.Patch,
                    ).assertClientError(HttpStatusCode.NotFound, "not_found")
                    upload(path, user, image, "1").assertClientError(HttpStatusCode.NotFound, "not_found")
                }
                requestAs(
                    "$C/clubs/$b/halls/$idB/tables/$tableA",
                    FOREIGN,
                    obj("label" to "foreign-table", "expectedVersion" to hallB.version()),
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals(
                    "A table",
                    ok("$C/clubs/$a/layout", RECIPIENT)
                        .hall()["tables"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("label"),
                )
            }
        }

    @Test
    fun `layout write and read are separate exact grants and revoked reads never return cached image`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT, "Revoke")
                val hall =
                    ok(
                        "$C/clubs/$club/halls",
                        RECIPIENT,
                        obj("name" to "Saved"),
                        key = "revoke-created-hall",
                    ).hall()
                val path = "$C/clubs/$club/halls/${hall.string("id")}"
                assertEquals(HttpStatusCode.OK, upload(path, RECIPIENT, image(), hall.version()).status)
                source.sql(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club " +
                        "AND permission_code='client.club.layout.write'",
                )
                val read = ok("$C/clubs/$club/layout", RECIPIENT)
                assertFalse(read["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.layout.write")))
                assertEquals(HttpStatusCode.OK, requestAs("$path/plan", RECIPIENT).status)
                requestAs("$C/clubs/$club/halls", RECIPIENT, obj("name" to "No"))
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs("$C/clubs/$club/halls", RECIPIENT, obj("name" to "Saved"), key = "revoke-created-hall")
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.sql(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club " +
                        "AND permission_code='client.club.read'",
                )
                requestAs("$C/clubs/$club/layout", RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                val image =
                    client.request("$path/plan") {
                        header(HEADER, signed(RECIPIENT))
                        header("If-None-Match", "*")
                        header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                    }
                image.assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("no-store", image.headers["Cache-Control"])
                assertEquals(null, image.headers["ETag"])
            }
        }

    @Test
    fun `fresh canonical auth precedes layout input parsing and rejects image spoofing and oversized inputs`() =
        database { source ->
            withApp(source) {
                for (path in listOf("$C/clubs/1/layout", "$C/clubs/1/halls/1/plan", "$C/clubs/1/halls")) {
                    client
                        .request(path) { header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN") }
                        .assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                }
                val club = createClub(RECIPIENT, "Validation")
                val hall = ok("$C/clubs/$club/halls", RECIPIENT, obj("name" to "Valid")).hall()
                val path = "$C/clubs/$club/halls/${hall.string("id")}"
                requestAs("$C/clubs/$club/halls", RECIPIENT, obj("name" to "Spoof", "clientId" to "999"))
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                upload(path, RECIPIENT, "<svg/>".toByteArray(), hall.version(), type = "image/svg+xml")
                    .assertClientError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
                upload(path, RECIPIENT, "<svg/>".toByteArray(), hall.version())
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                upload(path, RECIPIENT, ByteArray(5 * 1024 * 1024 + 1), hall.version())
                    .assertClientError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
                requestAs(
                    "$C/clubs/01/layout",
                    RECIPIENT,
                ).assertClientError(HttpStatusCode.BadRequest, "validation_error")
                requestAs("$C/clubs/$club/layout?clientId=999", RECIPIENT)
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                assertEquals(
                    null,
                    ok("$C/clubs/$club/layout", RECIPIENT).hall()["plan"]?.takeUnless {
                        it.toString() ==
                            "null"
                    },
                )
            }
        }

    private suspend fun ApplicationTestBuilder.createClub(
        user: Long,
        name: String,
    ): String {
        val invitation = ok("$P/invitations", OWNER, obj("clientName" to name, "recipientLabel" to name))
        ok(
            "$C/invitations/${invitation.string("id")}/claim",
            user,
            obj(
                "token" to invitation.string("token"),
                "expectedVersion" to invitation.string("version"),
            ),
        )
        return ok(
            "$C/clients/${invitation.string("clientId")}/clubs",
            user,
            obj(
                "venueName" to name,
                "countryCode" to "RU",
                "cityId" to "524901",
                "address" to "Synthetic address $name",
            ),
        ).string("id")
    }

    private fun database(block: (JdbcDataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:layout_api_${UUID.randomUUID()};" +
                        "MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(
                source,
            ).locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        try {
            block(source)
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("SHUTDOWN") } }
        }
    }

    private fun withApp(
        source: JdbcDataSource,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            clientOnboardingRoutes(
                JdbcClientContextRepository(source),
                JdbcClientOnboardingRepository(source, OWNER),
                "synthetic_bot",
                OWNER,
            ) {
                TEST_BOT_TOKEN
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.requestAs(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse =
        client.request(path) {
            this.method = method
            header(HEADER, signed(user))
            if (body != null) {
                header("Content-Type", "application/json")
                header("Idempotency-Key", key)
                setBody(body.toString())
            }
        }

    private suspend fun ApplicationTestBuilder.ok(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): JsonObject {
        val response = requestAs(path, user, body, method, key)
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertEquals("no-store", response.headers["Cache-Control"])
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun ApplicationTestBuilder.upload(
        path: String,
        user: Long,
        bytes: ByteArray,
        version: String,
        type: String = "image/png",
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse =
        client.request("$path/plan") {
            method = HttpMethod.Post
            header(HEADER, signed(user))
            header("Content-Type", type)
            header("X-Layout-Version", version)
            header("Idempotency-Key", key)
            setBody(bytes)
        }

    private fun image(): ByteArray =
        ByteArrayOutputStream().use { stream ->
            ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", stream)
            stream.toByteArray()
        }

    private fun signed(user: Long): String =
        WebAppInitDataTestHelper.createInitData(
            TEST_BOT_TOKEN,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
            ),
        )

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(
            pairs.associate {
                it.first to
                    JsonPrimitive(it.second)
            },
        )

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.hall() = getValue("halls").jsonArray.single().jsonObject

    private fun JsonObject.version() = string("layoutRevision")

    private fun JdbcDataSource.sql(sql: String) =
        connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }

    private companion object {
        const val P = "/api/platform/onboarding"
        const val C = "/api/client"
        const val HEADER = "X-Telegram-Init-Data"
        const val OWNER = 998201L
        const val RECIPIENT = 998202L
        const val FOREIGN = 998203L
    }
}
