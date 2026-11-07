package com.example.bot.data.client.onboarding

import com.example.bot.data.finance.RevenueEntryInput
import com.example.bot.data.finance.ShiftReportBraceletInput
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.data.finance.ShiftReportUpdatePayload
import com.example.bot.data.finance.nonTotalIndicators
import com.example.bot.data.finance.totalRevenue
import com.example.bot.data.finance.totalsPerGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Canonical storage, authority, migration and racing commands run unchanged on H2 and PostgreSQL. */
internal abstract class ClubFinanceContract : ClubZoneContract() {
    @Test
    fun `finance preserves canonical identities and persists settings order flags and disabled history`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            var state = finance(club)
            assertEquals("1", state.string("version"))
            assertEquals(0, fixture.count("club_report_templates"))
            state = change(club, state, fields("bracelet", "Бесплатный"))
            val first = state.items("bracelets").single().id("id")
            state = change(club, state, fields("bracelet", "VIP"))
            val second = state.items("bracelets").last().id("id")
            state = change(club, state, fields("group", "Основная выручка"))
            val group = state.items("revenueGroups").single().id("id")
            val article =
                JsonObject(
                    fields("article", "Бар") +
                        buildJsonObject {
                            put("groupId", group.toString())
                            put("includeInTotal", true)
                            put("showSeparately", false)
                        },
                )
            state = change(club, state, article)
            val articleId = state.items("revenueArticles").single().id("id")
            expect("finance_group_in_use") { change(club, state, action("group", "disable", group)) }
            state = change(club, state, JsonObject(fields("bracelet", "ВИП") + action("bracelet", "update", second)))
            state =
                change(
                    club,
                    state,
                    buildJsonObject {
                        put("entity", "bracelet")
                        put("action", "reorder")
                        put("ids", JsonArray(listOf(second, first).map { JsonPrimitive(it.toString()) }))
                    },
                )
            state = change(club, state, action("article", "disable", articleId))
            state = change(club, state, action("group", "disable", group))
            state = change(club, state, action("bracelet", "disable", first))
            assertEquals(listOf(second, first), state.items("bracelets").map { it.id("id") })
            assertFalse(
                state
                    .items("bracelets")
                    .last()
                    .getValue("enabled")
                    .toString()
                    .toBoolean(),
            )
            assertEquals(2, fixture.count("club_bracelet_types"))
            assertEquals(1, fixture.count("club_revenue_articles"))
            assertEquals(
                state,
                JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId)
                    .read(fixture.alice, OnboardingRead.CLUB_FINANCE_GET, club),
            )
            assertEquals(0, fixture.count("shift_reports"))
            assertEquals(0, fixture.count("table_deposits"))
        }

    @Test
    fun `finance enforces exact read write tenant and related group boundaries before replay`() =
        runBlocking<Unit> {
            val approved = approved()
            val club = create(approved).id("id")
            val other = fixture.accept(fixture.bob)
            val otherClub =
                create(
                    fixture.approved(other.id("clientId"), fixture.bob, "Other venue"),
                    fixture.bob,
                ).id("id")
            val foreignGroup =
                fixture.repository
                    .mutate(
                        fixture.bob,
                        OnboardingMutation.CLUB_FINANCE_UPDATE,
                        otherClub,
                        JsonObject(fields("group", "Foreign group") + ("expectedVersion" to JsonPrimitive("1"))),
                        "foreign-finance-group",
                    ).items("revenueGroups")
                    .single()
                    .id("id")
            val state = finance(club)
            expect("not_found") { finance(club, fixture.bob) }
            expect("not_found") { finance(club, fixture.owner) }
            val body = fields("bracelet", "Сохранённый")
            change(club, state, body, "finance-accepted-retry")
            fixture.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club " +
                    "AND permission_code='$WRITE_FINANCE'",
            )
            assertFalse(finance(club).getValue("canWrite").toString().toBoolean())
            expect("not_found") { change(club, state, body, "finance-accepted-retry") }
            fixture.execute(
                "UPDATE client_club_grants SET is_active=TRUE WHERE club_id=$club AND permission_code='$WRITE_FINANCE'",
            )
            fixture.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND permission_code='$READ_FINANCE'",
            )
            expect("not_found") { finance(club) }
            expect("not_found") { change(club, state, body, "finance-accepted-retry") }
            fixture.execute(
                "UPDATE client_club_grants SET is_active=TRUE WHERE club_id=$club AND permission_code='$READ_FINANCE'",
            )
            val current = finance(club)
            expect("not_found") {
                change(
                    club,
                    current,
                    JsonObject(
                        fields("article", "Foreign") +
                            buildJsonObject {
                                put("groupId", foreignGroup.toString())
                                put("includeInTotal", true)
                                put("showSeparately", false)
                            },
                    ),
                )
            }
            revoke(approved)
            expect("not_found") { finance(club) }
            expect("not_found") { change(club, state, body, "finance-accepted-retry") }
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_RESTORE,
                approved.id("clientId"),
                buildJsonObject {
                    put("userId", approved.getValue("authorUserId"))
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(emptyList()))
                    put(
                        "clubGrants",
                        JsonArray(
                            listOf(READ_CLUB, READ_FINANCE).map { permission ->
                                buildJsonObject {
                                    put("clubId", club.toString())
                                    put("permission", permission)
                                }
                            },
                        ),
                    )
                },
                "restore-finance-read-only",
            )
            assertFalse(finance(club).getValue("canWrite").toString().toBoolean())
            expect("not_found") { change(club, state, body, "finance-accepted-retry") }
            assertEquals(1, fixture.rows("SELECT id FROM club_bracelet_types WHERE club_id=$club").size)
        }

    @Test
    fun `finance concurrent commands replay exactly once and stale versions never mutate`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val before = finance(club)
            val body = fields("bracelet", "Единый")
            val pair =
                coroutineScope {
                    listOf(
                        async { change(club, before, body, "finance-race-same") },
                        async { change(club, before, body, "finance-race-same") },
                    ).awaitAll()
                }
            assertEquals(pair[0], pair[1])
            assertEquals(1, fixture.rows("SELECT id FROM club_bracelet_types WHERE club_id=$club").size)
            expect("idempotency_conflict") { change(club, before, fields("bracelet", "Иной"), "finance-race-same") }
            expect("version_conflict") { change(club, before, body) }
            fixture.accept(fixture.bob)
            val secondUser =
                fixture
                    .rows(
                        "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                    ).single()
                    .long("id")
            val client = before.id("clientId")
            fixture.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($client,$secondUser,TRUE)",
            )
            listOf(READ_CLUB, READ_FINANCE, WRITE_FINANCE).forEach { permission ->
                fixture.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                        "VALUES ($client,$secondUser,$club,'$permission',TRUE)",
                )
            }
            val now = finance(club)
            assertEquals(now, finance(club, fixture.bob))
            val outcomes =
                coroutineScope {
                    (1..2)
                        .map { n ->
                            async {
                                try {
                                    change(
                                        club,
                                        now,
                                        fields("group", "Group $n"),
                                        actor = if (n == 1) fixture.alice else fixture.bob,
                                    )
                                    "ok"
                                } catch (
                                    failure: OnboardingFailure,
                                ) {
                                    failure.code
                                }
                            }
                        }.awaitAll()
                }
            assertEquals(listOf("ok", "version_conflict"), outcomes.sorted())
            assertEquals(2, fixture.rows("SELECT id FROM audit_log WHERE action LIKE 'client.club.finance.%'").size)
            assertEquals(0, fixture.count("shift_reports"))
        }

    @Test
    fun `finance rolls back config when durable receipt write fails`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val state = finance(club)
            val failing = fixture.failingAfterRepository("INSERT INTO client_commands")
            try {
                failing.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_FINANCE_UPDATE,
                    club,
                    JsonObject(fields("bracelet", "Rollback") + ("expectedVersion" to state.getValue("version"))),
                    "finance-rollback-command",
                )
                error("Expected failure")
            } catch (_: java.sql.SQLException) {
                // Injected after successful SQL, before transaction commit.
            }
            assertEquals(state, finance(club))
            assertEquals(0, fixture.count("club_bracelet_types"))
            assertEquals(0, fixture.rows("SELECT id FROM audit_log WHERE action LIKE 'client.club.finance.%'").size)
        }

    @Test
    fun `configuration migration grants only unique active creators and never revives permission tombstones`() {
        newFixture("73").use { old ->
            for (id in 73001L..73006L) {
                old.execute("INSERT INTO users(id,telegram_user_id) VALUES ($id,${880000000L + id})")
                old.execute("INSERT INTO clients(id,display_name,is_active) VALUES ($id,'Old client',TRUE)")
                old.execute(
                    "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($id,$id,${id != 73002L})",
                )
                old.execute(
                    "INSERT INTO clubs(id,name,timezone,is_active) " +
                        "VALUES ($id,'Old club','Europe/Moscow',${id == 73003L})",
                )
                old.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($id,$id)")
                old.execute(
                    "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,venue_fingerprint," +
                        "country_code,city,address,legacy_fingerprint) VALUES ($id,$id,$id,'${digest("owner-$id")}'," +
                        "'RU','City','Address','${digest("legacy-$id")}')",
                )
                listOf(
                    READ_CLUB,
                    WRITE_PROFILE,
                    WRITE_LAYOUT,
                    WRITE_CALENDAR,
                    WRITE_BOOKING_RULES,
                ).forEach { permission ->
                    old.execute(
                        "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                            "VALUES ($id,$id,$id,'$permission',${!(id == 73004L && permission == READ_CLUB)})",
                    )
                }
            }
            // An active member with identical old permissions is not the recorded creator.
            old.execute("INSERT INTO users(id,telegram_user_id) VALUES (73007,880073007)")
            old.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (73001,73007,TRUE)")
            listOf(
                READ_CLUB,
                WRITE_PROFILE,
                WRITE_LAYOUT,
                WRITE_CALENDAR,
                WRITE_BOOKING_RULES,
            ).forEach { permission ->
                old.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                        "VALUES (73001,73007,73001,'$permission',TRUE)",
                )
            }
            old.execute("UPDATE clients SET archived_at=CURRENT_TIMESTAMP,is_active=FALSE WHERE id=73005")
            val legacy = old.rows("SELECT * FROM user_roles ORDER BY id")
            old.migrate()
            assertEquals(
                listOf(73001L, 73006L),
                old
                    .rows(
                        "SELECT user_id FROM client_club_grants " +
                            "WHERE permission_code='$READ_FINANCE' ORDER BY user_id",
                    ).map { it.long("user_id") },
            )
            assertEquals(legacy, old.rows("SELECT * FROM user_roles ORDER BY id"))
            old.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=73001 " +
                    "AND permission_code='$WRITE_FINANCE'",
            )
            val grants = old.rows("SELECT * FROM client_club_grants ORDER BY client_id,user_id,club_id,permission_code")
            val migration =
                checkNotNull(
                    javaClass.classLoader.getResource(
                        "db/migration/${old.vendor}/V074__client_club_configuration.sql",
                    ),
                ).readText()
            migration
                .substring(migration.indexOf("INSERT INTO audit_log"))
                .split(';')
                .filter { it.isNotBlank() }
                .forEach(old::execute)
            old.migrate()
            assertEquals(
                grants,
                old.rows("SELECT * FROM client_club_grants ORDER BY client_id,user_id,club_id,permission_code"),
            )
            assertEquals(
                8,
                old
                    .rows(
                        "SELECT id FROM audit_log WHERE action LIKE 'client.club.%.bootstrap' " +
                            "AND metadata_json LIKE '%V074%'",
                    ).size,
            )
        }
    }

    @Test
    fun `closed finance report retains snapshots totals and display references after owner disables used category`() =
        runBlocking<Unit> {
            val application = approved()
            val club = create(application).id("id")
            val database = Database.connect(fixture.source)
            val templates = ShiftReportTemplateRepository(database, fixture.clock)
            val group = templates.createRevenueGroup(club, "Выручка зала", 0)
            val bracelet = templates.createBraceletType(club, "VIP", 0)
            val main = templates.createRevenueArticle(club, group.id, "Исторический бар", true, false, 3)
            val separate = templates.createRevenueArticle(club, group.id, "Депозитные карты", false, true, 9)
            val reports = ShiftReportRepository(database, fixture.clock)
            val night = Instant.parse("2026-10-27T20:00:00Z")
            val report = reports.getOrCreateDraft(club, night)
            val payload =
                ShiftReportUpdatePayload(
                    peopleWomen = 3,
                    peopleMen = 4,
                    peopleRejected = 1,
                    comment = "Историческая смена",
                    bracelets = listOf(ShiftReportBraceletInput(bracelet.id, 7)),
                    revenueEntries = listOf(historyEntry(main.id, 12345), historyEntry(separate.id, 6789)),
                )
            checkNotNull(reports.updateDraft(report.id, payload))
            val closedAt = night.plusSeconds(21600)
            assertTrue(reports.close(report.id, application.id("authorUserId"), closedAt))
            val historical = checkNotNull(reports.getDetails(report.id))
            assertEquals(ShiftReportStatus.CLOSED, historical.report.status)
            assertEquals(closedAt, historical.report.closedAt)
            assertEquals(listOf("Исторический бар", "Депозитные карты"), historical.revenueEntries.map { it.name })
            assertEquals(listOf(3, 9), historical.revenueEntries.map { it.orderIndex })

            var settings = finance(club)
            settings =
                change(
                    club,
                    settings,
                    JsonObject(
                        fields("article", "Новое название бара") +
                            action("article", "update", main.id) +
                            buildJsonObject {
                                put("groupId", group.id.toString())
                                put("includeInTotal", false)
                                put("showSeparately", true)
                                put("orderIndex", 99)
                            },
                    ),
                )
            settings = change(club, settings, action("article", "disable", main.id))
            settings = change(club, settings, action("article", "disable", separate.id))
            change(club, settings, action("group", "disable", group.id))

            val reopened = checkNotNull(ShiftReportRepository(database, fixture.clock).getDetails(report.id))
            assertEquals(historical, reopened)
            assertEquals(historical.report, reports.getByClubAndNight(club, night))
            assertNull(reports.updateDraft(report.id, payload.copy(peopleMen = 99)))
            assertEquals(12345L, totalRevenue(reopened.revenueEntries))
            val indicators = nonTotalIndicators(reopened.revenueEntries)
            assertEquals(listOf(6789L), indicators.all.map { it.amountMinor })
            assertEquals(indicators.all, indicators.showSeparately)
            val reloadedTemplate = templates.getTemplateData(club)
            val archivedGroup = reloadedTemplate.revenueGroups.single { it.id == group.id }
            assertFalse(archivedGroup.enabled)
            assertEquals("Выручка зала", archivedGroup.name)
            val totals =
                totalsPerGroup(
                    reopened.revenueEntries,
                    reloadedTemplate.revenueGroups.associate { it.id to it.name },
                )
            assertEquals(group.id, totals.single().groupId)
            assertEquals("Выручка зала", totals.single().groupName)
            assertEquals(12345L, totals.single().amountMinor)
            val currentArticle = reloadedTemplate.revenueArticles.single { it.id == main.id }
            assertEquals("Новое название бара", currentArticle.name)
            assertFalse(currentArticle.enabled)
            assertFalse(currentArticle.includeInTotal)
            assertTrue(currentArticle.showSeparately)
            assertEquals(99, currentArticle.orderIndex)
            assertEquals(bracelet.id, reopened.bracelets.single().braceletTypeId)
            assertEquals(7, reopened.bracelets.single().count)
            assertTrue(reloadedTemplate.bracelets.any { it.id == reopened.bracelets.single().braceletTypeId })
        }

    private fun historyEntry(
        articleId: Long,
        amountMinor: Long,
    ) = RevenueEntryInput(
        articleId = articleId,
        name = null,
        groupId = null,
        amountMinor = amountMinor,
        includeInTotal = null,
        showSeparately = null,
        orderIndex = null,
    )

    private suspend fun finance(
        club: Long,
        actor: OnboardingActor = fixture.alice,
    ): JsonObject = fixture.repository.read(actor, OnboardingRead.CLUB_FINANCE_GET, club)

    private suspend fun change(
        club: Long,
        state: JsonObject,
        body: JsonObject,
        key: String = UUID.randomUUID().toString(),
        actor: OnboardingActor = fixture.alice,
    ): JsonObject =
        fixture.repository.mutate(
            actor,
            OnboardingMutation.CLUB_FINANCE_UPDATE,
            club,
            JsonObject(body + ("expectedVersion" to state.getValue("version"))),
            key,
        )

    private fun fields(
        entity: String,
        name: String,
    ) = buildJsonObject {
        put("entity", entity)
        put("action", "create")
        put("name", name)
        put("orderIndex", 0)
    }

    private fun action(
        entity: String,
        action: String,
        id: Long,
    ) = buildJsonObject {
        put("entity", entity)
        put("action", action)
        put("id", id.toString())
    }

    private fun JsonObject.items(key: String) = getValue(key).jsonArray.map { it.jsonObject }
}
