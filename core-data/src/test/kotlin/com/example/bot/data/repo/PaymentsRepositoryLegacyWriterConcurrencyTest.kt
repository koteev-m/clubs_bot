package com.example.bot.data.repo

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.PostgresIntegrationTest
import com.example.bot.data.db.Clubs
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentActionsTable
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentRefundsTable
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentsTable
import com.example.bot.payments.PaymentsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

@RequiresDocker
@Suppress("InjectDispatcher", "LargeClass", "NestedBlockDepth")
class PaymentsRepositoryLegacyWriterConcurrencyTest : PostgresIntegrationTest() {
    @Test
    fun `new refund and legacy refund serialize without deadlock`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            seedPayment(booking, 500)
            val key = "old-new-refund-refund"
            val fingerprint = explicitRefund(300)

            val race =
                runLegacyRace(
                    booking = booking,
                    key = key,
                    legacyAction = PaymentsRepository.Action.REFUND,
                    legacyReason = "300",
                ) { repository ->
                    repository.executeRefundIdempotently(booking.clubId, booking.id, key, fingerprint)
                }

            assertEquals(PaymentsRepository.RefundExecution.IdempotencyPayloadMismatch, race.result)
            assertEquals(1, race.typedClaimAttempts)
            assertLegacyRefundPersistence(booking, key)

