package com.example.bot.data.client.onboarding

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** Zone persistence and racing commands run unchanged on H2 and disposable PostgreSQL. */
internal abstract class ClubZoneContract : ClubLayoutContract() {
    @Test
    fun `zones persist names assignments and preserve physical table identity and coordinates`() =
        runBlocking<Unit> {
            val club = create(approved())
            var hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            hall = command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall)).firstHall()
            val originalTable =
                hall
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
            val bookings = fixture.rows("SELECT * FROM tables ORDER BY id")
            val createZone = withHall(hall, buildJsonObject { put("name", "У сцены") })
            hall = command(club, OnboardingMutation.CLUB_ZONE_CREATE, createZone, "zone-create-replay").firstHall()
            assertEquals(
                hall,
                command(club, OnboardingMutation.CLUB_ZONE_CREATE, createZone, "zone-create-replay").firstHall(),
            )
            val zone =
                hall
                    .getValue("zones")
                    .jsonArray
                    .last()
                    .jsonObject
            hall =
                command(
                    club,
                    OnboardingMutation.CLUB_TABLE_UPDATE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("tableId", originalTable.getValue("id"))
                            put("zoneId", zone.getValue("id"))
                        },
                    ),
                ).firstHall()
            val assigned =
                hall
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(JsonObject(originalTable - "zoneId"), JsonObject(assigned - "zoneId"))
            assertEquals(bookings, fixture.rows("SELECT * FROM tables ORDER BY id"))
            val storageBefore = fixture.rows("SELECT * FROM hall_tables ORDER BY id")
            hall =
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_UPDATE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", zone.getValue("id"))
                            put("name", "Сцена")
                        },
                    ),
                ).firstHall()
            assertEquals(storageBefore, fixture.rows("SELECT * FROM hall_tables ORDER BY id"))
            assertEquals(
                "Сцена",
                hall
                    .getValue("zones")
                    .jsonArray
                    .last()
                    .jsonObject
                    .string("name"),
            )
            expect("zone_in_use") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_REMOVE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", zone.getValue("id"))
                        },
                    ),
                )
            }
            fixture.execute("UPDATE hall_tables SET is_active=FALSE")
            expect("zone_in_use") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_REMOVE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", zone.getValue("id"))
                        },
                    ),
                )
            }
            fixture.execute("UPDATE hall_tables SET is_active=TRUE")
            val firstZone =
                hall
                    .getValue("zones")
                    .jsonArray
                    .first()
                    .jsonObject
            hall =
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_REMOVE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", firstZone.getValue("id"))
                        },
                    ),
                ).firstHall()
            val fresh = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(hall, fresh.read(fixture.alice, OnboardingRead.CLUB_LAYOUT_GET, club.id("id")).firstHall())
            assertEquals(1, fixture.rows("SELECT id FROM audit_log WHERE action='client.club.layout.zone.create'").size)
        }

    @Test
    fun `zone commands isolate tenants validate hall ownership and recheck grant on durable replay`() =
        runBlocking<Unit> {
            val application = approved()
            val club = create(application)
            var hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val main =
                hall
                    .getValue("zones")
                    .jsonArray
                    .single()
                    .jsonObject
            expect("last_zone") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_REMOVE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", main.getValue("id"))
                        },
                    ),
                )
            }
            val body = withHall(hall, buildJsonObject { put("name", "Балкон") })
            fixture.accept(fixture.bob)
            expect("not_found") { command(club, OnboardingMutation.CLUB_ZONE_CREATE, body, actor = fixture.bob) }
            hall = command(club, OnboardingMutation.CLUB_ZONE_CREATE, body, "zone-revoke-replay").firstHall()
            expect("version_conflict") { command(club, OnboardingMutation.CLUB_ZONE_CREATE, body) }
            expect("zone_name_conflict") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_CREATE,
                    withHall(hall, buildJsonObject { put("name", "балкон") }),
                )
            }
            val second =
                command(club, OnboardingMutation.CLUB_HALL_CREATE, buildJsonObject { put("name", "Другой зал") })
                    .getValue("halls")
                    .jsonArray
                    .last()
                    .jsonObject
            val foreignZone =
                second
                    .getValue("zones")
                    .jsonArray
                    .single()
                    .jsonObject
            expect("not_found") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_UPDATE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put("zoneId", foreignZone.getValue("id"))
                            put("name", "Подмена")
                        },
                    ),
                )
            }
            hall = command(club, OnboardingMutation.CLUB_TABLE_CREATE, tableBody(hall)).firstHall()
            expect("not_found") {
                command(
                    club,
                    OnboardingMutation.CLUB_TABLE_UPDATE,
                    withHall(
                        hall,
                        buildJsonObject {
                            put(
                                "tableId",
                                hall
                                    .getValue("tables")
                                    .jsonArray
                                    .single()
                                    .jsonObject
                                    .getValue("id"),
                            )
                            put("zoneId", foreignZone.getValue("id"))
                        },
                    ),
                )
            }
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$WRITE_LAYOUT'")
            expect("not_found") { command(club, OnboardingMutation.CLUB_ZONE_CREATE, body, "zone-revoke-replay") }
            assertEquals(JsonArray(listOf(JsonPrimitive(READ_CLUB))), layout(club)["permissions"])
            revoke(application)
            expect("not_found") { layout(club) }
        }

    @Test
    fun `zone mutation races serialize versions duplicate replay and atomic rollback`() =
        runBlocking<Unit> {
            val club = create(approved())
            var hall = command(club, OnboardingMutation.CLUB_HALL_CREATE, name()).firstHall()
            val body = withHall(hall, buildJsonObject { put("name", "Терраса") })
            assertEquals(
                listOf("ok", "ok"),
                race(
                    { command(club, OnboardingMutation.CLUB_ZONE_CREATE, body, "same-zone-race") },
                    { command(club, OnboardingMutation.CLUB_ZONE_CREATE, body, "same-zone-race") },
                ),
            )
            hall = layout(club).firstHall()
            val zone =
                hall
                    .getValue("zones")
                    .jsonArray
                    .last()
                    .jsonObject
            val rename =
                withHall(
                    hall,
                    buildJsonObject {
                        put("zoneId", zone.getValue("id"))
                        put("name", "Новая терраса")
                    },
                )
            assertEquals(
                listOf("ok", "version_conflict"),
                race(
                    { command(club, OnboardingMutation.CLUB_ZONE_UPDATE, rename) },
                    { command(club, OnboardingMutation.CLUB_ZONE_UPDATE, rename) },
                ).sorted(),
            )
            hall = layout(club).firstHall()
            val before = layoutState()
            try {
                fixture.failingAfterRepository("INSERT INTO client_commands").mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_ZONE_REMOVE,
                    club.id("id"),
                    withHall(hall, buildJsonObject { put("zoneId", zone.getValue("id")) }),
                    "zone-rollback-remove",
                )
                error("Expected rollback")
            } catch (_: SQLException) {
                assertEquals(before, layoutState())
            }
            expect("idempotency_conflict") {
                command(
                    club,
                    OnboardingMutation.CLUB_ZONE_CREATE,
                    withHall(hall, buildJsonObject { put("name", "Другая зона") }),
                    "same-zone-race",
                )
            }
        }
}
