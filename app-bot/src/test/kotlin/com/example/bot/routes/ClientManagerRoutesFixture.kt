package com.example.bot.routes

import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class ClientManagerRoutesFixture : ClientOnboardingRoutesTestBase(legacyClients = false) {
    protected fun revokeSource(
        roster: JsonObject,
        row: JsonObject,
        action: String,
    ) = JsonObject(
        obj(
            "action" to action,
            "expectedVersion" to roster.string("version"),
            "assignmentId" to row.string("id"),
            "expectedAssignmentVersion" to row.string("version"),
            "reason" to "No further duties",
        ) + ("confirmed" to JsonPrimitive(true)),
    )

    protected suspend fun ApplicationTestBuilder.business(
        source: DataSource,
        confirmed: Boolean = true,
    ): Pair<String, String> {
        val invited =
            ok(
                "$P/invitations",
                OWNER,
                JsonObject(
                    obj("clientName" to "Verified business", "recipientLabel" to "Owner") +
                        ("allowedClubs" to JsonPrimitive(2)),
                ),
            )
        ok(
            "$C/invitations/${invited.string("id")}/claim",
            RECIPIENT,
            obj(
                "token" to invited.string("token"),
                "expectedVersion" to "1",
            ),
        )
        val business = invited.string("clientId")
        val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$RECIPIENT")
        if (confirmed) ok("$P/clients/$business/members/$user/business-owner", OWNER, obj("expectedVersion" to "1"))

        suspend fun create(name: String) =
            ok(
                "$C/clients/$business/clubs",
                RECIPIENT,
                obj("venueName" to name, "countryCode" to "RU", "cityId" to "524901", "address" to "Synthetic address"),
            ).string("id")
        return create("First") to create("Second")
    }

    protected suspend fun ApplicationTestBuilder.invite(
        path: String,
        issuer: Long,
        role: String,
    ): JsonObject {
        val body =
            obj(
                "action" to "invite",
                "expectedVersion" to ok(path, issuer).string("version"),
                "role" to role,
                "recipientLabel" to "Synthetic manager",
            )
        val key = UUID.randomUUID().toString()
        val issued = ok(path, issuer, body, key = key)
        assertTrue(issued.string("invitationLink").contains("?startapp=cm_"))
        assertFalse(ok(path, issuer, body, key = key).containsKey("token"))
        return issued
    }

    protected suspend fun ApplicationTestBuilder.claim(
        issued: JsonObject,
        candidate: Long,
    ): JsonObject {
        val resolved = ok("$C/manager-invitations/resolve", candidate, obj("token" to issued.string("token")))
        assertEquals("manager", resolved.string("acceptanceMode"))
        return ok(
            "$C/manager-invitations/${resolved.string("id")}/claim",
            candidate,
            obj("token" to issued.string("token"), "expectedVersion" to resolved.string("version")),
        )
    }

    protected suspend fun ApplicationTestBuilder.confirmBody(
        path: String,
        issuer: Long,
        claimed: JsonObject,
    ): JsonObject =
        JsonObject(
            obj(
                "action" to "confirm",
                "expectedVersion" to ok(path, issuer).string("version"),
                "invitationId" to claimed.string("id"),
                "expectedInvitationVersion" to claimed.string("version"),
                "verificationCode" to claimed.string("verificationCode"),
            ) +
                ("confirmed" to JsonPrimitive(true)),
        )

    protected suspend fun ApplicationTestBuilder.confirm(
        path: String,
        issuer: Long,
        claimed: JsonObject,
    ): JsonObject = ok(path, issuer, confirmBody(path, issuer, claimed))

    protected fun profileChange(profile: JsonObject) =
        obj(
            "name" to "Manager saved",
            "description" to "Synthetic",
            "timezone" to profile.string("timezone"),
            "expectedVersion" to profile.string("version"),
        )

    protected companion object {
        const val SECOND_MANAGER = 998105L
        const val THIRD_MANAGER = 998106L
    }
}
