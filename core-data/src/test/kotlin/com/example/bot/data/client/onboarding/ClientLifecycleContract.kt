package com.example.bot.data.client.onboarding

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.JdbcClientContextRepository
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

/** These lifecycle assertions execute against both H2 and disposable PostgreSQL 16. */
internal abstract class ClientLifecycleContract : OnboardingFoundationContract() {
    @Test
    fun `archive is owner only reversible versioned idempotent and never deletes history`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, issued)
            val clientId = issued.id("clientId")
            val before = detail(clientId)
            val users = fixture.rows("SELECT * FROM users ORDER BY id")
            val invitations = fixture.rows("SELECT * FROM client_invitations ORDER BY id")
            expect("forbidden") { lifecycle(before, OnboardingMutation.CLIENT_ARCHIVE, actor = fixture.alice) }
            expect("forbidden") {
                fixture.repository.mutate(
                    fixture.owner.copy(connectionKey = "client:1"),
                    OnboardingMutation.CLIENT_ARCHIVE,
                    clientId,
                    buildJsonObject { put("expectedVersion", before.getValue("version")) },
                    "archive-wrong-connection",
                )
            }
            val archived = lifecycle(before, OnboardingMutation.CLIENT_ARCHIVE, key = "archive-lost-response")
            assertEquals(JsonPrimitive(true), archived["archived"])
            assertEquals("2", archived.string("version"))
            assertEquals(users, fixture.rows("SELECT * FROM users ORDER BY id"))
            assertEquals(invitations, fixture.rows("SELECT * FROM client_invitations ORDER BY id"))
            assertTrue(search().getValue("items").jsonArray.isEmpty())
            assertEquals(
                clientId,
                search("archived")
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .id("id"),
            )
            assertEquals(1, search("all").getValue("items").jsonArray.size)
            val audits = fixture.count("audit_log")
            assertEquals(archived, lifecycle(before, OnboardingMutation.CLIENT_ARCHIVE, key = "archive-lost-response"))
            assertEquals(audits, fixture.count("audit_log"))
            expect("idempotency_conflict") {
                lifecycle(before, OnboardingMutation.CLIENT_RESTORE, key = "archive-lost-response")
            }
            expect("version_conflict") { lifecycle(before, OnboardingMutation.CLIENT_RESTORE) }
            val restored = lifecycle(archived, OnboardingMutation.CLIENT_RESTORE)
            assertEquals(JsonPrimitive(false), restored["archived"])
            assertEquals("3", restored.string("version"))
            assertEquals(before["active"], restored["active"])
            assertEquals(1, search().getValue("items").jsonArray.size)
            assertEquals(1, fixture.rows("SELECT id FROM audit_log WHERE action='client.archive'").size)
            assertEquals(1, fixture.rows("SELECT id FROM audit_log WHERE action='client.restore'").size)
        }

    @Test
    fun `active invitations memberships grants and every mapped club independently prevent archive`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            val clientId = issued.id("clientId")
            expect("client_not_empty") { lifecycle(detail(clientId), OnboardingMutation.CLIENT_ARCHIVE) }
            fixture.claim(issued)
            expect("client_not_empty") { lifecycle(detail(clientId), OnboardingMutation.CLIENT_ARCHIVE) }
            val member =
                detail(clientId)
                    .getValue("members")
                    .jsonArray
                    .single()
                    .jsonObject
            fixture.execute("UPDATE client_memberships SET is_active=FALSE WHERE client_id=$clientId")
            assertTrue(detail(clientId).getValue("archiveBlockers").jsonArray.contains(JsonPrimitive("active_grants")))
            expect("client_not_empty") { lifecycle(detail(clientId), OnboardingMutation.CLIENT_ARCHIVE) }
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE WHERE client_id=$clientId")
            fixture.execute(
                "INSERT INTO clubs(id,name,timezone,is_active) VALUES (66001,'Synthetic empty','UTC',FALSE)",
            )
            fixture.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,66001)")
            fixture.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "($clientId,${member.id("userId")},66001,'$READ_CLUB',FALSE)",
            )
            assertEquals(
                listOf(JsonPrimitive("clubs")),
                detail(clientId).getValue("archiveBlockers").jsonArray.toList(),
            )
            expect("client_not_empty") { lifecycle(detail(clientId), OnboardingMutation.CLIENT_ARCHIVE) }
            assertEquals(1, fixture.count("client_clubs"))
            assertFalse(fixture.rows("SELECT * FROM clients").single()["archived_at"] != null)
        }

    @Test
    fun `archived client refuses old invitation resolution claim reissue and confirmation`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val claimed = fixture.claim(issued)
            fixture.clock.current = fixture.clock.current.plus(Duration.ofDays(8))
            val archived = lifecycle(detail(issued.id("clientId")), OnboardingMutation.CLIENT_ARCHIVE)
            expect("not_found") {
                fixture.repository.read(
                    fixture.alice,
                    OnboardingRead.INVITATION_RESOLVE,
                    body = buildJsonObject { put("token", issued.getValue("token")) },
                )
            }
            expect("not_found") { fixture.claim(issued) }
            expect("not_found") { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, claimed) }
            expect("not_found") { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REISSUE, claimed) }
            val historical =
                fixture.repository.read(
                    fixture.owner,
                    OnboardingRead.PLATFORM_INVITATION_GET,
                    issued.id("id"),
                )
            assertTrue(historical.getValue("availableActions").jsonArray.isEmpty())
            lifecycle(archived, OnboardingMutation.CLIENT_RESTORE)
            expect("not_found") { fixture.claim(issued) }
            assertEquals(0, fixture.count("client_memberships"))
        }

    @Test
    fun `restore never revives revoked scopes or client context`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            val member =
                detail(clientId)
                    .getValue("members")
                    .jsonArray
                    .single()
                    .jsonObject
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_REVOKE,
                clientId,
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", member.getValue("version"))
                },
                "revoke-before-archive",
            )
            val archived = lifecycle(detail(clientId), OnboardingMutation.CLIENT_ARCHIVE)
            val restoreMember =
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(listOf(JsonPrimitive(MANAGE))))
                    put("clubGrants", JsonArray(emptyList()))
                }
            expect("not_found") {
                fixture.repository.mutate(
                    fixture.owner,
                    OnboardingMutation.MEMBER_RESTORE,
                    clientId,
                    restoreMember,
                    "restore-while-archived",
                )
            }
            val restored = lifecycle(archived, OnboardingMutation.CLIENT_RESTORE)
            assertEquals(JsonPrimitive(0L), restored["activeMembers"])
            assertTrue(fixture.rows("SELECT * FROM client_memberships").none { it.active() })
            assertTrue(fixture.rows("SELECT * FROM client_membership_grants").none { it.active() })
            assertTrue(
                JdbcClientContextRepository(
                    fixture.source,
                ).context(fixture.alice.telegramUserId, ClientAudience.Platform).isEmpty(),
            )
            expect("not_found") { fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_ONBOARDING, clientId) }
        }

    @Test
    fun `archive and invitation reissue serialize and cannot leave active invitation in archive`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            val revoked = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, issued)
            val client = detail(issued.id("clientId"))
            val result =
                race(
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE) },
                    { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REISSUE, revoked) },
                )
            assertTrue(
                result == listOf("ok", "not_found") || result == listOf("client_not_empty", "ok"),
                result.toString(),
            )
            val archived = detail(issued.id("clientId"))["archived"] == JsonPrimitive(true)
            val pending = fixture.rows("SELECT id FROM client_invitations WHERE status='issued'").size
            assertEquals(if (archived) 0 else 1, pending)
        }

    @Test
    fun `claim and archive race cannot activate an archived client`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            val clientId = issued.id("clientId")
            val client = detail(clientId)
            val result =
                race(
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE) },
                    { fixture.claim(issued) },
                )
            assertEquals(listOf("client_not_empty", "ok"), result)
            assertEquals(JsonPrimitive(false), detail(clientId)["archived"])
            assertEquals(1, fixture.count("client_memberships"))
        }

    @Test
    fun `member restore and archive race cannot leave restored authority in archive`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            val member =
                detail(clientId)
                    .getValue("members")
                    .jsonArray
                    .single()
                    .jsonObject
            val revokeBody =
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", member.getValue("version"))
                }
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_REVOKE,
                clientId,
                revokeBody,
                "race-initial-revoke",
            )
            val restoreBody =
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(listOf(JsonPrimitive(MANAGE))))
                    put("clubGrants", JsonArray(emptyList()))
                }
            val client = detail(clientId)
            val result =
                race(
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE) },
                    {
                        fixture.repository.mutate(
                            fixture.owner,
                            OnboardingMutation.MEMBER_RESTORE,
                            clientId,
                            restoreBody,
                            "race-member-restore",
                        )
                    },
                )
            assertTrue(
                result == listOf("ok", "not_found") || result == listOf("client_not_empty", "ok"),
                result.toString(),
            )
            val archived = detail(clientId)["archived"] == JsonPrimitive(true)
            assertEquals(!archived, fixture.rows("SELECT * FROM client_memberships").single().active())
        }

    @Test
    fun `club creation and archive race keeps exact club mapping and rejects archive of live client`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            val client = detail(clientId)
            val result =
                race(
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE) },
                    {
                        fixture.repository.mutate(
                            fixture.alice,
                            OnboardingMutation.CLUB_CREATE,
                            clientId,
                            buildJsonObject {
                                put("venueName", "Race venue")
                                put("countryCode", "RU")
                                put("cityId", "524901")
                                put("city", "Moscow")
                                put("region", "Moscow")
                                put("timezone", "Europe/Moscow")
                                put("address", "Synthetic address")
                            },
                            "race-archive-club-create",
                        )
                    },
                )
            assertEquals(listOf("client_not_empty", "ok"), result)
            assertEquals(1, fixture.count("client_clubs"))
            assertEquals(JsonPrimitive(false), detail(clientId)["archived"])
        }

    @Test
    fun `racing archive commands require a new version and audit exactly once`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, issued)
            val client = detail(issued.id("clientId"))
            assertEquals(
                listOf("ok", "version_conflict"),
                race(
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE, key = "archive-race-command-a") },
                    { lifecycle(client, OnboardingMutation.CLIENT_ARCHIVE, key = "archive-race-command-b") },
                ).sorted(),
            )
            assertEquals(1, fixture.rows("SELECT id FROM audit_log WHERE action='client.archive'").size)
        }

    @Test
    fun `archive audit failure rolls back status version and durable command`() =
        runBlocking<Unit> {
            val issued = fixture.issueDirect()
            fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, issued)
            val client = detail(issued.id("clientId"))
            val before = fixture.rows("SELECT * FROM clients")
            val commands = fixture.count("client_commands")
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.owner,
                    OnboardingMutation.CLIENT_ARCHIVE,
                    issued.id("clientId"),
                    buildJsonObject { put("expectedVersion", client.getValue("version")) },
                    "archive-audit-failure",
                )
                error("Synthetic audit failure expected")
            } catch (_: SQLException) {
                assertEquals(before, fixture.rows("SELECT * FROM clients"))
                assertEquals(commands, fixture.count("client_commands"))
            }
        }

    @Test
    fun `platform club search and summary use mappings real publication and exact owner`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            fixture.execute(
                "INSERT INTO clubs(id,name,timezone,is_active) VALUES (66010,'Mapped','UTC',FALSE),(66011,'Legacy','UTC',TRUE)",
            )
            fixture.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,66010)")
            expect("forbidden") { fixture.repository.read(fixture.alice, OnboardingRead.PLATFORM_CLUB_SEARCH) }
            expect("forbidden") { fixture.repository.read(fixture.alice, OnboardingRead.PLATFORM_SUMMARY) }
            val clubs = fixture.repository.read(fixture.owner, OnboardingRead.PLATFORM_CLUB_SEARCH)
            val club =
                clubs
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("66010", club.string("id"))
            assertEquals(clientId.toString(), club.string("clientId"))
            assertEquals(JsonPrimitive(false), club["published"])
            val summary = fixture.repository.read(fixture.owner, OnboardingRead.PLATFORM_SUMMARY)
            assertEquals(JsonPrimitive(1L), summary["clients"])
            assertEquals(JsonPrimitive(1L), summary["clubs"])
            assertEquals(JsonPrimitive(1L), summary["activeMembers"])
        }

    @Test
    fun `archive migration preserves all existing state and defaults to unarchived`() {
        newFixture("65").use { previous ->
            previous.execute("INSERT INTO clients(id,display_name,is_active) VALUES (66020,'Historical',TRUE)")
            val before = previous.rows("SELECT id,display_name,is_active,created_at,updated_at FROM clients")
            previous.migrate()
            assertEquals(before, previous.rows("SELECT id,display_name,is_active,created_at,updated_at FROM clients"))
            val client = previous.rows("SELECT archived_at,lifecycle_version FROM clients").single()
            assertEquals(null, client["archived_at"])
            assertEquals("1", client["lifecycle_version"])
            previous.migrate()
            assertEquals(1, previous.count("clients"))
        }
    }

    private suspend fun detail(clientId: Long) =
        fixture.repository.read(fixture.owner, OnboardingRead.PLATFORM_CLIENT_GET, clientId)

    private suspend fun search(status: String? = null) =
        fixture.repository.read(
            fixture.owner,
            OnboardingRead.PLATFORM_CLIENT_SEARCH,
            body = buildJsonObject { if (status != null) put("status", status) },
        )

    private suspend fun lifecycle(
        client: JsonObject,
        operation: OnboardingMutation,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        actor: OnboardingActor = fixture.owner,
    ) = fixture.repository.mutate(
        actor,
        operation,
        client.id("id"),
        buildJsonObject { put("expectedVersion", client.getValue("version")) },
        key,
    )
}
