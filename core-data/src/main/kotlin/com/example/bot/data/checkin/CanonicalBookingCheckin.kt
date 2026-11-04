package com.example.bot.data.checkin

import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.checkin.CheckinConfig
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.core.canonicalBookingTransaction
import com.example.bot.data.client.onboarding.LockedCanonicalNight
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.calendarClub
import com.example.bot.data.client.onboarding.instant
import com.example.bot.data.client.onboarding.lockLinkedCanonicalNight
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinRecord
import com.example.bot.data.club.NewCheckin
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import com.example.bot.data.visits.ClubVisit
import com.example.bot.data.visits.VisitCheckInInput
import com.example.bot.data.visits.VisitRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

private val HOST_CHECKIN_ROLES = setOf(Role.ENTRY_MANAGER, Role.MANAGER, Role.CLUB_ADMIN)

internal fun arrivalCheckinStatus(
    arrivalBy: Instant?,
    occurredAt: Instant,
    graceMinutes: Int,
): CheckinResultStatus {
    val graceEnd = arrivalBy?.plus(graceMinutes.toLong(), ChronoUnit.MINUTES)
    return if (graceEnd != null && occurredAt.isAfter(graceEnd)) {
        CheckinResultStatus.LATE
    } else {
        CheckinResultStatus.ARRIVED
    }
}

internal sealed interface CanonicalCheckinResult {
    data class Admitted(
        val checkin: CheckinRecord,
        val visit: ClubVisit,
        val replayed: Boolean,
    ) : CanonicalCheckinResult

    enum class Rejected : CanonicalCheckinResult {
        FORBIDDEN,
        BOOKING_UNAVAILABLE,
        NIGHT_UNAVAILABLE,
        ALREADY_ADMITTED,
    }
}

