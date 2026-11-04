package com.example.bot.data.checkin

import com.example.bot.checkin.CheckinConfig
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.data.client.onboarding.OnboardingRead
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.id
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.NewCheckin
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

internal class CanonicalBookingCheckinH2Test {
    @Test
    fun `atomic admission records the original visit key and replays on a fresh repository`() =
        h2 { fixture ->
            val at = fixture.window.start.plusNanos(123_456_789)
            val precise = fixture.repository(at = Clock.fixed(at, ZoneOffset.UTC))
            val first = fixture.admit(repository = precise).admitted()
            assertEquals(at.truncatedTo(ChronoUnit.MICROS), first.checkin.occurredAt)
            assertEquals(first.checkin.occurredAt, first.visit.firstCheckinAt)
            val afterClose = Clock.fixed(fixture.window.end, ZoneOffset.UTC)
            val repeated = fixture.admit(repository = fixture.repository(at = afterClose)).admitted()
            assertEquals(first.copy(replayed = true), repeated)
            fixture.assertAdmission(first)
            assertEquals(CanonicalCheckinResult.Rejected.ALREADY_ADMITTED, fixture.admit(bookingId = SECOND_BOOKING))
            fixture.assertAdmission(first)
        }

    @Test
    fun `protected and legacy admission stay separate when an old checkin has no visit`() =
        h2 { fixture ->
            val legacy = CheckinDbRepository(Database.connect(fixture.source), fixture.clock)
            val record =
                legacy.insertWithBookingUpdate(
                    NewCheckin(
                        CLUB,
                        EVENT,
                        CheckinSubjectType.BOOKING,
                        BOOKING.toString(),
                        HOST.userId,
                        CheckinMethod.NAME,
                        CheckinResultStatus.ARRIVED,
                        null,
                        fixture.window.now,
                    ),
                    BOOKING,
                    BookingStatus.SEATED,
                )
            assertEquals(BOOKING.toString(), record?.subjectId)
            assertEquals("0", fixture.count("club_visits"))
            assertEquals(CanonicalCheckinResult.Rejected.ALREADY_ADMITTED, fixture.admit())
            assertEquals(CanonicalCheckinResult.Rejected.ALREADY_ADMITTED, fixture.admit(bookingId = SECOND_BOOKING))
            assertEquals("1", fixture.count("checkins"))
            assertEquals("0", fixture.count("club_visits"))
            assertEquals("0", fixture.count("audit_log", "action='CHECKIN'"))
            fixture.assertNoRewards()
        }

    @Test
    fun `outside the night or with a mismatched booking slot admission makes no writes`() =
        h2 { fixture ->
            val beforeOpen = Clock.fixed(fixture.window.start.minusSeconds(1), ZoneOffset.UTC)
            val atClose = Clock.fixed(fixture.window.end, ZoneOffset.UTC)
            assertEquals(
                CanonicalCheckinResult.Rejected.NIGHT_UNAVAILABLE,
                fixture.admit(repository = fixture.repository(at = beforeOpen)),
            )
            assertEquals(
                CanonicalCheckinResult.Rejected.NIGHT_UNAVAILABLE,
                fixture.admit(repository = fixture.repository(at = atClose)),
            )
            fixture.source.write("UPDATE bookings SET slot_end='${fixture.window.now}' WHERE id='$BOOKING'")
            assertEquals(CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE, fixture.admit())
            fixture.assertUntouched()
        }

    @Test
    fun `arrival grace includes the exact boundary`() {
        val limit = Instant.parse("2026-11-04T00:10:00Z")
        assertEquals(CheckinResultStatus.ARRIVED, arrivalCheckinStatus(limit, limit.plusSeconds(900), 15))
        assertEquals(CheckinResultStatus.LATE, arrivalCheckinStatus(limit, limit.plusSeconds(901), 15))
        assertEquals(CheckinResultStatus.ARRIVED, arrivalCheckinStatus(null, limit, 15))
    }

    private fun h2(block: suspend (CanonicalCheckinFixture) -> Unit) =
        runBlocking {
            val source =
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:canonical-checkin-${UUID.randomUUID()};" +
                            "MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                }
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/h2")
                .load()
                .migrate()
            try {
                block(CanonicalCheckinFixture(source).also { it.seed() })
            } finally {
                source.write("SHUTDOWN")
            }
        }
}