            val replay =
                PaymentsRepositoryImpl(database)
                    .executeRefundIdempotently(booking.clubId, booking.id, key, fingerprint)
            assertEquals(race.result, replay)
            assertLegacyRefundPersistence(booking, key)
        }

    @Test
    fun `new cancel and legacy cancel serialize without deadlock`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            markLegacyBookingCancelled(booking)
            val cancelledAt = bookingUpdatedAt(booking.id)
            val key = "old-new-cancel-cancel"

            val race =
                runLegacyRace(
                    booking = booking,
                    key = key,
                    legacyAction = PaymentsRepository.Action.CANCEL,
                    legacyReason = "legacy-cancel",
                ) { repository ->
                    repository.executeCancelIdempotently(booking.clubId, booking.id, key, "new-cancel")
                }

            val result = race.result as PaymentsRepository.CancelExecution.Success
            assertTrue(result.idempotent)
            assertFalse(result.alreadyCancelled)
            assertEquals(1, race.typedClaimAttempts)
            assertEquals(BookingStatus.CANCELLED, bookingStatus(booking.id))
            assertEquals(cancelledAt, bookingUpdatedAt(booking.id))
            assertSingleTerminalAction(key, PaymentsRepository.Action.CANCEL)
            assertEquals(0, refundRowsCount(booking.id))

            val replay =
                PaymentsRepositoryImpl(database)
                    .executeCancelIdempotently(booking.clubId, booking.id, key, "another-reason")
            assertEquals(result, replay)
            assertEquals(cancelledAt, bookingUpdatedAt(booking.id))
            assertSingleTerminalAction(key, PaymentsRepository.Action.CANCEL)
        }

    @Test
    fun `new refund and legacy cancel preserve global key winner`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            seedPayment(booking, 500)
            markLegacyBookingCancelled(booking)
            val cancelledAt = bookingUpdatedAt(booking.id)
            val key = "old-new-refund-cancel"
            val fingerprint = explicitRefund(300)

            val race =
                runLegacyRace(
                    booking = booking,
                    key = key,
                    legacyAction = PaymentsRepository.Action.CANCEL,
                    legacyReason = "legacy-cancel",
                ) { repository ->
                    repository.executeRefundIdempotently(booking.clubId, booking.id, key, fingerprint)
                }

            assertEquals(PaymentsRepository.RefundExecution.IdempotencyBindingMismatch, race.result)
            assertEquals(1, race.typedClaimAttempts)
            assertEquals(BookingStatus.CANCELLED, bookingStatus(booking.id))
            assertEquals(cancelledAt, bookingUpdatedAt(booking.id))
            assertSingleTerminalAction(key, PaymentsRepository.Action.CANCEL)
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))

            val replay =
                PaymentsRepositoryImpl(database)
                    .executeRefundIdempotently(booking.clubId, booking.id, key, fingerprint)
            assertEquals(race.result, replay)
            assertEquals(0, refundRowsCount(booking.id))
        }

    @Test
    fun `new cancel and legacy refund preserve global key winner`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            seedPayment(booking, 500)
            val bookedAt = bookingUpdatedAt(booking.id)
            val key = "old-new-cancel-refund"

            val race =
                runLegacyRace(
                    booking = booking,
                    key = key,
                    legacyAction = PaymentsRepository.Action.REFUND,
                    legacyReason = "300",
                ) { repository ->
                    repository.executeCancelIdempotently(booking.clubId, booking.id, key, "new-cancel")
                }

            assertEquals(PaymentsRepository.CancelExecution.IdempotencyBindingMismatch, race.result)
            assertEquals(1, race.typedClaimAttempts)
            assertEquals(BookingStatus.BOOKED, bookingStatus(booking.id))
            assertEquals(bookedAt, bookingUpdatedAt(booking.id))
            assertLegacyRefundPersistence(booking, key)

            val replay =
                PaymentsRepositoryImpl(database)
                    .executeCancelIdempotently(booking.clubId, booking.id, key, "another-reason")
            assertEquals(race.result, replay)
            assertEquals(BookingStatus.BOOKED, bookingStatus(booking.id))
            assertLegacyRefundPersistence(booking, key)
        }

    @Test
    fun `atomic refund retries one deadlock as a whole transaction`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            seedPayment(booking, 500)
            val key = "atomic-refund-deadlock-retry"
            val fixture = installOneShotSqlFailure(key, "40P01")

            try {
                val result =
                    PaymentsRepositoryImpl(database)
                        .executeRefundIdempotently(booking.clubId, booking.id, key, explicitRefund(300))

                assertEquals(
                    PaymentsRepository.RefundExecution.Success(300, 200, idempotent = false),
                    result,
                )
                assertEquals(2, sequenceCalls(fixture.sequenceName))
                assertSingleTerminalAction(key, PaymentsRepository.Action.REFUND)
                assertEquals(1, refundRowsCount(booking.id))
                assertEquals(300, refundedTotal(booking.id))
                assertEquals(200, remainingAmount(booking.id))
            } finally {
                dropSqlFixture(fixture)
            }
        }

    @Test
    fun `atomic cancel retries one serialization failure as a whole transaction`() =
        runBlocking {
            val booking = seedBooking(BookingStatus.BOOKED)
            val key = "atomic-cancel-serialization-retry"
            val fixture = installOneShotSqlFailure(key, "40001")

            try {
                val result =
                    PaymentsRepositoryImpl(database)
                        .executeCancelIdempotently(booking.clubId, booking.id, key, "retry")

                assertTrue(result is PaymentsRepository.CancelExecution.Success)
                assertFalse((result as PaymentsRepository.CancelExecution.Success).idempotent)
                assertEquals(2, sequenceCalls(fixture.sequenceName))
                assertSingleTerminalAction(key, PaymentsRepository.Action.CANCEL)
                assertEquals(BookingStatus.CANCELLED, bookingStatus(booking.id))
            } finally {
                dropSqlFixture(fixture)
            }
        }

    private suspend fun <T> runLegacyRace(
        booking: BookingSeed,
        key: String,
        legacyAction: PaymentsRepository.Action,
        legacyReason: String?,
        newOperation: suspend (PaymentsRepositoryImpl) -> T,
    ): LegacyRaceResult<T> =
        supervisorScope {
            val fixture = installBlockingNewClaimFixture()
            val newApplicationName = "new-${UUID.randomUUID()}"
            val legacyApplicationName = "old-${UUID.randomUUID()}"
            val newRepository = PaymentsRepositoryImpl(connectToPostgres(newApplicationName))
            val controlConnection = directConnection("control-${UUID.randomUUID()}")
            val legacyCommitGate = CompletableDeferred<Unit>()
            val legacyInserted = CompletableDeferred<Int>()
            var controlLockHeld = false
            var newJob: Deferred<T>? = null
            var legacyJob: Deferred<Int>? = null

            try {
                acquireAdvisoryLock(controlConnection, fixture.lockNamespace, fixture.lockKey)
                controlLockHeld = true

                val startedNew = async(Dispatchers.IO) { newOperation(newRepository) }
                newJob = startedNew
                awaitAdvisoryWait(newApplicationName, fixture.lockNamespace, fixture.lockKey)
                assertEquals(1, sequenceCalls(fixture.sequenceName))
                assertBookingLockContract(booking.id)

                val startedLegacy =
                    async(Dispatchers.IO) {
                        directConnection(legacyApplicationName).use { connection ->
                            connection.autoCommit = false
                            try {
                                val inserted =
                                    insertLegacyAction(
                                        connection = connection,
                                        bookingId = booking.id,
                                        key = key,
                                        action = legacyAction,
                                        reason = legacyReason,
                                    )
                                legacyInserted.complete(inserted)
                                legacyCommitGate.await()
                                connection.commit()
                                inserted
                            } catch (error: Throwable) {
                                connection.rollback()
                                legacyInserted.completeExceptionally(error)
                                throw error
                            }
                        }
                    }
                legacyJob = startedLegacy

                assertEquals(1, withTimeout(WAIT_TIMEOUT_MS) { legacyInserted.await() })
                awaitLegacyTransactionReady(legacyApplicationName)

                releaseAdvisoryLock(controlConnection, fixture.lockNamespace, fixture.lockKey)
                controlLockHeld = false
                awaitUniqueTupleWait(newApplicationName, legacyApplicationName)

                legacyCommitGate.complete(Unit)
                assertEquals(1, withTimeout(WAIT_TIMEOUT_MS) { startedLegacy.await() })
                val result = withTimeout(WAIT_TIMEOUT_MS) { startedNew.await() }
                val attempts = sequenceCalls(fixture.sequenceName)
                assertEquals(1, attempts, "primary lock regression must not be rescued by retry")

                LegacyRaceResult(result = result, typedClaimAttempts = attempts)
            } finally {
                if (controlLockHeld) {
                    releaseAdvisoryLock(controlConnection, fixture.lockNamespace, fixture.lockKey)
                }
                legacyCommitGate.complete(Unit)
                newJob?.cancel()
                legacyJob?.cancel()
                withTimeoutOrNull(2_000L) { newJob?.join() }
                withTimeoutOrNull(2_000L) { legacyJob?.join() }
                controlConnection.close()
                dropSqlFixture(fixture)
            }
        }

    private fun installBlockingNewClaimFixture(): SqlFixture {
        val fixture = SqlFixture.random("old_new", sqlState = null)
        transaction(database) {
            exec("CREATE SEQUENCE ${fixture.sequenceName}")
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    PERFORM nextval('${fixture.sequenceName}');
                    PERFORM pg_advisory_xact_lock(${fixture.lockNamespace}, ${fixture.lockKey});
                    RETURN NEW;
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                BEFORE INSERT ON payment_actions
                FOR EACH ROW
                WHEN (NEW.status = 'PROCESSING')
                EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun installOneShotSqlFailure(
        key: String,
        sqlState: String,
    ): SqlFixture {
        val fixture = SqlFixture.random("retry", sqlState)
        transaction(database) {
            exec("CREATE SEQUENCE ${fixture.sequenceName}")
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                DECLARE
                    attempt bigint;
                BEGIN
                    attempt := nextval('${fixture.sequenceName}');
                    IF attempt = 1 THEN
                        RAISE EXCEPTION 'controlled transient transaction failure'
                            USING ERRCODE = '${fixture.sqlState}';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                BEFORE INSERT ON payment_actions
                FOR EACH ROW
                WHEN (
                    NEW.status = 'PROCESSING'
                    AND NEW.idempotency_key = '$key'
                )
                EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun dropSqlFixture(fixture: SqlFixture) {
        transaction(database) {
            exec("DROP TRIGGER IF EXISTS ${fixture.triggerName} ON payment_actions")
            exec("DROP FUNCTION IF EXISTS ${fixture.functionName}()")
            exec("DROP SEQUENCE IF EXISTS ${fixture.sequenceName}")
        }
    }

    private fun assertBookingLockContract(bookingId: UUID) {
        assertLockUnavailable(bookingId, "FOR NO KEY UPDATE NOWAIT")
        assertLockAvailable(bookingId, "FOR KEY SHARE NOWAIT")
    }

    private fun assertLockUnavailable(
        bookingId: UUID,
        lockClause: String,
    ) {
        directConnection("probe-exclusive-${UUID.randomUUID()}").use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM bookings WHERE id = '$bookingId' $lockClause")
                }
                fail("expected $lockClause to conflict with the atomic booking lock")
            } catch (error: SQLException) {
                assertEquals(LOCK_NOT_AVAILABLE_SQLSTATE, error.sqlState)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun assertLockAvailable(
        bookingId: UUID,
        lockClause: String,
    ) {
        directConnection("probe-key-share-${UUID.randomUUID()}").use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT id FROM bookings WHERE id = '$bookingId' $lockClause")
                        .use { result ->
                            assertTrue(result.next())
                        }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    private fun insertLegacyAction(
        connection: Connection,
        bookingId: UUID,
        key: String,
        action: PaymentsRepository.Action,
        reason: String?,
    ): Int =
        connection
            .prepareStatement(
                """
                INSERT INTO payment_actions (
                    booking_id,
                    idempotency_key,
                    action,
                    status,
                    reason
                ) VALUES (?, ?, ?, 'OK', ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.queryTimeout = 15
                statement.setObject(1, bookingId)
                statement.setString(2, key)
                statement.setString(3, action.name)
                statement.setString(4, reason)
                statement.executeUpdate()
            }

    private suspend fun awaitAdvisoryWait(
        applicationName: String,
        lockNamespace: Int,
        lockKey: Int,
    ) {
        awaitDatabaseState {
            directConnection("observe-advisory-${UUID.randomUUID()}").use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity activity
                            JOIN pg_locks waiting_lock ON waiting_lock.pid = activity.pid
                            WHERE activity.application_name = ?
                              AND waiting_lock.locktype = 'advisory'
                              AND waiting_lock.classid::bigint = ?
                              AND waiting_lock.objid::bigint = ?
                              AND NOT waiting_lock.granted
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, applicationName)
                        statement.setLong(2, lockNamespace.toLong())
                        statement.setLong(3, lockKey.toLong())
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getBoolean(1)
                        }
                    }
            }
        }
    }

    private suspend fun awaitLegacyTransactionReady(applicationName: String) {
        awaitDatabaseState {
            directConnection("observe-legacy-${UUID.randomUUID()}").use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE application_name = ?
                              AND state = 'idle in transaction'
                              AND backend_xid IS NOT NULL
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, applicationName)
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getBoolean(1)
                        }
                    }
            }
        }
    }

    private suspend fun awaitUniqueTupleWait(
        newApplicationName: String,
        legacyApplicationName: String,
    ) {
        awaitDatabaseState {
            directConnection("observe-unique-${UUID.randomUUID()}").use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity waiter
                            JOIN pg_locks waiting_lock
                              ON waiting_lock.pid = waiter.pid
                             AND waiting_lock.locktype = 'transactionid'
                             AND NOT waiting_lock.granted
                            JOIN LATERAL unnest(pg_blocking_pids(waiter.pid)) blocker_pid(pid) ON TRUE
                            JOIN pg_stat_activity blocker ON blocker.pid = blocker_pid.pid
                            WHERE waiter.application_name = ?
                              AND blocker.application_name = ?
                              AND waiter.wait_event_type = 'Lock'
                              AND waiter.wait_event = 'transactionid'
                              AND blocker.backend_xid IS NOT NULL
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, newApplicationName)
                        statement.setString(2, legacyApplicationName)
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getBoolean(1)
                        }
                    }
            }
        }
    }

    private suspend fun awaitDatabaseState(predicate: () -> Boolean) {
        withTimeout(WAIT_TIMEOUT_MS) {
            while (!predicate()) {
                yield()
            }
        }
    }

    private fun acquireAdvisoryLock(
        connection: Connection,
        lockNamespace: Int,
        lockKey: Int,
    ) {
        connection.prepareStatement("SELECT pg_advisory_lock(?, ?)").use { statement ->
            statement.setInt(1, lockNamespace)
            statement.setInt(2, lockKey)
            statement.execute()
        }
    }

    private fun releaseAdvisoryLock(
        connection: Connection,
        lockNamespace: Int,
        lockKey: Int,
    ) {
        connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)").use { statement ->
            statement.setInt(1, lockNamespace)
            statement.setInt(2, lockKey)
            statement.execute()
        }
    }

    private fun sequenceCalls(sequenceName: String): Long =
        transaction(database) {
            exec("SELECT last_value FROM $sequenceName") { result ->
                check(result.next())
                result.getLong(1)
            } ?: error("Failed to read test sequence")
        }

    private fun connectToPostgres(applicationName: String): Database {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return Database.connect(
            url = "${postgres.jdbcUrl}$separator" + "ApplicationName=$applicationName",
            driver = postgres.driverClassName,
            user = postgres.username,
            password = postgres.password,
        )
    }

    private fun directConnection(applicationName: String): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            Properties().apply {
                setProperty("user", postgres.username)
                setProperty("password", postgres.password)
                setProperty("ApplicationName", applicationName)
            },
        )

    private fun seedBooking(status: BookingStatus): BookingSeed {
        val id = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString()
        val slotStart = OffsetDateTime.ofInstant(Instant.parse("2025-01-01T20:00:00Z"), ZoneOffset.UTC)
        val slotEnd = slotStart.plusHours(4)
        var clubId = 0L
        transaction(database) {
            val insertedClubId =
                Clubs.insert {
                    it[name] = "Deadlock fixture $suffix"
                    it[description] = "fixture"
                    it[timezone] = "UTC"
                } get Clubs.id
            clubId = insertedClubId.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubId
                    it[zoneId] = null
                    it[tableNumber] = 1
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id
            val eventId =
                EventsTable.insert {
                    it[EventsTable.clubId] = clubId
                    it[startAt] = slotStart
                    it[endAt] = slotEnd
                    it[title] = "Deadlock fixture"
                    it[isSpecial] = false
                    it[posterUrl] = null
                } get EventsTable.id
            BookingsTable.insert {
                it[BookingsTable.id] = id
                it[BookingsTable.eventId] = eventId
                it[BookingsTable.clubId] = clubId
                it[BookingsTable.tableId] = tableId
                it[BookingsTable.tableNumber] = 1
                it[BookingsTable.guestUserId] = null
                it[BookingsTable.guestName] = "Guest"
                it[BookingsTable.phoneE164] = null
                it[BookingsTable.promoterUserId] = null
                it[BookingsTable.guestsCount] = 2
                it[BookingsTable.minDeposit] = BigDecimal("100.00")
                it[BookingsTable.totalDeposit] = BigDecimal("200.00")
                it[BookingsTable.slotStart] = slotStart
                it[BookingsTable.slotEnd] = slotEnd
                it[BookingsTable.arrivalBy] = slotStart
                it[BookingsTable.status] = status.name
                it[BookingsTable.qrSecret] = "qr-$suffix"
                it[BookingsTable.idempotencyKey] = "booking-$suffix"
                it[BookingsTable.createdAt] = slotStart
                it[BookingsTable.updatedAt] = slotStart
            }
        }
        return BookingSeed(id = id, clubId = clubId)
    }

    private fun seedPayment(
        booking: BookingSeed,
        amountMinor: Long,
    ) {
        val suffix = UUID.randomUUID().toString()
        transaction(database) {
            PaymentsTable.insert {
                it[id] = UUID.randomUUID()
                it[bookingId] = booking.id
                it[provider] = "TEST"
                it[currency] = "RUB"
                it[PaymentsTable.amountMinor] = amountMinor
                it[status] = "CAPTURED"
                it[payload] = "payload-$suffix"
                it[idempotencyKey] = "payment-$suffix"
            }
        }
    }

    private fun markLegacyBookingCancelled(booking: BookingSeed) {
        transaction(database) {
            val updated =
                BookingsTable.update({ BookingsTable.id eq booking.id }) {
                    it[status] = BookingStatus.CANCELLED.name
                    it[updatedAt] = OffsetDateTime.parse("2025-01-01T21:00:00Z")
                }
            assertEquals(1, updated)
        }
    }

    private fun bookingStatus(bookingId: UUID): BookingStatus =
        BookingStatus.valueOf(bookingRow(bookingId)[BookingsTable.status])

    private fun bookingUpdatedAt(bookingId: UUID): OffsetDateTime = bookingRow(bookingId)[BookingsTable.updatedAt]

    private fun bookingRow(bookingId: UUID): ResultRow =
        transaction(database) {
            BookingsTable
                .selectAll()
                .where { BookingsTable.id eq bookingId }
                .single()
        }

    private fun assertSingleTerminalAction(
        key: String,
        action: PaymentsRepository.Action,
    ) {
        transaction(database) {
            val rows =
                PaymentActionsTable
                    .selectAll()
                    .where { PaymentActionsTable.idempotencyKey eq key }
                    .toList()
            assertEquals(1, rows.size)
            assertEquals(action.name, rows.single()[PaymentActionsTable.action])
            assertEquals(PaymentsRepository.Result.Status.OK.name, rows.single()[PaymentActionsTable.status])
            assertFalse(rows.single()[PaymentActionsTable.status] == PROCESSING_STATUS)
        }
    }

    private fun assertLegacyRefundPersistence(
        booking: BookingSeed,
        key: String,
    ) {
        assertSingleTerminalAction(key, PaymentsRepository.Action.REFUND)
        transaction(database) {
            val action =
                PaymentActionsTable
                    .selectAll()
                    .where { PaymentActionsTable.idempotencyKey eq key }
                    .single()
            assertEquals(null, action[PaymentActionsTable.refundFingerprintVersion])
            assertEquals(null, action[PaymentActionsTable.refundRequestMode])
            assertEquals(null, action[PaymentActionsTable.refundRequestAmountMinor])
            assertEquals(300, action[PaymentActionsTable.refundResultAmountMinor])
            assertEquals(
                PaymentsRepository.RefundSourceKind.LEGACY_ACTION.name,
                action[PaymentActionsTable.refundSourceKind],
            )
        }
        assertEquals(1, refundRowsCount(booking.id))
        assertEquals(300, refundedTotal(booking.id))
        assertEquals(200, remainingAmount(booking.id))
    }

    private fun refundRowsCount(bookingId: UUID): Long =
        transaction(database) {
            PaymentRefundsTable
                .selectAll()
                .where { PaymentRefundsTable.bookingId eq bookingId }
                .count()
        }

    private fun refundedTotal(bookingId: UUID): Long =
        transaction(database) {
            PaymentRefundsTable
                .selectAll()
                .where { PaymentRefundsTable.bookingId eq bookingId }
                .sumOf { row -> row[PaymentRefundsTable.amountMinor] }
        }

    private fun capturedTotal(bookingId: UUID): Long =
        transaction(database) {
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.bookingId eq bookingId }
                .filter { row -> row[PaymentsTable.status] in setOf("CAPTURED", "REFUNDED") }
                .sumOf { row -> row[PaymentsTable.amountMinor] }
        }

    private fun remainingAmount(bookingId: UUID): Long = capturedTotal(bookingId) - refundedTotal(bookingId)

    private fun explicitRefund(amountMinor: Long): PaymentsRepository.RefundFingerprint =
        PaymentsRepository.RefundFingerprint(
            mode = PaymentsRepository.RefundRequestMode.EXPLICIT,
            requestAmountMinor = amountMinor,
        )

    private data class BookingSeed(
        val id: UUID,
        val clubId: Long,
    )

    private data class LegacyRaceResult<T>(
        val result: T,
        val typedClaimAttempts: Long,
    )

    private data class SqlFixture(
        val functionName: String,
        val triggerName: String,
        val sequenceName: String,
        val lockNamespace: Int,
        val lockKey: Int,
        val sqlState: String?,
    ) {
        companion object {
            fun random(
                prefix: String,
                sqlState: String?,
            ): SqlFixture {
                val suffix = UUID.randomUUID().toString().replace("-", "")
                val lockNamespace = UUID.randomUUID().hashCode() and Int.MAX_VALUE
                val lockKey = UUID.randomUUID().hashCode() and Int.MAX_VALUE
                return SqlFixture(
                    functionName = "test_${prefix}_fn_$suffix",
                    triggerName = "test_${prefix}_trg_$suffix",
                    sequenceName = "test_${prefix}_seq_$suffix",
                    lockNamespace = lockNamespace,
                    lockKey = lockKey,
                    sqlState = sqlState,
                )
            }
        }
    }

    private companion object {
        private const val WAIT_TIMEOUT_MS = 15_000L
        private const val LOCK_NOT_AVAILABLE_SQLSTATE = "55P03"
        private const val PROCESSING_STATUS = "PROCESSING"
    }
}