/** Disconnected host admission for DB bookings with a known guest. No route or DI calls this path. */
internal class CanonicalBookingCheckin(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val config: CheckinConfig = CheckinConfig.fromEnv(),
) {
    private val checkins = CheckinDbRepository(db, clock)
    private val visits = VisitRepository(db, clock)

    suspend fun admit(
        clubId: Long,
        eventId: Long,
        bookingId: UUID,
        actor: AuthContext,
    ): CanonicalCheckinResult =
        canonicalBookingTransaction(db, name = "checkin.canonical") {
            val provisionalClub =
                BookingsTable
                    .select(BookingsTable.clubId)
                    .where { BookingsTable.id eq bookingId }
                    .singleOrNull()
                    ?.get(BookingsTable.clubId)
            if (provisionalClub != clubId) {
                return@canonicalBookingTransaction CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE
            }

            val sql = OnboardingSql(TransactionManager.current().connection.connection as Connection)
            val host =
                lockHostClub(sql, clubId, actor)
                    ?: return@canonicalBookingTransaction CanonicalCheckinResult.Rejected.FORBIDDEN
            val night =
                sql.lockLinkedCanonicalNight(clubId, eventId, host.timezone)
                    ?: return@canonicalBookingTransaction CanonicalCheckinResult.Rejected.NIGHT_UNAVAILABLE
            val booking =
                BookingsTable
                    .select(
                        BookingsTable.id,
                        BookingsTable.clubId,
                        BookingsTable.eventId,
                        BookingsTable.guestUserId,
                        BookingsTable.slotStart,
                        BookingsTable.slotEnd,
                        BookingsTable.status,
                        BookingsTable.arrivalBy,
                    ).where {
                        (BookingsTable.id eq bookingId) and (BookingsTable.clubId eq clubId) and
                            (BookingsTable.eventId eq eventId)
                    }.forUpdate()
                    .singleOrNull()
                    ?: return@canonicalBookingTransaction CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE
            admitLocked(sql, host.role, night, booking, actor)
        }

    private fun lockHostClub(
        sql: OnboardingSql,
        clubId: Long,
        actor: AuthContext,
    ): AuthorizedHost? {
        val telegramId =
            actor.telegramUserId?.takeIf {
                it > 0 &&
                    actor.roles.any { role -> role in HOST_CHECKIN_ROLES } &&
                    sql.user(it)?.long("id") == actor.userId
            } ?: return null
        val session = OnboardingSession(sql, OnboardingActor(telegramId, "platform"), actor.userId, null, clock)
        return try {
            session.requireClubGrant(clubId)
            val club = session.calendarClub(clubId, lock = true)
            val role = if (club.active()) null else scopedRole(sql, clubId, actor)
            role?.let { AuthorizedHost(it, checkNotNull(club["timezone"])) }
        } catch (_: OnboardingFailure) {
            null
        }
    }

    private fun scopedRole(
        sql: OnboardingSql,
        clubId: Long,
        actor: AuthContext,
    ): Role? {
        val placeholders = HOST_CHECKIN_ROLES.joinToString(",") { "?" }
        return sql
            .rowsWithArguments(
                "SELECT role_code FROM user_roles WHERE user_id=? AND scope_type='CLUB' AND scope_club_id=? " +
                    "AND role_code IN ($placeholders) ORDER BY role_code FOR UPDATE",
                listOf(actor.userId, clubId) + HOST_CHECKIN_ROLES.map(Role::name),
            ).map { Role.valueOf(checkNotNull(it["role_code"])) }
            .firstOrNull { it in actor.roles }
    }

    private fun admitLocked(
        sql: OnboardingSql,
        role: Role,
        night: LockedCanonicalNight,
        booking: ResultRow,
        actor: AuthContext,
    ): CanonicalCheckinResult {
        val guestId = booking[BookingsTable.guestUserId]?.takeIf { it > 0 }
        val slotMatches =
            booking[BookingsTable.slotStart].toInstant() == night.legacyStart &&
                booking[BookingsTable.slotEnd].toInstant() == night.end
        if (guestId == null || !slotMatches) return CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE

        val clubId = booking[BookingsTable.clubId]
        val subjectId = booking[BookingsTable.id].toString()
        val eventId = booking[BookingsTable.eventId]
        val existing = checkins.findCheckinInTransaction(CheckinSubjectType.BOOKING, subjectId)
        val visit = visits.findCheckInInTransaction(clubId, night.legacyStart, guestId)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return when {
            existing != null -> replay(sql, booking, existing, visit)
            visit != null || hasBookingAdmission(sql, clubId, eventId, guestId) ->
                CanonicalCheckinResult.Rejected.ALREADY_ADMITTED
            !night.contains(now) -> CanonicalCheckinResult.Rejected.NIGHT_UNAVAILABLE
            booking[BookingsTable.status] != BookingStatus.BOOKED.name ->
                CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE
            else -> insertAdmission(sql, night, booking, AdmissionActor(actor.userId, role, guestId), now)
        }
    }

    private fun hasBookingAdmission(
        sql: OnboardingSql,
        clubId: Long,
        eventId: Long,
        guestId: Long,
    ): Boolean =
        sql.row(
            "SELECT 1 FROM checkins c JOIN bookings b ON c.subject_id=CAST(b.id AS VARCHAR) " +
                "AND b.club_id=c.club_id AND b.event_id=c.event_id " +
                "WHERE c.club_id=? AND c.event_id=? AND b.guest_user_id=? AND c.subject_type='BOOKING' " +
                "AND c.result_status IN ('ARRIVED','LATE') LIMIT 1",
            clubId,
            eventId,
            guestId,
        ) != null

    private fun insertAdmission(
        sql: OnboardingSql,
        night: LockedCanonicalNight,
        booking: ResultRow,
        actor: AdmissionActor,
        now: Instant,
    ): CanonicalCheckinResult {
        val clubId = booking[BookingsTable.clubId]
        val eventId = booking[BookingsTable.eventId]
        val id = booking[BookingsTable.id]
        val checkin =
            checkins.insertBookingCheckinInTransaction(
                NewCheckin(
                    clubId = clubId,
                    eventId = eventId,
                    subjectType = CheckinSubjectType.BOOKING,
                    subjectId = id.toString(),
                    checkedBy = actor.id,
                    method = CheckinMethod.NAME,
                    resultStatus =
                        arrivalCheckinStatus(
                            booking[BookingsTable.arrivalBy]?.toInstant(),
                            now,
                            config.lateGraceMinutes,
                        ),
                    denyReason = null,
                    occurredAt = now,
                ),
                id,
                BookingStatus.SEATED,
                createdAt = now.atOffset(ZoneOffset.UTC),
            ) ?: return CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE

        val visit =
            visits
                .insertCheckInInTransaction(
                    VisitCheckInInput(
                        clubId = clubId,
                        nightStartUtc = night.legacyStart,
                        eventId = eventId,
                        userId = actor.guestId,
                        actorUserId = actor.id,
                        actorRole = actor.role,
                        entryType = CheckinSubjectType.BOOKING.name,
                        firstCheckinAt = now,
                        effectiveEarlyCutoffAt = earlyCutoff(sql, clubId, night.legacyStart),
                    ),
                    now,
                    hasTable = true,
                    ignoreExisting = false,
                ).visit
        auditAdmission(checkin, visit, now)
        return CanonicalCheckinResult.Admitted(checkin, visit, replayed = false)
    }

    private fun replay(
        sql: OnboardingSql,
        booking: ResultRow,
        checkin: CheckinRecord,
        visit: ClubVisit?,
    ): CanonicalCheckinResult {
        val matchingCheckin =
            checkin.clubId == booking[BookingsTable.clubId] &&
                checkin.eventId == booking[BookingsTable.eventId] &&
                checkin.resultStatus != CheckinResultStatus.DENIED
        val matchingVisit =
            visit != null &&
                visit.eventId == checkin.eventId &&
                visit.firstCheckinAt == checkin.occurredAt &&
                visit.actorUserId == checkin.checkedBy
        val recorded =
            visit?.let {
                sql.row("SELECT 1 FROM audit_log WHERE fingerprint=?", fingerprint(checkin.id, it.id)) != null
            } == true
        return if (matchingCheckin && matchingVisit && recorded) {
            CanonicalCheckinResult.Admitted(checkin, visit, replayed = true)
        } else {
            CanonicalCheckinResult.Rejected.ALREADY_ADMITTED
        }
    }

    private fun earlyCutoff(
        sql: OnboardingSql,
        clubId: Long,
        legacyStart: Instant,
    ): Instant? =
        sql
            .row(
                "SELECT early_cutoff_at FROM operational_night_overrides WHERE club_id=? AND night_start_utc=?",
                clubId,
                legacyStart,
            )?.takeIf { it["early_cutoff_at"] != null }
            ?.instant("early_cutoff_at")
            ?: sql
                .row("SELECT early_window_minutes FROM club_gamification_settings WHERE club_id=?", clubId)
                ?.get("early_window_minutes")
                ?.toLong()
                ?.let { legacyStart.plus(it, ChronoUnit.MINUTES) }

    private fun auditAdmission(
        checkin: CheckinRecord,
        visit: ClubVisit,
        now: Instant,
    ) {
        AuditLogTable.insert {
            it[createdAt] = now.atOffset(ZoneOffset.UTC)
            it[clubId] = visit.clubId
            it[nightId] = visit.eventId
            it[actorUserId] = visit.actorUserId
            it[actorRole] = visit.actorRole?.name
            it[subjectUserId] = visit.userId
            it[entityType] = StandardAuditEntityType.VISIT.value
            it[entityId] = checkin.id
            it[action] = StandardAuditAction.CHECKIN.value
            it[fingerprint] = fingerprint(checkin.id, visit.id)
            it[metadataJson] =
                buildJsonObject {
                    put("subjectType", checkin.subjectType.name)
                    put("subjectId", checkin.subjectId)
                    put("method", checkin.method.name)
                    put("status", checkin.resultStatus.name)
                    put("visitId", visit.id)
                }.toString()
        }
    }

    private fun fingerprint(
        checkinId: Long,
        visitId: Long,
    ) = "VISIT:CHECKIN:canonical-booking:$checkinId:$visitId:v1"

    private data class AuthorizedHost(
        val role: Role,
        val timezone: String,
    )

    private data class AdmissionActor(
        val id: Long,
        val role: Role,
        val guestId: Long,
    )
}