internal class CanonicalCheckinFixture(
    val source: DataSource,
    val window: CheckinWindow = MIDNIGHT,
) {
    val clock: Clock = Clock.fixed(window.now, ZoneOffset.UTC)
    val calendar = JdbcClientOnboardingRepository(source, null, clock)
    private val db = Database.connect(source)
    private val checkin = CanonicalBookingCheckin(db, clock, CheckinConfig(15))
    lateinit var calendarView: JsonObject
        private set
    var nightId = 0L
        private set

    suspend fun seed(linked: Boolean = true) {
        source.write("INSERT INTO users(id,telegram_user_id) VALUES (8801,101),(8802,102),(8803,103),(8804,104)")
        source.write("INSERT INTO clients(id,display_name,is_active) VALUES ($CLUB,'Test client',TRUE)")
        source.write("INSERT INTO clubs(id,name,timezone,is_active) VALUES ($CLUB,'Private','${window.zone}',FALSE)")
        source.write("INSERT INTO client_clubs(client_id,club_id) VALUES ($CLUB,$CLUB)")
        source.write(
            "INSERT INTO client_memberships(client_id,user_id,is_active) " +
                "VALUES ($CLUB,8801,TRUE),($CLUB,8802,TRUE),($CLUB,8803,TRUE)",
        )
        source.write(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                "SELECT $CLUB,u,$CLUB,'client.club.read',TRUE FROM (VALUES (8801),(8802),(8803)) AS actors(u)",
        )
        source.write(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                "VALUES ($CLUB,8801,$CLUB,'client.club.calendar.write',TRUE)",
        )
        source.write(
            "INSERT INTO user_roles(user_id,role_code,scope_type,scope_club_id) " +
                "VALUES (8802,'ENTRY_MANAGER','CLUB',$CLUB),(8803,'MANAGER','CLUB',$CLUB)",
        )
        source.write(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) " +
                "VALUES (8801,$CLUB,1,4,0),(8802,$CLUB,2,4,0)",
        )

        val beforeOpen = Clock.fixed(window.start.minusSeconds(86_400), ZoneOffset.UTC)
        val setup = JdbcClientOnboardingRepository(source, null, beforeOpen)
        val initial = setup.read(EDITOR, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
        val saved =
            setup.mutate(
                EDITOR,
                OnboardingMutation.CLUB_CALENDAR_UPDATE,
                CLUB,
                calendarBody(
                    initial,
                    """{"date":"${window.businessDate}","mode":"open","open":"${window.open}","close":"08:00",""" +
                        """"startNextDay":true,"nextDay":false}""",
                ),
                "seed-checkin-night",
            )
        nightId =
            saved
                .getValue("nights")
                .jsonArray
                .single()
                .jsonObject
                .id("nightId")
        source.write(
            "INSERT INTO events(id,club_id,start_at,end_at) VALUES ($EVENT,$CLUB,'${window.start}','${window.end}')",
        )
        if (linked) {
            setup.mutate(
                EDITOR,
                OnboardingMutation.CLUB_NIGHT_EVENT_LINK,
                nightId,
                JsonObject(
                    mapOf(
                        "expectedVersion" to saved.getValue("version"),
                        "eventId" to JsonPrimitive(EVENT.toString()),
                    ),
                ),
                "link-checkin-night",
            )
        }
        insertBooking(BOOKING, 8801, 1)
        insertBooking(SECOND_BOOKING, 8802, 2)
        calendarView = calendar.read(EDITOR, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
    }

    fun repository(
        dataSource: DataSource = source,
        at: Clock = clock,
    ) = CanonicalBookingCheckin(Database.connect(dataSource), at, CheckinConfig(15))

    suspend fun admit(
        bookingId: UUID = BOOKING,
        actor: AuthContext = HOST,
        clubId: Long = CLUB,
        eventId: Long = EVENT,
        repository: CanonicalBookingCheckin = checkin,
    ): CanonicalCheckinResult = repository.admit(clubId, eventId, bookingId, actor)

    suspend fun closeNight(repository: JdbcClientOnboardingRepository = calendar) =
        repository.mutate(
            EDITOR,
            OnboardingMutation.CLUB_CALENDAR_UPDATE,
            CLUB,
            calendarBody(calendarView, """{"date":"${window.businessDate}","mode":"closed"}"""),
            "close-checkin-night",
        )

    fun assertAdmission(admission: CanonicalCheckinResult.Admitted) {
        assertEquals(EVENT, admission.checkin.eventId)
        assertEquals(EVENT, admission.visit.eventId)
        assertEquals(window.start, admission.visit.nightStartUtc)
        assertEquals(GUEST, admission.visit.userId)
        assertEquals(true, admission.visit.hasTable)
        assertEquals("1", count("checkins"))
        assertEquals("1", count("club_visits"))
        assertEquals("1", count("audit_log", "action='CHECKIN'"))
        assertEquals(
            "1",
            count("audit_log", "action='CHECKIN' AND night_id=$EVENT AND entity_id=${admission.checkin.id}"),
        )
        assertEquals("SEATED", bookingStatus())
        assertEquals("BOOKED", bookingStatus(SECOND_BOOKING))
        assertNoRewards()
    }

    fun assertUntouched() {
        assertEquals("BOOKED", bookingStatus())
        assertEquals("0", count("checkins"))
        assertEquals("0", count("club_visits"))
        assertEquals("0", count("audit_log", "action='CHECKIN'"))
        assertNoRewards()
    }

    fun assertNoRewards() {
        assertEquals("0", count("user_badges"))
        assertEquals("0", count("reward_coupons"))
    }

    fun bookingStatus(id: UUID = BOOKING): String =
        source.connection.use {
            it.scalar("SELECT status FROM bookings WHERE id='$id'")
        }

    fun count(
        table: String,
        condition: String = "1=1",
    ): String =
        source.connection.use {
            it.scalar("SELECT count(*) FROM $table WHERE $condition")
        }

    private fun insertBooking(
        id: UUID,
        tableId: Long,
        tableNumber: Int,
    ) {
        source.write(
            "INSERT INTO bookings(id,club_id,event_id,table_id,table_number,guest_user_id,guests_count,min_deposit," +
                "total_deposit,slot_start,slot_end,status,qr_secret,idempotency_key) VALUES " +
                "('$id',$CLUB,$EVENT,$tableId,$tableNumber,$GUEST,2,0,0," +
                "'${window.start}','${window.end}','BOOKED','synthetic-$tableId','synthetic-booking-$tableId')",
        )
    }

    private fun calendarBody(
        base: JsonObject,
        rule: String,
    ): JsonObject =
        Json
            .parseToJsonElement(
                """
                {"expectedVersion":"${base.id("version")}","timezone":"${window.zone}",
                 "weekly":[],"holidays":[],"exceptions":[$rule]}
                """.trimIndent(),
            ).jsonObject
}

internal data class CheckinWindow(
    val businessDate: String,
    val zone: String,
    val open: String,
    val start: Instant,
    val end: Instant,
    val now: Instant,
)

internal val MIDNIGHT =
    CheckinWindow(
        "2026-11-03",
        "UTC",
        "00:00",
        Instant.parse("2026-11-04T00:00:00Z"),
        Instant.parse("2026-11-04T08:00:00Z"),
        Instant.parse("2026-11-04T01:00:00Z"),
    )
internal val DST =
    CheckinWindow(
        "2026-10-24",
        "Europe/Berlin",
        "02:00",
        Instant.parse("2026-10-25T00:00:00Z"),
        Instant.parse("2026-10-25T07:00:00Z"),
        Instant.parse("2026-10-25T01:30:00Z"),
    )
internal const val CLUB = 8801L
internal const val EVENT = 8801L
internal const val GUEST = 8804L
internal val BOOKING = UUID.fromString("5c25195f-f004-4ffc-8260-12a3c5496fb8")
internal val SECOND_BOOKING = UUID.fromString("8c4ff304-be9e-4f43-9816-3be258db92d3")
internal val EDITOR = OnboardingActor(101, "platform")
internal val HOST = AuthContext(8802, 102, setOf(Role.ENTRY_MANAGER))
internal val MANAGER = AuthContext(8803, 103, setOf(Role.MANAGER))

internal fun CanonicalCheckinResult.admitted(): CanonicalCheckinResult.Admitted =
    this as? CanonicalCheckinResult.Admitted ?: throw AssertionError("Expected admission, got $this")

internal fun DataSource.write(sql: String) = connection.use { it.executeSql(sql) }

internal fun Connection.executeSql(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun Connection.scalar(query: String): String =
    checkNotNull(checkNotNull(OnboardingSql(this).row(query)).values.single())
