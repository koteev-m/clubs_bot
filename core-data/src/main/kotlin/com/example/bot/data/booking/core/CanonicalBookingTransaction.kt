package com.example.bot.data.booking.core

import com.example.bot.data.client.onboarding.LockedCanonicalNight
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.WRITE_CALENDAR
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.digest
import com.example.bot.data.client.onboarding.instant
import com.example.bot.data.client.onboarding.lockLinkedCanonicalNight
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.client.onboarding.updateLock
import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Explicit input to the cabinet-only prototype. No runtime route constructs it. */
internal data class CanonicalBookingScope(
    val actor: OnboardingActor,
    val clubId: Long,
    val nightId: Long,
    val eventId: Long,
    val calendarVersion: Long,
) {
    fun command(
        operation: CanonicalBookingOperation,
        key: String,
        vararg parameters: Any,
    ): CanonicalBookingCommand =
        CanonicalBookingCommand(
            operation,
            digest("${actor.connectionKey}:${actor.telegramUserId}:$key"),
            digest((listOf(clubId, nightId, eventId, calendarVersion) + parameters).joinToString("|")),
        )
}

internal enum class CanonicalBookingOperation { HOLD, CONFIRM }

internal data class CanonicalBookingCommand(
    val operation: CanonicalBookingOperation,
    val keyHash: String,
    val requestHash: String,
)

internal data class CanonicalBookingReceipt(
    val requestHash: String,
    val resultId: UUID,
    val holdExpiresAt: Instant?,
)

/** Only constructed within the repository's current write transaction. */
internal class CanonicalBookingTransaction(
    private val scope: CanonicalBookingScope,
    private val clock: Clock,
) {
    private val sql = OnboardingSql(TransactionManager.current().connection.connection as Connection)
    private var calendarVersion: Long? = null
    private var timezone: String? = null

    fun lockClub(
        provisionalClubId: Long?,
        command: CanonicalBookingCommand? = null,
    ): Long? {
        val knownClubId = provisionalClubId ?: scope.clubId.takeIf { command != null && receipt(command) != null }
        if (knownClubId != scope.clubId || scope.actor.connectionKey != "platform") return null
        return sql.user(scope.actor.telegramUserId)?.long("id")?.takeIf(::hasGrant)?.let { userId ->
            sql
                .row(
                    "SELECT is_active,timezone,calendar_version FROM clubs WHERE id=?" + sql.updateLock(),
                    scope.clubId,
                )?.takeUnless { it.active() }
                ?.let { club ->
                    calendarVersion = club.long("calendar_version")
                    timezone = club["timezone"]
                    userId
                }
        }
    }

    private fun hasGrant(userId: Long): Boolean =
        // The same client lock used for grant changes precedes the club lock.
        try {
            OnboardingSession(sql, scope.actor, userId, null, clock).requireClubGrant(scope.clubId, WRITE_CALENDAR)
            true
        } catch (_: OnboardingFailure) {
            false
        }

    fun matchingNight(
        start: Instant,
        end: Instant,
        requireVersion: Boolean = true,
    ): LockedCanonicalNight? {
        val currentVersion = checkNotNull(calendarVersion)
        if (requireVersion && scope.calendarVersion != currentVersion) return null
        return sql.lockLinkedCanonicalNight(scope.clubId, scope.eventId, timezone)?.takeIf {
            it.id == scope.nightId && it.legacyStart == start && it.end == end
        }
    }

    /**
     * The command and stored HOLD carry a booking ID. Resolve its unique V072 link,
     * then use that same booking ID and the verified legacy start for the session key.
     */
    fun tableConflict(
        tableId: Long,
        night: LockedCanonicalNight,
    ): BookingCoreError? {
        val table =
            sql.row(
                "SELECT EXISTS(SELECT 1 FROM table_sessions s WHERE s.table_id=t.id " +
                    "AND s.night_start_utc=? AND s.status='OPEN') AS occupied " +
                    "FROM hall_tables ht JOIN halls h ON h.id=ht.hall_id " +
                    "JOIN tables t ON t.id=ht.booking_table_id AND t.club_id=ht.booking_club_id " +
                    "AND t.table_number=ht.table_number AND t.capacity=ht.capacity " +
                    "WHERE h.club_id=? AND t.club_id=h.club_id AND t.id=? " +
                    "AND h.is_active=TRUE AND ht.is_active=TRUE AND t.active=TRUE",
                night.legacyStart,
                scope.clubId,
                tableId,
            ) ?: return BookingCoreError.TableNotFound
        // Also reject a malformed OPEN row for this booking ID and night carrying a foreign club key.
        return BookingCoreError.TableOccupied.takeIf { table.active("occupied") }
    }

    fun receipt(command: CanonicalBookingCommand): CanonicalBookingReceipt? =
        sql
            .row(
                "SELECT request_hash,result_id,hold_expires_at FROM canonical_booking_commands " +
                    "WHERE club_id=? AND operation=? AND key_hash=?",
                scope.clubId,
                command.operation.name,
                command.keyHash,
            )?.let {
                CanonicalBookingReceipt(
                    checkNotNull(it["request_hash"]),
                    UUID.fromString(it["result_id"]),
                    it["hold_expires_at"]?.let { _ -> it.instant("hold_expires_at") },
                )
            }

    fun record(
        command: CanonicalBookingCommand,
        resultId: UUID,
        expiresAt: Instant? = null,
    ) {
        sql.update(
            "INSERT INTO canonical_booking_commands " +
                "(club_id,canonical_night_id,operation,key_hash,request_hash,result_id,hold_expires_at) " +
                "VALUES (?,?,?,?,?,?,?)",
            scope.clubId,
            scope.nightId,
            command.operation.name,
            command.keyHash,
            command.requestHash,
            resultId,
            expiresAt,
        )
    }

    fun ownsHold(id: UUID): Boolean =
        sql.row(
            "SELECT 1 FROM canonical_booking_commands " +
                "WHERE club_id=? AND canonical_night_id=? AND operation='HOLD' AND result_id=?",
            scope.clubId,
            scope.nightId,
            id,
        ) != null

    fun conflict(
        tableId: Long,
        start: Instant,
        end: Instant,
        now: Instant,
        exceptHold: UUID? = null,
    ): BookingCoreError? {
        val bookings =
            sql.row(
                "SELECT 1 FROM bookings WHERE table_id=? AND slot_start<? AND slot_end>? " +
                    "AND status IN ('BOOKED','SEATED') LIMIT 1",
                tableId,
                end,
                start,
            )
        if (bookings != null) return BookingCoreError.DuplicateActiveBooking
        val args = listOf(tableId, end, start, now) + listOfNotNull(exceptHold)
        val holds =
            sql.rowsWithArguments(
                "SELECT 1 FROM booking_holds WHERE table_id=? AND slot_start<? AND slot_end>? AND expires_at>? " +
                    (if (exceptHold == null) "" else "AND id<>? ") + "LIMIT 1",
                args,
            )
        return BookingCoreError.ActiveHoldExists.takeIf { holds.isNotEmpty() }
    }
}

internal fun canonicalStorageKey(): String = "canonical-" + UUID.randomUUID()

internal suspend fun <T> canonicalBookingTransaction(
    database: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    name: String = "booking.canonical",
    block: suspend () -> T,
): T =
    withRetriedTx(name = name, manageTransaction = false) {
        newSuspendedTransaction(
            context = dispatcher,
            db = database,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) { block() }
    }
