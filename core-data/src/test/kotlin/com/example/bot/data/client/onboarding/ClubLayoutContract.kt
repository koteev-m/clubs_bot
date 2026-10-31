package com.example.bot.data.client.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** Same persistence, migration and racing-command assertions run on H2 and disposable PostgreSQL. */
internal abstract class ClubLayoutContract : ClubLayoutMigrationContract() {
    @Test
    fun `direct club creation grants layout only to its confirmed creator and staff read remains read only`() =
        runBlocking<Unit> {
            val accepted = fixture.claim(fixture.issueDirect())
            val clientId = accepted.id("clientId")
            val club =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    buildJsonObject {
                        put("venueName", "Direct layout venue")
                        put("countryCode", "RU")
                        put("city", "Synthetic city")
                        put("cityId", "geonames:12345")
                        put("region", "Synthetic region")
                        put("address", "Synthetic address")
                        put("timezone", "Europe/Moscow")
                    },
                    "layout-direct-creation",
                )
            fixture.accept(fixture.bob)
            val bob =
                fixture
                    .rows(
                        "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                    ).single()
                    .long("id")
            fixture.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$bob,TRUE)")
            fixture.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "($clientId,$bob,${club.id(
                        "id",
                    )},'$READ_CLUB',TRUE),($clientId,$bob,${club.id("id")},'$WRITE_PROFILE',TRUE)",
            )
            val grants =
                fixture.rows(
                    "SELECT user_id FROM client_club_grants WHERE club_id=${club.id(
                        "id",
                    )} AND permission_code='$WRITE_LAYOUT'",
                )
            assertEquals(1, grants.size)
            assertEquals(
                fixture.rows("SELECT owner_user_id FROM client_direct_club_profiles").single()["owner_user_id"],
                grants.single()["user_id"],
            )
            assertEquals(
                JsonArray(listOf(JsonPrimitive(READ_CLUB))),
                fixture.repository.read(fixture.bob, OnboardingRead.CLUB_LAYOUT_GET, club.id("id"))["permissions"],
            )
            expect("not_found") { command(club, OnboardingMutation.CLUB_HALL_CREATE, name(), actor = fixture.bob) }
        }

    @Test
    fun `layout persistence reopens halls tables coordinates and decoded image without JSON input`() =
        runBlocking<Unit> {
            val club = create(approved())
            assertTrue(club.getValue("permissions").jsonArray.contains(JsonPrimitive(WRITE_LAYOUT)))
            val created =
                command(club, OnboardingMutation.CLUB_HALL_CREATE, buildJsonObject { put("name", "Основной") })
            var hall = created.firstHall()
            assertEquals("1", hall.string("layoutRevision"))
            assertEquals(
                "main",
                fixture.rows("SELECT zone_id FROM hall_zones ORDER BY id DESC LIMIT 1").single()["zone_id"],
            )
            hall = command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall)).firstHall()
            val table =
                hall
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("12", table.getValue("tableNumber").toString())
            hall =
                command(
                    club,
                    OnboardingMutation.CLUB_TABLE_UPDATE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("tableId", table.getValue("id"))
                            put("label", "У окна")
                            put("capacity", 8)
                            put("x", 0.95)
                            put("y", 0.05)
                        },
                    ),
                ).firstHall()
            hall = upload(club, hall, png() + "trailing metadata".toByteArray()).firstHall()
            assertEquals("4", hall.string("layoutRevision"))
            val fresh = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(hall, fresh.read(fixture.alice, OnboardingRead.CLUB_LAYOUT_GET, club.id("id")).firstHall())
            val image = plan(club, hall)
            val bytes = Base64.getDecoder().decode(image.string("bytesBase64"))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("trailing metadata"))
            assertEquals(2, ImageIO.read(ByteArrayInputStream(bytes)).width)
            assertEquals("image/png", image.string("contentType"))
            assertEquals(
                "0.95",
                hall
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("x")
                    .toString(),
            )
        }

    @Test
    fun `JPEG plans roundtrip and image decompression size is bounded before pixel allocation`() =
        runBlocking<Unit> {
            val club = create(approved())
            var hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            expect("validation_error") { upload(club, hall, raster("png", 5001, 5000)) }
            hall = upload(club, hall, raster("jpeg"), contentType = "image/jpeg").firstHall()
            val saved = plan(club, hall)
            assertEquals("image/jpeg", saved.string("contentType"))
            val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(saved.string("bytesBase64"))))
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertEquals("2", hall.string("layoutRevision"))
        }

    @Test
    fun `layout requires exact current read and write with no platform owner or member manage bypass`() =
        runBlocking<Unit> {
            val club = create(approved())
            fixture.accept(fixture.bob)
            listOf(fixture.owner, fixture.bob).forEach { actor ->
                expect("not_found") { fixture.repository.read(actor, OnboardingRead.CLUB_LAYOUT_GET, club.id("id")) }
                expect("not_found") { command(club, OnboardingMutation.CLUB_HALL_CREATE, name(), actor = actor) }
            }
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$WRITE_LAYOUT'")
            assertEquals(JsonArray(listOf(JsonPrimitive(READ_CLUB))), layout(club)["permissions"])
            expect("not_found") { command(club, OnboardingMutation.CLUB_HALL_CREATE, name()) }
            fixture.execute("UPDATE client_club_grants SET is_active=TRUE WHERE permission_code='$WRITE_LAYOUT'")
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$READ_CLUB'")
            expect("not_found") { layout(club) }
            expect("not_found") { command(club, OnboardingMutation.CLUB_HALL_CREATE, name()) }
            assertEquals(0, fixture.rows("SELECT id FROM halls WHERE club_id=${club.id("id")}").size)
        }

    @Test
    fun `foreign hall table and image cannot cross client club or hall despite valid caller grants`() =
        runBlocking<Unit> {
            val a = create(approved())
            val bApplication = fixture.approved(fixture.accept(fixture.bob).id("clientId"), fixture.bob, "B")
            val b = create(bApplication, fixture.bob)
            val aHall = command(a, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val bHall = command(b, OnboardingMutation.CLUB_HALL_CREATE, name(), actor = fixture.bob).firstHall()
            val bWithTable =
                command(
                    b,
                    OnboardingMutation.CLUB_TABLE_CREATE,
                    tableBody(bHall),
                    actor = fixture.bob,
                ).firstHall()
            expect("not_found") { command(a, OnboardingMutation.CLUB_HALL_UPDATE, withHall(bHall, name())) }
            expect("not_found") { plan(a, bHall) }
            expect("not_found") { upload(a, bHall, png()) }
            expect("not_found") {
                command(
                    a,
                    OnboardingMutation.CLUB_TABLE_UPDATE,
                    withHall(
                        aHall,
                        buildJsonObject {
                            put(
                                "tableId",
                                bWithTable
                                    .getValue("tables")
                                    .jsonArray
                                    .single()
                                    .jsonObject
                                    .getValue("id"),
                            )
                            put("label", "forged")
                        },
                    ),
                )
            }
            val another =
                command(
                    a,
                    OnboardingMutation.CLUB_HALL_CREATE,
                    buildJsonObject {
                        put("name", "Second")
                    },
                ).getValue("halls").jsonArray.last().jsonObject
            expect("not_found") { plan(a, another) }
            assertEquals(0, aHall.getValue("tables").jsonArray.size)
        }

    @Test
    fun `lost responses and parallel same command create one hall table image and audit with current access replay`() =
        runBlocking<Unit> {
            val club = create(approved())
            val first = command(club, OnboardingMutation.CLUB_HALL_CREATE, name(), "layout-lost-hall")
            assertEquals(first, command(club, OnboardingMutation.CLUB_HALL_CREATE, name(), "layout-lost-hall"))
            expect("idempotency_conflict") {
                command(
                    club,
                    OnboardingMutation.CLUB_HALL_CREATE,
                    buildJsonObject { put("name", "Changed") },
                    "layout-lost-hall",
                )
            }
            val hall = first.firstHall()
            val outcomes =
                race(
                    { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall), "layout-lost-table") },
                    { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall), "layout-lost-table") },
                )
            assertEquals(listOf("ok", "ok"), outcomes)
            val current = layout(club).firstHall()
            assertEquals(1, current.getValue("tables").jsonArray.size)
            val uploaded = upload(club, current, png(), "layout-lost-plan")
            assertEquals(uploaded, upload(club, current, png(), "layout-lost-plan"))
            assertEquals(3, fixture.rows("SELECT id FROM audit_log WHERE action LIKE 'client.club.layout.%'").size)
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$WRITE_LAYOUT'")
            expect("not_found") { command(club, OnboardingMutation.CLUB_HALL_CREATE, name(), "layout-lost-hall") }
            expect("not_found") { upload(club, current, png(), "layout-lost-plan") }
        }

    @Test
    fun `different concurrent writers and stale image updates conflict without lost changes`() =
        runBlocking<Unit> {
            val club = create(approved())
            val clientId = club.id("clientId")
            val clubId = club.id("id")
            fixture.accept(fixture.bob)
            val bobId =
                fixture
                    .rows(
                        "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                    ).single()
                    .long("id")
            fixture.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$bobId,TRUE)",
            )
            // Explicit fixture authority proves the shared client lock, not the per-actor command lock.
            fixture.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "($clientId,$bobId,$clubId,'$READ_CLUB',TRUE),($clientId,$bobId,$clubId,'$WRITE_LAYOUT',TRUE)",
            )
            val hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val outcomes =
                race(
                    { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall)) },
                    { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall, 13), actor = fixture.bob) },
                )
            assertEquals(listOf("ok", "version_conflict"), outcomes.sorted())
            expect("version_conflict") { upload(club, hall, png()) }
            assertEquals(
                1,
                layout(club)
                    .firstHall()
                    .getValue("tables")
                    .jsonArray.size,
            )
            assertEquals(0, fixture.rows("SELECT hall_id FROM hall_plans WHERE hall_id=${hall.id("id")}").size)
        }

    @Test
    fun `hall names table numbers capacities finite coordinates and real image formats are validated`() =
        runBlocking<Unit> {
            val club = create(approved())
            val hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            expect("hall_name_conflict") {
                command(club, OnboardingMutation.CLUB_HALL_CREATE, buildJsonObject { put("name", "  основной  ") })
            }
            listOf(
                buildJsonObject { put("capacity", 101) },
                buildJsonObject { put("tableNumber", 0) },
                buildJsonObject { put("x", 1.1) },
                buildJsonObject { put("y", -0.1) },
                buildJsonObject { put("label", "") },
            ).forEach { invalid ->
                expect("validation_error") {
                    command(
                        club,
                        OnboardingMutation.CLUB_TABLE_CREATE,
                        JsonObject(
                            tableBody(hall) + invalid,
                        ),
                    )
                }
            }
            expect("validation_error") { upload(club, hall, "<svg/>".toByteArray()) }
            expect("validation_error") { upload(club, hall, png(), contentType = "image/jpeg") }
            expect("unsupported_media_type") { upload(club, hall, png(), contentType = "image/svg+xml") }
            expect("payload_too_large") { upload(club, hall, ByteArray(5 * 1024 * 1024 + 1)) }
            val withTable = command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall)).firstHall()
            expect(
                "table_number_conflict",
            ) { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(withTable)) }
            assertEquals("2", layout(club).firstHall().string("layoutRevision"))
        }

    @Test
    fun `audit or command persistence failure rolls back hall zone table image and version`() =
        runBlocking<Unit> {
            val club = create(approved())
            val hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val attempts =
                listOf(
                    OnboardingMutation.CLUB_HALL_CREATE to buildJsonObject { put("name", "Rollback") },
                    OnboardingMutation.CLUB_TABLE_CREATE to tableBody(hall),
                    OnboardingMutation.CLUB_PLAN_UPLOAD to imageBody(hall, png()),
                )
            attempts.forEach { (operation, body) ->
                val before = layoutState()
                try {
                    fixture.failingAuditRepository().mutate(
                        fixture.alice,
                        operation,
                        club.id("id"),
                        body,
                        UUID.randomUUID().toString(),
                    )
                    error("Audit failure must roll back")
                } catch (_: SQLException) {
                    assertEquals(before, layoutState())
                }
            }
            val before = layoutState()
            try {
                fixture.failingAfterRepository("INSERT INTO client_commands").mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_TABLE_CREATE,
                    club.id("id"),
                    tableBody(hall),
                    "layout-command-rollback",
                )
                error("Command failure must roll back")
            } catch (_: SQLException) {
                assertEquals(before, layoutState())
            }
        }

    @Test
    fun `cancellation after table persistence preserves its cause and rolls back transaction`() =
        runBlocking<Unit> {
            val club = create(approved())
            val hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val before = layoutState()
            val cancellation = CancellationException("Synthetic layout cancellation")
            try {
                fixture.failingAfterRepository("INSERT INTO hall_tables", errorFactory = { cancellation }).mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_TABLE_CREATE,
                    club.id("id"),
                    tableBody(hall),
                    "layout-cancel-table",
                )
                error("Cancellation must propagate")
            } catch (actual: CancellationException) {
                // Coroutine debug stacktrace recovery may copy an exception, retaining the exact original cause.
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
                assertEquals(cancellation.message, actual.message)
                assertEquals(before, layoutState())
            }
        }

    @Test
    fun `member revoke removes layout and explicit restore never restores omitted capability`() =
        runBlocking<Unit> {
            val application = approved()
            val club = create(application)
            val hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            upload(club, hall, png())
            revoke(application)
            expect("not_found") { layout(club) }
            expect("not_found") { plan(club, hall) }
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_RESTORE,
                club.id("clientId"),
                buildJsonObject {
                    put("userId", application.getValue("authorUserId"))
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(emptyList()))
                    put(
                        "clubGrants",
                        JsonArray(
                            listOf(READ_CLUB, WRITE_PROFILE).map { permission ->
                                buildJsonObject {
                                    put("clubId", club.getValue("id"))
                                    put("permission", permission)
                                }
                            },
                        ),
                    )
                },
                "layout-explicit-restore",
            )
            assertEquals(JsonArray(listOf(JsonPrimitive(READ_CLUB))), layout(club)["permissions"])
            assertFalse(
                fixture
                    .rows(
                        "SELECT is_active FROM client_club_grants WHERE permission_code='$WRITE_LAYOUT'",
                    ).single()
                    .active(),
            )
            expect(
                "not_found",
            ) { command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(layout(club).firstHall())) }
        }

    private suspend fun command(
        club: JsonObject,
        operation: OnboardingMutation,
        body: JsonObject,
        key: String = UUID.randomUUID().toString(),
        actor: OnboardingActor = fixture.alice,
    ) = fixture.repository.mutate(actor, operation, club.id("id"), body, key)

    protected suspend fun layout(club: JsonObject) =
        fixture.repository.read(fixture.alice, OnboardingRead.CLUB_LAYOUT_GET, club.id("id"))

    private suspend fun plan(
        club: JsonObject,
        hall: JsonObject,
    ) = fixture.repository.read(
        fixture.alice,
        OnboardingRead.CLUB_PLAN_GET,
        club.id("id"),
        buildJsonObject { put("hallId", hall.getValue("id")) },
    )

    private suspend fun upload(
        club: JsonObject,
        hall: JsonObject,
        bytes: ByteArray,
        key: String = UUID.randomUUID().toString(),
        contentType: String = "image/png",
    ) = command(club, OnboardingMutation.CLUB_PLAN_UPLOAD, imageBody(hall, bytes, contentType), key)

    private fun imageBody(
        hall: JsonObject,
        bytes: ByteArray,
        contentType: String = "image/png",
    ) = withHall(
        hall,
        buildJsonObject {
            put("contentType", contentType)
            put("bytesBase64", Base64.getEncoder().encodeToString(bytes))
        },
    )

    private fun name() = buildJsonObject { put("name", "Основной") }

    protected fun JsonObject.firstHall() = getValue("halls").jsonArray.first().jsonObject

    protected fun tableBody(
        hall: JsonObject,
        number: Int = 12,
    ) = withHall(
        hall,
        buildJsonObject {
            put("tableNumber", number)
            put("label", "Стол $number")
            put("capacity", 4)
            put("x", 0.25)
            put("y", 0.75)
        },
    )

    protected fun withHall(
        hall: JsonObject,
        fields: JsonObject,
    ) = JsonObject(
        fields +
            buildJsonObject {
                put("hallId", hall.getValue("id"))
                put("expectedVersion", hall.getValue("layoutRevision"))
            },
    )

    private fun png(): ByteArray = raster("png")

    private fun raster(
        format: String,
        width: Int = 2,
        height: Int = 2,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            val type = if (width > 2) BufferedImage.TYPE_BYTE_BINARY else BufferedImage.TYPE_INT_RGB
            check(ImageIO.write(BufferedImage(width, height, type), format, output))
            output.toByteArray()
        }

    private fun layoutState() =
        listOf("halls", "hall_zones", "hall_tables", "hall_plans", "client_commands", "audit_log").associateWith {
            fixture.rows("SELECT * FROM $it ORDER BY 1")
        }
}
