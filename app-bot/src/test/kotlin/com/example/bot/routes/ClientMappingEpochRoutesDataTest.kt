package com.example.bot.routes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Existing mounted onboarding endpoints; only metadata epochs are new, never response fields or permissions. */
open class ClientMappingEpochRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test fun `direct clubs bump epoch once while replay and limits keep it stable`() =
        database { source ->
            withApp(source) {
                val invitation =
                    ok(
                        "$P/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Epoch direct",
                            "recipientLabel" to "Synthetic",
                        ),
                    )
                val client = invitation.string("clientId")

                fun epoch() = source.scalar("SELECT target_mapping_epoch FROM clients WHERE id=$client").toLong()
                assertEquals(1L, epoch())
                ok(
                    "$C/invitations/${invitation.string("id")}/claim",
                    RECIPIENT,
                    obj(
                        "token" to invitation.string("token"),
                        "expectedVersion" to invitation.string("version"),
                    ),
                )
                assertEquals(2L, epoch())
                val body =
                    obj(
                        "venueName" to "First epoch club",
                        "countryCode" to "RU",
                        "cityId" to "524901",
                        "address" to "Synthetic address 1",
                    )
                val first = ok("$C/clients/$client/clubs", RECIPIENT, body, key = "epoch-create-first")
                assertEquals(3L, epoch())
                assertFalse(first.containsKey("target_mapping_epoch"))
                assertEquals(first, ok("$C/clients/$client/clubs", RECIPIENT, body, key = "epoch-create-first"))
                assertEquals(3L, epoch())
                val current = ok("$P/clients/$client", OWNER)
                ok(
                    "$P/clients/$client/limit",
                    OWNER,
                    JsonObject(
                        obj("expectedVersion" to current.string("limitVersion")) + ("allowedClubs" to JsonPrimitive(2)),
                    ),
                )
                assertEquals(3L, epoch())
                ok(
                    "$C/clients/$client/clubs",
                    RECIPIENT,
                    JsonObject(
                        body + obj("venueName" to "Second epoch club", "address" to "Synthetic address 2"),
                    ),
                )
                assertEquals(4L, epoch())
                assertEquals(
                    "4",
                    source.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=$client"),
                )
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM target_bot_connections"))
            }
        }

    @Test fun `historical application creates private club without TARGET state`() =
        database { source ->
            withApp(source) {
                val invitation =
                    ok(
                        "$P/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Epoch approved",
                            "recipientLabel" to "Synthetic",
                        ),
                    )
                val client = invitation.string("clientId")
                val invitationId = invitation.string("id")
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "UPDATE client_invitations SET acceptance_policy='owner_confirmation' " +
                                "WHERE id=$invitationId",
                        )
                    }
                }
                val claimed =
                    ok(
                        "$C/invitations/$invitationId/claim",
                        RECIPIENT,
                        obj(
                            "token" to invitation.string("token"),
                            "expectedVersion" to invitation.string("version"),
                        ),
                    )
                ok("$P/invitations/$invitationId/confirm", OWNER, version(claimed))
                val draft = ok("$C/clients/$client/applications", RECIPIENT, venue())
                val application = draft.string("id")
                val submitted = ok("$C/applications/$application/submit", RECIPIENT, version(draft))
                val reviewed = ok("$P/applications/$application/review", OWNER, version(submitted))
                val approval =
                    JsonObject(
                        version(reviewed) +
                            obj("commercialReference" to "SYNTHETIC-EPOCH", "commercialAgreedAt" to "2026-01-01") +
                            ("platformConfirmed" to JsonPrimitive(true)),
                    )
                val approved = ok("$P/applications/$application/approve", OWNER, approval)
                val before = source.scalar("SELECT target_mapping_epoch FROM clients WHERE id=$client").toLong()
                val club =
                    ok("$C/applications/$application/club", RECIPIENT, version(approved), key = "epoch-approved-create")
                assertEquals(
                    before + 1,
                    source.scalar("SELECT target_mapping_epoch FROM clients WHERE id=$client").toLong(),
                )
                assertEquals(
                    club,
                    ok(
                        "$C/applications/$application/club",
                        RECIPIENT,
                        version(approved),
                        key = "epoch-approved-create",
                    ),
                )
                assertEquals(
                    before + 1,
                    source.scalar("SELECT target_mapping_epoch FROM clients WHERE id=$client").toLong(),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM clubs WHERE id=${club.string("id")} AND is_active=FALSE"),
                )
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM target_bot_bindings"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_role_assignments"))
            }
        }
}
