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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration

/** Profile tests share the same H2 and PostgreSQL contract harness. */
internal abstract class ClubProfileContract : ClubOnboardingContract() {
    @Test
    fun `profile read write uses exact grants with no membership manage or platform bypass`() =
        runBlocking<Unit> {
            val approved = approved()
            val profile = create(approved)
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.owner, OnboardingRead.CLUB_PROFILE_GET, profile.id("id")) }
            fixture.accept(fixture.bob)
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, profile.id("id")) }
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$WRITE_PROFILE'")
            val read = fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, profile.id("id"))
            assertEquals(JsonArray(emptyList()), read["availableActions"])
            expect("not_found") { update(profile) }
            fixture.execute("UPDATE client_club_grants SET is_active=TRUE WHERE permission_code='$WRITE_PROFILE'")
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$READ_CLUB'")
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, profile.id("id")) }
            expect("not_found") { update(profile) }
            fixture.execute("UPDATE client_club_grants SET is_active=TRUE")
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE")
            assertEquals("2", update(profile).string("version"))
        }

    @Test
    fun `profile save preserves approval and rejects conflicts invalid values and authority payload`() =
        runBlocking<Unit> {
            val approved = approved()
            val profile = create(approved)
            val immutable = fixture.rows("SELECT * FROM client_club_applications")
            fixture.clock.current += Duration.ofMinutes(2)
            val saved = update(profile, key = "profile-lost-response")
            assertEquals("Saved synthetic name", saved.string("name"))
            assertEquals("Synthetic description", saved.string("description"))
            assertEquals("Europe/Paris", saved.string("timezone"))
            assertEquals("2", saved.string("version"))
            assertEquals(fixture.clock.instant().toString(), saved.string("savedAt"))
            assertEquals(saved, update(profile, key = "profile-lost-response"))
            expect("version_conflict") { update(profile) }
            listOf(
                buildJsonObject { put("name", "") },
                buildJsonObject { put("description", "x".repeat(2001)) },
                buildJsonObject { put("timezone", "UTC+03:00") },
                buildJsonObject { put("countryCode", "US") },
                buildJsonObject { put("city", "Forged") },
                buildJsonObject { put("address", "Forged") },
                buildJsonObject { put("isActive", true) },
                buildJsonObject { put("ownerId", "1") },
                buildJsonObject { put("clientId", "1") },
                buildJsonObject { put("permissions", JsonArray(listOf(JsonPrimitive(WRITE_PROFILE)))) },
            ).forEach { invalid ->
                expect(
                    "validation_error",
                ) { fixture.mutate(fixture.alice, OnboardingMutation.CLUB_PROFILE_UPDATE, saved, invalid) }
            }
            assertEquals(immutable, fixture.rows("SELECT * FROM client_club_applications"))
            assertFalse(fixture.rows("SELECT * FROM clubs WHERE id=${profile.id("id")}").single().active())
        }

    @Test
    fun `profile audit and command failures roll back data version and save timestamp`() =
        runBlocking<Unit> {
            val profile = create(approved())
            val before = state()
            listOf(
                "UPDATE clubs SET name=",
                "UPDATE client_club_onboarding SET profile_version=",
                "INSERT INTO audit_log",
                "INSERT INTO client_commands",
            ).forEachIndexed { index, prefix ->
                try {
                    fixture.failingAfterRepository(prefix).mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_PROFILE_UPDATE,
                        profile.id("id"),
                        JsonObject(version(profile) + profileFields()),
                        "profile-fault-$index",
                    )
                    error("Expected synthetic failure after $prefix")
                } catch (_: SQLException) {
                    assertEquals(before, state())
                }
            }
        }

    @Test
    fun `concurrent profile writes use version and replay requires current write grant`() =
        runBlocking<Unit> {
            val profile = create(approved())
            assertEquals(
                listOf("ok", "version_conflict"),
                race(
                    { update(profile, key = "profile-race-first") },
                    { update(profile, key = "profile-race-second") },
                ).sorted(),
            )
            val saved = fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, profile.id("id"))
            val repeated = update(saved, key = "profile-replay-current")
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$WRITE_PROFILE'")
            expect("not_found") { update(saved, key = "profile-replay-current") }
            assertEquals(
                repeated["version"],
                fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, profile.id("id"))["version"],
            )
        }

    @Test
    fun `fresh repository resumes same saved club and second club needs independent approval`() =
        runBlocking<Unit> {
            val approved = approved()
            val first = update(create(approved))
            val fresh = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(first, fresh.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, first.id("id")))
            val context = fresh.read(fixture.alice, OnboardingRead.CLIENT_ONBOARDING, first.id("clientId"))
            assertEquals(listOf(first["id"]), context.getValue("clubs").jsonArray.map { it.jsonObject["id"] })
            assertEquals(first["id"], create(approved)["id"])
            val secondApproval = fixture.approved(first.id("clientId"), venue = "Second approved venue")
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.CLIENT_LIMIT_UPDATE,
                first.id("clientId"),
                buildJsonObject {
                    put("allowedClubs", 2)
                    put("expectedVersion", "1")
                },
                "explicit-second-club-limit",
            )
            val second = create(secondApproval)
            assertTrue(second["id"] != first["id"])
            assertEquals(2, fixture.count("client_club_onboarding"))
            assertEquals(
                2,
                fresh
                    .read(
                        fixture.alice,
                        OnboardingRead.CLIENT_ONBOARDING,
                        first.id("clientId"),
                    ).getValue("clubs")
                    .jsonArray.size,
            )
        }

    @Test
    fun `restore chooses exact prior read and profile write scopes independently`() =
        runBlocking<Unit> {
            val approved = approved()
            val profile = create(approved)
            val revoked = revoke(approved)
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_RESTORE,
                approved.id("clientId"),
                buildJsonObject {
                    put("userId", approved.getValue("authorUserId"))
                    put("expectedVersion", revoked.getValue("version"))
                    put("permissions", JsonArray(emptyList()))
                    put(
                        "clubGrants",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("clubId", profile.getValue("id"))
                                    put("permission", READ_CLUB)
                                },
                            ),
                        ),
                    )
                },
                "restore-read-only",
            )
            assertEquals(
                JsonArray(emptyList()),
                fixture.repository.read(
                    fixture.alice,
                    OnboardingRead.CLUB_PROFILE_GET,
                    profile.id("id"),
                )["availableActions"],
            )
            expect("not_found") { update(profile) }
            expect("not_found") { create(approved) }
        }
}
