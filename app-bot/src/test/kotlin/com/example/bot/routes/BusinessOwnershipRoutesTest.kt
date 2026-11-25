package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class BusinessOwnershipRoutesTest : ClientOnboardingRoutesTestBase(legacyClients = false) {
    @Test
    fun `platform verifies ownership before creation and the limit and financial scope survive direct requests`() =
        database { source ->
            withApp(source) {
                val clientId = enroll()
                val member = member(clientId)
                val root = "$P/clients/$clientId/members/${member.string("userId")}/business-owner"
                val clubs = "$C/clients/$clientId/clubs"
                assertEquals(HttpStatusCode.NotFound, requestAs(clubs, RECIPIENT, place("Before")).status)
                assertEquals(HttpStatusCode.Forbidden, requestAs(root, RECIPIENT, version(member)).status)
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_business_owners"))
                ok(root, OWNER, version(member))
                source.execute("INSERT INTO clubs(id,name,timezone,is_active) VALUES (64001,'Public venue','UTC',TRUE)")
                val publicClub = root.removeSuffix("business-owner") + "business-clubs/64001"
                requestAs(publicClub, OWNER, version(member)).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_clubs WHERE club_id=64001"))
                val club = ok(clubs, RECIPIENT, place("First")).string("id")
                assertEquals("BUSINESS_OWNER", ok("$C/clubs/$club/profile", RECIPIENT).string("businessRole"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_grants WHERE club_id=$club"))
                assertTrue(ok("$C/clubs/$club/business-overview", RECIPIENT).getValue("reports").jsonArray.isEmpty())
                requestAs(
                    clubs,
                    RECIPIENT,
                    place("Second"),
                ).assertClientError(HttpStatusCode.Conflict, "club_limit_reached")
                val settings = ok("$P/clients/$clientId", OWNER)
                ok(
                    "$P/clients/$clientId/limit",
                    OWNER,
                    JsonObject(
                        obj("expectedVersion" to settings.string("limitVersion")) +
                            ("allowedClubs" to JsonPrimitive(2)),
                    ),
                )
                val second = ok(clubs, RECIPIENT, place("Second")).string("id")
                assertEquals(
                    "2",
                    source.scalar("SELECT COUNT(*) FROM client_business_clubs WHERE evidence='OWNER_CREATE'"),
                )
                source.closedAndDraftReports(club, second)
                source.linkedNight(clientId, club)
                val overview = ok("$C/clubs/$club/business-overview", RECIPIENT)
                val night =
                    overview
                        .getValue("nights")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("1", night.string("bookings"))
                assertEquals("1", night.string("checkins"))
                source.execute("UPDATE events SET end_at=end_at+INTERVAL '1' HOUR WHERE id=63001")
                val staleNight = ok("$C/clubs/$club/business-overview", RECIPIENT).getValue("nights").jsonArray.single()
                assertEquals(JsonNull, staleNight.jsonObject["bookings"])
                assertEquals(JsonNull, staleNight.jsonObject["checkins"])
                val report =
                    overview
                        .getValue("reports")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("12345", report.string("totalRevenueMinor"))
                assertEquals("3", report.string("people"))
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$club/business-overview", FOREIGN).status)
                assertEquals("3", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                val profile = ok("$C/clubs/$club/profile", RECIPIENT)
                requestAs(
                    "$C/clubs/$club/profile",
                    RECIPIENT,
                    JsonObject(
                        version(profile) + ("name" to JsonPrimitive("Denied")),
                    ),
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `owner candidate needs a usable Telegram identity in both projection and command`() =
        database { source ->
            withApp(source) {
                val clientId = enroll()
                val eligible = member(clientId)
                val client = "$P/clients/$clientId"
                source.execute("INSERT INTO users(id,telegram_user_id) VALUES (65001,NULL),(65002,0),(65003,-1)")
                for (userId in 65001..65003) {
                    source.execute(
                        "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)",
                    )
                    val candidate =
                        ok(client, OWNER)
                            .getValue("members")
                            .jsonArray
                            .map { it.jsonObject }
                            .single { it.string("userId") == userId.toString() }
                    val state =
                        requestAs(
                            "$client/members/$userId/business-owner",
                            OWNER,
                            version(candidate),
                        ).status
                    assertEquals(
                        JsonPrimitive(false) to HttpStatusCode.Conflict,
                        candidate["canConfirmBusinessOwner"] to state,
                    )
                }
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_business_owners"))
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.business.owner.confirm'"),
                )
                assertEquals(JsonPrimitive(true), eligible["canConfirmBusinessOwner"])
                ok("$client/members/${eligible.string("userId")}/business-owner", OWNER, version(eligible))
                assertEquals(eligible.string("userId"), source.scalar("SELECT user_id FROM client_business_owners"))
            }
        }

    @Test
    fun `overview withholds counters when a second event makes the canonical link ambiguous`() =
        database { source ->
            withApp(source) {
                val clientId = enroll()
                val candidate = member(clientId)
                ok(
                    "$P/clients/$clientId/members/${candidate.string("userId")}/business-owner",
                    OWNER,
                    version(candidate),
                )
                val club = ok("$C/clients/$clientId/clubs", RECIPIENT, place("Metrics")).string("id")
                val path = "$C/clubs/$club/business-overview"
                source.linkedNight(clientId, club)

                suspend fun counters(): Pair<JsonPrimitive?, JsonPrimitive?> {
                    val night =
                        ok(path, RECIPIENT)
                            .getValue("nights")
                            .jsonArray
                            .single()
                            .jsonObject
                    return (night["bookings"] as? JsonPrimitive) to (night["checkins"] as? JsonPrimitive)
                }
                val known = JsonPrimitive(1) to JsonPrimitive(1)
                val unknown = JsonNull to JsonNull
                assertEquals(known, counters())
                source.execute(
                    "INSERT INTO events(id,club_id,start_at,end_at) " +
                        "SELECT 63002,club_id,start_at+INTERVAL '1' HOUR,end_at FROM events WHERE id=63001",
                )
                assertEquals(unknown, counters())
                source.execute("DELETE FROM events WHERE id=63002")
                assertEquals(known, counters())
                source.execute("DELETE FROM canonical_night_event_links WHERE canonical_night_id=63001")
                assertEquals(unknown, counters())
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM bookings WHERE event_id=63001"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM checkins WHERE event_id=63001"))
            }
        }

    @Test
    fun `adoption retires bootstrap writes and preserves independently confirmed rights`() =
        databaseAt("79") { source ->
            withApp(source) {
                val clientId = enroll()
                ok(
                    "$P/clients/$clientId/limit",
                    OWNER,
                    JsonObject(
                        obj("expectedVersion" to "1") + ("allowedClubs" to JsonPrimitive(2)),
                    ),
                )
                val clubs = "$C/clients/$clientId/clubs"
                val first = ok(clubs, RECIPIENT, place("Simple"), key = "legacy-club-create").string("id")
                val ambiguous = ok(clubs, RECIPIENT, place("Review")).string("id")
                val member = member(clientId)
                val root = "$P/clients/$clientId/members/${member.string("userId")}"
                source.execute(
                    "UPDATE client_club_grants SET updated_at=TIMESTAMP '2099-01-01 00:00:00' WHERE " +
                        "club_id=$ambiguous AND permission_code='client.club.calendar.write'",
                )
                // A separate, active editor of the first club has independent provenance.
                source.execute("INSERT INTO users(telegram_user_id) VALUES ($FOREIGN)")
                val editor = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                source.execute(
                    "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$editor,TRUE)",
                )
                for (code in listOf("client.club.read", "client.club.profile.write")) {
                    source.execute(
                        "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code," +
                            "is_active) VALUES ($clientId,$editor,$first,'$code',TRUE)",
                    )
                }
                upgradeClubFixture(source)
                val access = ok("$C/clients/$clientId/onboarding", RECIPIENT)
                assertEquals("false", access.string("canCreate"))
                val third = requestAs(clubs, RECIPIENT, place("Third"))
                assertEquals(HttpStatusCode.NotFound, third.status)
                assertEquals(first, ok(clubs, RECIPIENT, place("Simple"), key = "legacy-club-create").string("id"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_business_owners"))
                val verified = ok("$root/business-owner", OWNER, version(member))
                assertEquals(listOf(JsonPrimitive(first)), verified.getValue("businessClubIds").jsonArray.toList())
                val reviewed =
                    verified
                        .getValue(
                            "businessClubsForReview",
                        ).jsonArray
                        .single()
                        .jsonObject
                        .getValue("grants")
                        .jsonArray
                assertEquals("client.club.calendar.write", reviewed.single().jsonObject.string("permission"))
                assertFalse(
                    ok(
                        "$C/clubs/$first/profile",
                        RECIPIENT,
                    ).getValue("availableActions").jsonArray.contains(JsonPrimitive("update")),
                )
                assertTrue(
                    ok(
                        "$C/clubs/$first/profile",
                        FOREIGN,
                    ).getValue("availableActions").jsonArray.contains(JsonPrimitive("update")),
                )
                val path = "$root/business-clubs/$ambiguous"
                requestAs(path, OWNER, version(member)).assertClientError(HttpStatusCode.Conflict, "version_conflict")
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_business_clubs"))
                val choices = JsonArray(reviewed.map { JsonObject(it.jsonObject + ("keep" to JsonPrimitive(true))) })
                ok(path, OWNER, JsonObject(version(member) + ("reviewedGrants" to choices)))
                assertEquals(JsonPrimitive(true), ok("$C/clubs/$ambiguous/calendar", RECIPIENT)["canWrite"])
                assertEquals(JsonPrimitive(false), ok("$C/clubs/$ambiguous/booking-rules", RECIPIENT)["canWrite"])
                checkRestoredReader(root, clientId, first, ambiguous)
            }
        }

    @Test
    fun `manual attachment opens saved club data and only a confirmed manager updates its profile`() =
        database { source ->
            withApp(source) {
                val clientId = enroll()
                val clientMember = member(clientId)
                val root = "$P/clients/$clientId/members/${clientMember.string("userId")}"
                val clubId = 64001
                val path = "$C/clubs/$clubId"
                val profile = "$path/profile"
                val metadata = "SELECT profile_version FROM client_business_clubs WHERE club_id=$clubId"
                source.execute(
                    "INSERT INTO clubs(id,name,description,timezone,is_active) " +
                        "VALUES ($clubId,'Historic club','Saved description','Europe/Paris',FALSE)," +
                        "(64002,'Public club','','UTC',TRUE)",
                )
                source.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,$clubId)")
                ok("$root/business-owner", OWNER, version(clientMember))
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, RECIPIENT).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$root/business-clubs/64002", OWNER, version(clientMember)).status,
                )
                ok("$root/business-clubs/$clubId", OWNER, version(clientMember))
                val ownerView = ok(profile, RECIPIENT)
                assertEquals("Historic club", ownerView.string("name"))
                assertEquals("Saved description", ownerView.string("description"))
                assertEquals("Europe/Paris", ownerView.string("timezone"))
                assertEquals("1", ownerView.string("version"))
                assertEquals(kotlinx.serialization.json.JsonNull, ownerView["savedAt"])
                for (key in listOf("city", "address", "countryCode", "unit")) assertEquals("", ownerView.string(key))
                assertEquals(JsonPrimitive(false), ownerView["canPreviewBooking"])
                assertTrue(ownerView.getValue("availableActions").jsonArray.isEmpty())
                val patch = obj("expectedVersion" to "1", "name" to "Updated historic club")
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, RECIPIENT, patch, HttpMethod.Patch).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, FOREIGN).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/64002/profile", RECIPIENT).status)

                val roster = "$path/managers"
                val invitation =
                    ok(
                        roster,
                        RECIPIENT,
                        obj(
                            "expectedVersion" to ok(roster, RECIPIENT).string("version"),
                            "action" to "invite",
                            "role" to "CLUB_MANAGER",
                            "recipientLabel" to "Club manager",
                        ),
                    )
                val invitationId = invitation.string("createdInvitationId")
                val claimed =
                    ok(
                        "$C/manager-invitations/$invitationId/claim",
                        FOREIGN,
                        obj("expectedVersion" to "1", "token" to invitation.string("token")),
                    )
                ok(
                    roster,
                    RECIPIENT,
                    JsonObject(
                        obj(
                            "expectedVersion" to ok(roster, RECIPIENT).string("version"),
                            "action" to "confirm",
                            "invitationId" to invitationId,
                            "expectedInvitationVersion" to claimed.string("version"),
                            "verificationCode" to claimed.string("verificationCode"),
                        ) + ("confirmed" to JsonPrimitive(true)),
                    ),
                )
                val saved = ok(profile, FOREIGN, patch, HttpMethod.Patch, key = "historic-profile")
                assertEquals("Updated historic club", saved.string("name"))
                assertEquals("Saved description", saved.string("description"))
                assertEquals("Europe/Paris", saved.string("timezone"))
                assertEquals("2", saved.string("version"))
                Instant.parse(saved.string("savedAt"))
                assertEquals(saved, ok(profile, FOREIGN, patch, HttpMethod.Patch, key = "historic-profile"))
                requestAs(
                    profile,
                    FOREIGN,
                    patch,
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.Conflict, "version_conflict")
                assertEquals("2", source.scalar(metadata))
                assertEquals(saved["savedAt"], ok(profile, RECIPIENT)["savedAt"])
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM client_direct_club_profiles WHERE club_id=$clubId"),
                )
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_onboarding WHERE club_id=$clubId"))
            }
        }

    private suspend fun ApplicationTestBuilder.checkRestoredReader(
        memberPath: String,
        clientId: String,
        restoredClub: String,
        otherClub: String,
    ) {
        val revoked = ok("$memberPath/revoke", OWNER, version(member(clientId)))
        val selected = JsonArray(listOf(obj("clubId" to restoredClub, "permission" to "client.club.read")))
        ok(
            "$memberPath/restore",
            OWNER,
            JsonObject(version(revoked) + mapOf("permissions" to JsonArray(emptyList()), "clubGrants" to selected)),
        )
        val path = "$C/clients/$clientId/onboarding"
        val onboarding = ok(path, RECIPIENT)
        assertEquals("false", onboarding.string("businessOwner"))
        assertEquals("false", onboarding.string("canCreate"))
        assertEquals(listOf(restoredClub), onboarding.getValue("clubs").jsonArray.map { it.jsonObject.string("id") })
        val context =
            ok("$C/context", RECIPIENT)
                .getValue("clients")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(listOf(restoredClub), context.getValue("clubs").jsonArray.map { it.jsonObject.string("id") })
        assertFalse("businessRole" in ok("$C/clubs/$restoredClub/profile", RECIPIENT))
        assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$otherClub/profile", RECIPIENT).status)
        val old = signed(RECIPIENT, Instant.now().minusSeconds(301))
        for (scope in listOf(path, "$C/clubs/$restoredClub/profile")) {
            client
                .get(
                    scope,
                ) { header("X-Telegram-Init-Data", old) }
                .assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
        }
    }

    private suspend fun ApplicationTestBuilder.enroll(): String {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Business", "recipientLabel" to "Candidate"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            RECIPIENT,
            obj(
                "token" to invite.string("token"),
                "expectedVersion" to invite.string("version"),
            ),
        )
        return invite.string("clientId")
    }

    private suspend fun ApplicationTestBuilder.member(client: String): JsonObject =
        ok("$P/clients/$client", OWNER)
            .getValue("members")
            .jsonArray
            .map { it.jsonObject }
            .single { it.string("telegramUserId") == RECIPIENT.toString() }

    private fun place(name: String) =
        obj(
            "venueName" to name,
            "countryCode" to "RU",
            "cityId" to "524901",
            "address" to name,
        )

    private fun DataSource.linkedNight(
        clientId: String,
        club: String,
    ) {
        execute("UPDATE clubs SET timezone='UTC' WHERE id=$club")
        val from = "TIMESTAMP '2026-01-11 20:00:00'"
        val until = "TIMESTAMP '2026-01-12 06:00:00'"
        execute(
            "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone,start_at," +
                "end_at,source,status,created_at,updated_at) " +
                "VALUES (63001,$clientId,$club,DATE '2026-01-11','UTC',$from,$until,'WEEKEND_RULE'," +
                "'SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        )
        execute("INSERT INTO events(id,club_id,start_at,end_at) VALUES (63001,$club,$from,$until)")
        execute(
            "INSERT INTO canonical_night_event_links(canonical_night_id,event_id,club_id,legacy_start_at," +
                "legacy_end_at) " +
                "VALUES (63001,63001,$club,$from,$until)",
        )
        execute("INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (63001,$club,1,4,0)")
        execute(
            "INSERT INTO bookings(event_id,club_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
                "slot_start,slot_end,status,qr_secret,idempotency_key) VALUES (63001,$club,63001,1,2,0,0," +
                "$from,$until,'BOOKED','sample','overview-booking')",
        )
        execute(
            "INSERT INTO checkins(club_id,event_id,subject_type,subject_id,method,result_status) " +
                "VALUES ($club,63001,'GUEST_LIST_ENTRY','101','NAME','ARRIVED')",
        )
    }

    private fun DataSource.closedAndDraftReports(
        club: String,
        other: String,
    ) {
        for ((id, venue, status) in listOf(
            Triple(61001, club, "CLOSED"),
            Triple(61002, club, "DRAFT"),
            Triple(61003, other, "CLOSED"),
        )) {
            execute(
                "INSERT INTO shift_reports(id,club_id,night_start_utc,status,people_women,people_men," +
                    "closed_at,created_at,updated_at) " +
                    "VALUES ($id,$venue,TIMESTAMP '2026-01-${id - 61000 + 10} 20:00:00','$status',1,2," +
                    "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            )
        }
        execute(
            "INSERT INTO club_revenue_groups(id,club_id,name,created_at,updated_at) VALUES (62001,$club," +
                "'Sales',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        )
        for ((amount, included) in listOf(12345 to true, 9999 to false)) {
            execute(
                "INSERT INTO shift_report_revenue_entries(report_id,name,group_id,amount_minor," +
                    "include_in_total) VALUES (61001,'Sale',62001,$amount,$included)",
            )
        }
    }

    private fun DataSource.execute(query: String) {
        connection.use { it.createStatement().use { statement -> statement.execute(query) } }
    }
}
