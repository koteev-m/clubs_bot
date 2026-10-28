package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Runs unchanged against H2 and PostgreSQL 16, with distinct connections for racing commands. */
internal abstract class ClubOnboardingTestBase : OnboardingFoundationContract() {
    protected suspend fun approved(): JsonObject = fixture.approved(fixture.accept().id("clientId"))

    protected suspend fun create(
        application: JsonObject,
        actor: OnboardingActor = fixture.alice,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ): JsonObject = fixture.mutate(actor, OnboardingMutation.APPLICATION_CREATE_CLUB, application, key = key)

    protected suspend fun update(
        profile: JsonObject,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ): JsonObject = fixture.mutate(fixture.alice, OnboardingMutation.CLUB_PROFILE_UPDATE, profile, profileFields(), key)

    protected suspend fun revoke(application: JsonObject): JsonObject =
        fixture.repository.mutate(
            fixture.owner,
            OnboardingMutation.MEMBER_REVOKE,
            application.id("clientId"),
            buildJsonObject {
                put("userId", application.getValue("authorUserId"))
                put("expectedVersion", "1")
            },
            "revoke-club-owner",
        )

    protected fun version(resource: JsonObject) =
        buildJsonObject { put("expectedVersion", resource.getValue("version")) }

    protected fun reason() = buildJsonObject { put("reason", "Synthetic cancellation") }

    protected fun profileFields() =
        buildJsonObject {
            put("name", "Saved synthetic name")
            put("description", "Synthetic description")
            put("timezone", "Europe/Paris")
        }

    protected fun state(): Map<String, List<DbRow>> =
        listOf(
            "clubs",
            "client_clubs",
            "client_club_onboarding",
            "client_club_grants",
            "client_club_applications",
            "client_commands",
            "audit_log",
        ).associateWith {
            fixture.rows("SELECT * FROM $it ORDER BY 1,2")
        }
}
