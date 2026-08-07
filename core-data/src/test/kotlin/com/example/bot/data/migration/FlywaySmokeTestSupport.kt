package com.example.bot.data.migration

import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.payments.PaymentsRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

internal fun migrateAndTrack(
    jdbcUrl: String,
    user: String,
    password: String,
    driver: String,
    vendor: String,
    resourcesToClose: MutableList<AutoCloseable>,
) {
    val flyway =
        Flyway
            .configure()
            .dataSource(jdbcUrl, user, password)
            .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
            .cleanDisabled(false)
            .load()
    flyway.clean()
    flyway.migrate()

    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                driverClassName = driver
                maximumPoolSize = 2
            },
        )
    resourcesToClose += dataSource
}

internal fun migrateLegacyRefundUpgradeAndTrack(
    jdbcUrl: String,
    user: String,
    password: String,
    driver: String,
    vendor: String,
    legacyVersion: String,
    resourcesToClose: MutableList<AutoCloseable>,
) {
    val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/$vendor")
    val legacyFlyway =
        Flyway
            .configure()
            .dataSource(jdbcUrl, user, password)
            .locations(*locations)
            .target(legacyVersion)
            .cleanDisabled(false)
            .load()
    legacyFlyway.clean()
    legacyFlyway.migrate()

    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                driverClassName = driver
                maximumPoolSize = 2
            },
        )
    resourcesToClose += dataSource

    val fixture = dataSource.connection.use(::insertLegacyRefundFixture)

    Flyway
        .configure()
        .dataSource(jdbcUrl, user, password)
        .locations(*locations)
        .load()
        .migrate()

    dataSource.connection.use { connection ->
        assertLegacyRefundFixture(connection, fixture)
    }
    assertBlockedUpgradeRefundRuntime(dataSource, fixture)
}

private fun assertBlockedUpgradeRefundRuntime(
    dataSource: HikariDataSource,
    fixture: LegacyRefundFixture,
) {
    val repository = PaymentsRepositoryImpl(Database.connect(dataSource))
    val blockedBookings =
        listOf(
            "malformed" to fixture.malformedBookingId,
            "zero" to fixture.zeroBookingId,
            "negative" to fixture.negativeBookingId,
            "ambiguous" to fixture.ambiguousBookingId,
        )
    runBlocking {
        blockedBookings.forEach { (label, bookingId) ->
            val key = "upgrade-blocked-$label-${UUID.randomUUID()}"
            val before = dataSource.connection.use { connection -> refundLedgerSnapshot(connection, bookingId) }
            val clubId = dataSource.connection.use { connection -> bookingClubId(connection, bookingId) }
            val fingerprint =
                PaymentsRepository.RefundFingerprint(
                    mode = PaymentsRepository.RefundRequestMode.EXPLICIT,
                    requestAmountMinor = 1L,
                )

            val first = repository.executeRefundIdempotently(clubId, bookingId, key, fingerprint)
            check(first == PaymentsRepository.RefundExecution.Conflict("reconciliation_required", false))
            val replay = repository.executeRefundIdempotently(clubId, bookingId, key, fingerprint)
            check(replay == PaymentsRepository.RefundExecution.Conflict("reconciliation_required", true))

            val after = dataSource.connection.use { connection -> refundLedgerSnapshot(connection, bookingId) }
            check(after == before) { "Blocked upgrade refund mutated ledger for $label booking" }
            dataSource.connection.use { connection ->
                assertStoredRefundConflict(connection, key, bookingId, "reconciliation_required")
            }
        }
    }
}

private fun bookingClubId(
    connection: Connection,
    bookingId: UUID,
): Long =
    connection.prepareStatement("SELECT club_id FROM bookings WHERE id = ?").use { statement ->
        statement.setObject(1, bookingId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Upgrade fixture booking is missing" }
            resultSet.getLong(1)
        }
    }

private fun refundLedgerSnapshot(
    connection: Connection,
    bookingId: UUID,
): Pair<Long, Long> =
    connection
        .prepareStatement(
            "SELECT COUNT(*), COALESCE(SUM(amount_minor), 0) FROM payment_refunds WHERE booking_id = ?",
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1) to resultSet.getLong(2)
            }
        }

private fun assertStoredRefundConflict(
    connection: Connection,
    key: String,
    bookingId: UUID,
    reason: String,
) {
    connection
        .prepareStatement(
            """
            SELECT COUNT(*)
            FROM payment_actions
            WHERE idempotency_key = ?
              AND booking_id = ?
              AND action = 'REFUND'
              AND status = 'CONFLICT'
              AND reason = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, key)
            statement.setObject(2, bookingId)
            statement.setString(3, reason)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                check(resultSet.getLong(1) == 1L) { "Blocked refund terminal result was not stored exactly once" }
            }
        }
}

private fun insertLegacyRefundFixture(connection: Connection): LegacyRefundFixture {
    val numericBookingId = insertLegacyBooking(connection, "numeric")
    val malformedBookingId = insertLegacyBooking(connection, "malformed")
    val zeroBookingId = insertLegacyBooking(connection, "zero")
    val negativeBookingId = insertLegacyBooking(connection, "negative")
    val errorBookingId = insertLegacyBooking(connection, "error")
    val conflictBookingId = insertLegacyBooking(connection, "conflict")
    val cancelBookingId = insertLegacyBooking(connection, "cancel")
    val refundedPaymentBookingId = insertLegacyBooking(connection, "payment")
    val ambiguousBookingId = insertLegacyBooking(connection, "ambiguous")
    val multiplePaymentsBookingId = insertLegacyBooking(connection, "multiple-payments")
    val numericKey = insertLegacyAction(connection, numericBookingId, "REFUND", "OK", "375")
    val malformedKey = insertLegacyAction(connection, malformedBookingId, "REFUND", "OK", "not-a-number")
    val zeroKey = insertLegacyAction(connection, zeroBookingId, "REFUND", "OK", "0")
    val negativeKey = insertLegacyAction(connection, negativeBookingId, "REFUND", "OK", "-1")
    val errorKey = insertLegacyAction(connection, errorBookingId, "REFUND", "ERROR", "exceeds remainder")
    val conflictKey = insertLegacyAction(connection, conflictBookingId, "REFUND", "CONFLICT", "nothing to refund")
    val cancelKey = insertLegacyAction(connection, cancelBookingId, "CANCEL", "OK", "guest request")
    val ambiguousActionKey = insertLegacyAction(connection, ambiguousBookingId, "REFUND", "OK", "125")
    val refundedPaymentId = insertLegacyPayment(connection, refundedPaymentBookingId, 400L, "REFUNDED")
    val ambiguousPaymentId = insertLegacyPayment(connection, ambiguousBookingId, 500L, "REFUNDED")
    val multiplePaymentIds =
        listOf(
            insertLegacyPayment(connection, multiplePaymentsBookingId, 100L, "REFUNDED"),
            insertLegacyPayment(connection, multiplePaymentsBookingId, 200L, "REFUNDED"),
        )

    return LegacyRefundFixture(
        numericBookingId = numericBookingId,
        numericKey = numericKey,
        malformedBookingId = malformedBookingId,
        malformedKey = malformedKey,
        zeroBookingId = zeroBookingId,
        zeroKey = zeroKey,
        negativeBookingId = negativeBookingId,
        negativeKey = negativeKey,
        errorKey = errorKey,
        conflictKey = conflictKey,
        cancelKey = cancelKey,
        refundedPaymentBookingId = refundedPaymentBookingId,
        refundedPaymentId = refundedPaymentId,
        ambiguousBookingId = ambiguousBookingId,
        ambiguousActionKey = ambiguousActionKey,
        ambiguousPaymentId = ambiguousPaymentId,
        multiplePaymentsBookingId = multiplePaymentsBookingId,
        multiplePaymentIds = multiplePaymentIds,
    )
}

private fun insertLegacyBooking(
    connection: Connection,
    label: String,
): UUID {
    val bookingId = UUID.randomUUID()
    connection
        .prepareStatement(
            """
            INSERT INTO bookings (
                id, event_id, club_id, table_id, table_number, guests_count,
                min_deposit, total_deposit, status, qr_secret, idempotency_key,
                slot_start, slot_end
            )
            SELECT ?, e.id, e.club_id, t.id, t.table_number, 1,
                   0, 0, 'CANCELLED', ?, ?, e.start_at, e.end_at
            FROM events e
            JOIN tables t ON t.club_id = e.club_id
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, "lr-${UUID.randomUUID()}")
            statement.setString(3, "legacy-refund-$label-booking-${UUID.randomUUID()}")
            check(statement.executeUpdate() == 1) { "Legacy refund booking was not inserted" }
        }
    return bookingId
}

private fun insertLegacyAction(
    connection: Connection,
    bookingId: UUID,
    action: String,
    status: String,
    reason: String,
): String {
    val key = "legacy-${action.lowercase()}-${status.lowercase()}-${UUID.randomUUID()}"
    connection
        .prepareStatement(
            """
            INSERT INTO payment_actions (booking_id, idempotency_key, action, status, reason)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, key)
            statement.setString(3, action)
            statement.setString(4, status)
            statement.setString(5, reason)
            check(statement.executeUpdate() == 1) { "Legacy payment action was not inserted" }
        }
    return key
}

private fun insertLegacyPayment(
    connection: Connection,
    bookingId: UUID,
    amountMinor: Long,
    status: String,
): UUID {
    val paymentId = UUID.randomUUID()
    val suffix = UUID.randomUUID()
    connection
        .prepareStatement(
            """
            INSERT INTO payments (
                id, booking_id, provider, currency, amount_minor, status, payload, idempotency_key
            ) VALUES (?, ?, 'legacy-test', 'RUB', ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.setObject(2, bookingId)
            statement.setLong(3, amountMinor)
            statement.setString(4, status)
            statement.setString(5, "legacy-refund-payload-$suffix")
            statement.setString(6, "legacy-refund-payment-$suffix")
            check(statement.executeUpdate() == 1) { "Legacy payment was not inserted" }
        }
    return paymentId
}

private fun assertLegacyRefundFixture(
    connection: Connection,
    fixture: LegacyRefundFixture,
) {
    val actions = loadLegacyRefundActions(connection)
    check(actions.size >= 6) { "Legacy payment actions were not preserved" }
    val successful = checkNotNull(actions[fixture.numericKey])
    check(successful.status == "OK" && successful.reason == "375")
    check(successful.hasNoRequestFingerprint()) { "Legacy success gained an invented request fingerprint" }
    check(successful.resultAmountMinor == 375L)
    check(successful.sourceKind == "LEGACY_ACTION")
    val malformed = checkNotNull(actions[fixture.malformedKey])
    check(malformed.status == "OK" && malformed.reason == "not-a-number")
    check(malformed.hasNoRequestFingerprint())
    check(malformed.resultAmountMinor == null && malformed.sourceKind == null)
    assertBlocked(connection, fixture.malformedBookingId, "MALFORMED_LEGACY_REFUND_ACTION")
    assertNoLegacyRefundBackfill(connection, malformed.id)

    listOf(
        Triple(fixture.zeroKey, fixture.zeroBookingId, "0"),
        Triple(fixture.negativeKey, fixture.negativeBookingId, "-1"),
    ).forEach { (key, bookingId, reason) ->
        val nonPositive = checkNotNull(actions[key])
        check(nonPositive.status == "OK" && nonPositive.reason == reason)
        check(nonPositive.hasNoRequestFingerprint())
        check(nonPositive.resultAmountMinor == null && nonPositive.sourceKind == null)
        assertBlocked(connection, bookingId, "MALFORMED_LEGACY_REFUND_ACTION")
        assertNoLegacyRefundBackfill(connection, nonPositive.id)
    }

    val error = checkNotNull(actions[fixture.errorKey])
    check(error.status == "ERROR" && error.reason == "exceeds remainder")
    check(error.hasNoRequestFingerprint()) { "Legacy error gained an invented request fingerprint" }
    check(error.resultAmountMinor == null && error.sourceKind == null)
    val conflict = checkNotNull(actions[fixture.conflictKey])
    check(conflict.status == "CONFLICT" && conflict.reason == "nothing to refund")
    check(conflict.resultAmountMinor == null && conflict.sourceKind == null)
    val cancel = checkNotNull(actions[fixture.cancelKey])
    check(cancel.action == "CANCEL" && cancel.status == "OK" && cancel.reason == "guest request")
    check(cancel.resultAmountMinor == null && cancel.sourceKind == null)

    assertLegacyRefundBackfill(connection, successful.id, expectedAmountMinor = 375L)
    assertNoLegacyRefundBackfill(connection, error.id)
    assertNoLegacyRefundBackfill(connection, conflict.id)
    assertNoLegacyRefundBackfill(connection, cancel.id)
    assertPaymentStatusRefundBackfill(
        connection,
        fixture.refundedPaymentBookingId,
        fixture.refundedPaymentId,
        400L,
    )
    val ambiguousAction = checkNotNull(actions[fixture.ambiguousActionKey])
    assertLegacyRefundBackfill(connection, ambiguousAction.id, expectedAmountMinor = 125L)
    assertPaymentStatusRefundBackfill(
        connection,
        fixture.ambiguousBookingId,
        fixture.ambiguousPaymentId,
        500L,
    )
    assertBlocked(connection, fixture.ambiguousBookingId, "AMBIGUOUS_LEGACY_REFUND_SOURCES")
    fixture.multiplePaymentIds.zip(listOf(100L, 200L)).forEach { (paymentId, amountMinor) ->
        assertPaymentStatusRefundBackfill(
            connection,
            fixture.multiplePaymentsBookingId,
            paymentId,
            amountMinor,
        )
    }
    assertNotBlocked(connection, fixture.multiplePaymentsBookingId)
    assertLedgerAggregate(connection, fixture.multiplePaymentsBookingId, expectedRows = 2L, expectedAmount = 300L)
    assertInvalidRefundFingerprintsRejected(connection, fixture.numericBookingId)
    assertAtomicRefundIndexes(connection)
}

private fun loadLegacyRefundActions(connection: Connection): Map<String, LegacyRefundAction> =
    connection
        .prepareStatement(
            """
            SELECT id, idempotency_key, action, status, reason, refund_fingerprint_version, refund_request_mode,
                   refund_request_amount_minor, refund_result_amount_minor, refund_source_kind
            FROM payment_actions
            ORDER BY idempotency_key
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                readLegacyRefundActions(resultSet)
            }
        }

private fun readLegacyRefundActions(resultSet: ResultSet): Map<String, LegacyRefundAction> =
    buildMap {
        while (resultSet.next()) {
            put(
                resultSet.getString("idempotency_key"),
                LegacyRefundAction(
                    id = resultSet.getLong("id"),
                    action = resultSet.getString("action"),
                    status = resultSet.getString("status"),
                    reason = resultSet.getString("reason"),
                    fingerprintVersion = resultSet.getObject("refund_fingerprint_version"),
                    requestMode = resultSet.getObject("refund_request_mode"),
                    requestAmountMinor = resultSet.getObject("refund_request_amount_minor"),
                    resultAmountMinor = resultSet.getObject("refund_result_amount_minor") as? Long,
                    sourceKind = resultSet.getString("refund_source_kind"),
                ),
            )
        }
    }

private fun assertInvalidRefundFingerprintsRejected(
    connection: Connection,
    bookingId: UUID,
) {
    assertThrows<SQLException> {
        connection
            .prepareStatement(
                """
                INSERT INTO payment_actions (
                    booking_id, idempotency_key, action, status,
                    refund_fingerprint_version, refund_request_mode, refund_request_amount_minor
                )
                VALUES (?, ?, 'REFUND', 'PROCESSING', 1, NULL, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, bookingId)
                statement.setString(2, "invalid-null-mode-${UUID.randomUUID()}")
                statement.executeUpdate()
            }
    }
    assertThrows<SQLException> {
        connection
            .prepareStatement(
                """
                INSERT INTO payment_actions (
                    booking_id, idempotency_key, action, status, reason,
                    refund_fingerprint_version, refund_request_mode,
                    refund_request_amount_minor, refund_result_amount_minor
                )
                VALUES (?, ?, 'REFUND', 'OK', '100', 1, 'EXPLICIT', 100, 100)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, bookingId)
                statement.setString(2, "invalid-missing-source-${UUID.randomUUID()}")
                statement.executeUpdate()
            }
    }
    assertThrows<SQLException> {
        connection
            .prepareStatement(
                """
                INSERT INTO payment_actions (
                    booking_id, idempotency_key, action, status,
                    refund_fingerprint_version, refund_request_mode, refund_request_amount_minor
                )
                VALUES (?, ?, 'REFUND', 'PROCESSING', 1, 'EXPLICIT', -1)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, bookingId)
                statement.setString(2, "invalid-negative-amount-${UUID.randomUUID()}")
                statement.executeUpdate()
            }
    }
    assertTypedRefundActionRejected(
        connection = connection,
        bookingId = bookingId,
        resultAmountMinor = null,
        sourceKind = null,
        label = "null-result",
    )
    assertTypedRefundActionRejected(
        connection = connection,
        bookingId = bookingId,
        resultAmountMinor = 100L,
        sourceKind = null,
        label = "positive-null-source",
    )
    assertTypedRefundActionRejected(
        connection = connection,
        bookingId = bookingId,
        resultAmountMinor = 100L,
        sourceKind = "LEGACY_ACTION",
        label = "positive-legacy-source",
    )
    assertTypedRefundActionRejected(
        connection = connection,
        bookingId = bookingId,
        resultAmountMinor = 0L,
        sourceKind = "ATOMIC_ACTION",
        label = "zero-atomic-source",
    )
    assertTypedRefundActionRejected(
        connection = connection,
        bookingId = bookingId,
        resultAmountMinor = 100L,
        sourceKind = "UNKNOWN_SOURCE",
        label = "unknown-source",
    )

    val positiveActionId =
        insertTypedRefundAction(
            connection = connection,
            bookingId = bookingId,
            resultAmountMinor = 100L,
            sourceKind = "ATOMIC_ACTION",
            label = "valid-positive",
        )
    val zeroActionId =
        insertTypedRefundAction(
            connection = connection,
            bookingId = bookingId,
            resultAmountMinor = 0L,
            sourceKind = null,
            label = "valid-zero",
        )
    check(ledgerSourceCount(connection, "action_id", zeroActionId) == 0L) {
        "Zero typed refund unexpectedly created a ledger mutation"
    }
    if (connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)) {
        check(ledgerSourceCount(connection, "action_id", positiveActionId) == 1L) {
            "Positive typed PostgreSQL refund did not create exactly one ledger row"
        }
    }
}

private fun assertTypedRefundActionRejected(
    connection: Connection,
    bookingId: UUID,
    resultAmountMinor: Long?,
    sourceKind: String?,
    label: String,
) {
    assertThrows<SQLException> {
        insertTypedRefundAction(connection, bookingId, resultAmountMinor, sourceKind, "invalid-$label")
    }
}

private fun insertTypedRefundAction(
    connection: Connection,
    bookingId: UUID,
    resultAmountMinor: Long?,
    sourceKind: String?,
    label: String,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO payment_actions (
                booking_id, idempotency_key, action, status, reason,
                refund_fingerprint_version, refund_request_mode, refund_request_amount_minor,
                refund_result_amount_minor, refund_source_kind
            ) VALUES (?, ?, 'REFUND', 'OK', 'typed constraint test', 1, 'EXPLICIT', 100, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, "typed-$label-${UUID.randomUUID()}")
            statement.setObject(3, resultAmountMinor, Types.BIGINT)
            statement.setString(4, sourceKind)
            check(statement.executeUpdate() == 1)
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong(1)
            }
        }

private fun assertLegacyRefundBackfill(
    connection: Connection,
    actionId: Long,
    expectedAmountMinor: Long,
) {
    connection
        .prepareStatement(
            """
            SELECT source_kind, source_action, source_status, amount_minor
            FROM payment_refunds
            WHERE action_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, actionId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Legacy successful refund was not backfilled" }
                check(resultSet.getString("source_kind") == "LEGACY_ACTION")
                check(resultSet.getString("source_action") == "REFUND")
                check(resultSet.getString("source_status") == "OK")
                check(resultSet.getLong("amount_minor") == expectedAmountMinor)
                check(!resultSet.next()) { "Legacy successful refund was backfilled more than once" }
            }
        }
}

private fun assertNoLegacyRefundBackfill(
    connection: Connection,
    actionId: Long,
) {
    connection.prepareStatement("SELECT COUNT(*) FROM payment_refunds WHERE action_id = ?").use { statement ->
        statement.setLong(1, actionId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next() && resultSet.getLong(1) == 0L) {
                "Legacy terminal error must not create a financial mutation"
            }
        }
    }
}

private fun assertPaymentStatusRefundBackfill(
    connection: Connection,
    bookingId: UUID,
    paymentId: UUID,
    expectedAmountMinor: Long,
) {
    connection
        .prepareStatement(
            """
            SELECT booking_id, source_kind, action_id, source_action, source_status, amount_minor
            FROM payment_refunds
            WHERE source_payment_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "REFUNDED payment was not backfilled" }
                check(resultSet.getObject("booking_id") == bookingId)
                check(resultSet.getString("source_kind") == "PAYMENT_STATUS")
                check(resultSet.getObject("action_id") == null)
                check(resultSet.getObject("source_action") == null)
                check(resultSet.getString("source_status") == "REFUNDED")
                check(resultSet.getLong("amount_minor") == expectedAmountMinor)
                check(!resultSet.next()) { "REFUNDED payment was backfilled more than once" }
            }
        }
}

private fun assertBlocked(
    connection: Connection,
    bookingId: UUID,
    expectedReason: String,
) {
    connection
        .prepareStatement(
            "SELECT blocked_reason FROM booking_refund_reconciliation WHERE booking_id = ?",
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Expected booking $bookingId to be reconciliation-blocked" }
                check(resultSet.getString("blocked_reason") == expectedReason)
            }
        }
}

private fun assertNotBlocked(
    connection: Connection,
    bookingId: UUID,
) {
    connection
        .prepareStatement(
            "SELECT blocked_reason FROM booking_refund_reconciliation WHERE booking_id = ?",
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    check(resultSet.getString("blocked_reason") == null) {
                        "Booking $bookingId was unexpectedly reconciliation-blocked"
                    }
                }
            }
        }
}

private fun assertLedgerAggregate(
    connection: Connection,
    bookingId: UUID,
    expectedRows: Long,
    expectedAmount: Long,
) {
    connection
        .prepareStatement(
            "SELECT COUNT(*), COALESCE(SUM(amount_minor), 0) FROM payment_refunds WHERE booking_id = ?",
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                check(resultSet.getLong(1) == expectedRows)
                check(resultSet.getLong(2) == expectedAmount)
            }
        }
}

internal fun assertAtomicRefundIndexes(connection: Connection) {
    val requiredIndexes =
        mapOf(
            "payments" to setOf("payments_booking_idx"),
            "payment_refunds" to
                setOf(
                    "payment_refunds_action_idx",
                    "payment_refunds_source_payment_idx",
                    "payment_refunds_booking_idx",
                ),
        )
    requiredIndexes.forEach { (table, required) ->
        val actual = mutableSetOf<String>()
        connection.metaData
            .getIndexInfo(connection.catalog, connection.schema, table, false, false)
            .use { indexes ->
                while (indexes.next()) {
                    indexes.getString("INDEX_NAME")?.lowercase()?.let(actual::add)
                }
            }
        check(actual.containsAll(required)) {
            "Missing refund indexes for $table: expected=$required actual=$actual"
        }
    }
}

internal fun assertAtomicRefundSourceConstraints(connection: Connection) {
    val bookingId = insertLegacyBooking(connection, "constraint-primary")
    val otherBookingId = insertLegacyBooking(connection, "constraint-other")
    val cancelActionId = insertActionAfterV056(connection, bookingId, "CANCEL", "OK")
    val errorActionId = insertActionAfterV056(connection, bookingId, "REFUND", "ERROR")
    val processingActionId = insertActionAfterV056(connection, bookingId, "REFUND", "PROCESSING")
    val validActionId = insertTypedAtomicAction(connection, bookingId, 100L)
    val capturedPaymentId = insertLegacyPayment(connection, bookingId, 500L, "CAPTURED")
    val refundedPaymentId = insertLegacyPayment(connection, bookingId, 300L, "REFUNDED")

    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "ATOMIC_ACTION",
        actionId = cancelActionId,
        sourcePaymentId = null,
        sourceAction = null,
        sourceStatus = "OK",
        amountMinor = 100L,
    )
    assertLedgerInsertRejected(
        connection,
        otherBookingId,
        sourceKind = "ATOMIC_ACTION",
        actionId = cancelActionId,
        sourcePaymentId = null,
        sourceAction = "REFUND",
        sourceStatus = "OK",
        amountMinor = 100L,
    )
    listOf(cancelActionId, errorActionId, processingActionId).forEach { actionId ->
        assertLedgerInsertRejected(
            connection,
            bookingId,
            sourceKind = "ATOMIC_ACTION",
            actionId = actionId,
            sourcePaymentId = null,
            sourceAction = "REFUND",
            sourceStatus = "OK",
            amountMinor = 100L,
        )
    }
    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "ATOMIC_ACTION",
        actionId = validActionId,
        sourcePaymentId = null,
        sourceAction = "REFUND",
        sourceStatus = "OK",
        amountMinor = 99L,
    )
    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "PAYMENT_STATUS",
        actionId = null,
        sourcePaymentId = capturedPaymentId,
        sourceAction = null,
        sourceStatus = "REFUNDED",
        amountMinor = 500L,
    )
    assertLedgerInsertRejected(
        connection,
        otherBookingId,
        sourceKind = "PAYMENT_STATUS",
        actionId = null,
        sourcePaymentId = refundedPaymentId,
        sourceAction = null,
        sourceStatus = "REFUNDED",
        amountMinor = 300L,
    )
    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "PAYMENT_STATUS",
        actionId = null,
        sourcePaymentId = refundedPaymentId,
        sourceAction = null,
        sourceStatus = "REFUNDED",
        amountMinor = 299L,
    )

    ensureAtomicActionLedger(connection, bookingId, validActionId, 100L)
    ensurePaymentStatusLedger(connection, bookingId, refundedPaymentId, 300L)
    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "ATOMIC_ACTION",
        actionId = validActionId,
        sourcePaymentId = null,
        sourceAction = "REFUND",
        sourceStatus = "OK",
        amountMinor = 100L,
    )
    assertLedgerInsertRejected(
        connection,
        bookingId,
        sourceKind = "PAYMENT_STATUS",
        actionId = null,
        sourcePaymentId = refundedPaymentId,
        sourceAction = null,
        sourceStatus = "REFUNDED",
        amountMinor = 300L,
    )
}

internal fun assertPostgresRefundCompositeForeignKeys(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute("ALTER TABLE payment_actions DISABLE TRIGGER trg_payment_actions_record_refund_source")
        statement.execute("ALTER TABLE payments DISABLE TRIGGER trg_payments_record_refunded_status")
    }
    try {
        val actionBookingId = insertLegacyBooking(connection, "pg-fk-action")
        val otherBookingId = insertLegacyBooking(connection, "pg-fk-other")
        val actionId = insertTypedAtomicAction(connection, actionBookingId, 175L)
        assertPostgresForeignKeyRejected(
            connection,
            otherBookingId,
            sourceKind = "ATOMIC_ACTION",
            actionId = actionId,
            sourcePaymentId = null,
            sourceAction = "REFUND",
            sourceStatus = "OK",
            amountMinor = 175L,
        )
        assertPostgresForeignKeyRejected(
            connection,
            actionBookingId,
            sourceKind = "ATOMIC_ACTION",
            actionId = actionId,
            sourcePaymentId = null,
            sourceAction = "REFUND",
            sourceStatus = "OK",
            amountMinor = 174L,
        )
        insertLedger(
            connection,
            actionBookingId,
            "ATOMIC_ACTION",
            actionId,
            null,
            "REFUND",
            "OK",
            175L,
        )

        val paymentBookingId = insertLegacyBooking(connection, "pg-fk-payment")
        val paymentId = insertLegacyPayment(connection, paymentBookingId, 225L, "REFUNDED")
        assertPostgresForeignKeyRejected(
            connection,
            otherBookingId,
            sourceKind = "PAYMENT_STATUS",
            actionId = null,
            sourcePaymentId = paymentId,
            sourceAction = null,
            sourceStatus = "REFUNDED",
            amountMinor = 225L,
        )
        assertPostgresForeignKeyRejected(
            connection,
            paymentBookingId,
            sourceKind = "PAYMENT_STATUS",
            actionId = null,
            sourcePaymentId = paymentId,
            sourceAction = null,
            sourceStatus = "REFUNDED",
            amountMinor = 224L,
        )
        insertLedger(
            connection,
            paymentBookingId,
            "PAYMENT_STATUS",
            null,
            paymentId,
            null,
            "REFUNDED",
            225L,
        )
    } finally {
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE payment_actions ENABLE TRIGGER trg_payment_actions_record_refund_source")
            statement.execute("ALTER TABLE payments ENABLE TRIGGER trg_payments_record_refunded_status")
        }
    }
}

@Suppress("LongParameterList")
private fun assertPostgresForeignKeyRejected(
    connection: Connection,
    bookingId: UUID,
    sourceKind: String,
    actionId: Long?,
    sourcePaymentId: UUID?,
    sourceAction: String?,
    sourceStatus: String,
    amountMinor: Long,
) {
    val error =
        assertThrows<SQLException> {
            insertLedger(
                connection,
                bookingId,
                sourceKind,
                actionId,
                sourcePaymentId,
                sourceAction,
                sourceStatus,
                amountMinor,
            )
        }
    check(error.sqlState == "23503") { "Expected PostgreSQL foreign-key rejection, got ${error.sqlState}" }
}

private fun insertActionAfterV056(
    connection: Connection,
    bookingId: UUID,
    action: String,
    status: String,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO payment_actions (booking_id, idempotency_key, action, status, reason)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, "constraint-$action-$status-${UUID.randomUUID()}")
            statement.setString(3, action)
            statement.setString(4, status)
            statement.setString(5, status.lowercase())
            check(statement.executeUpdate() == 1)
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong(1)
            }
        }

private fun insertTypedAtomicAction(
    connection: Connection,
    bookingId: UUID,
    amountMinor: Long,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO payment_actions (
                booking_id, idempotency_key, action, status, reason,
                refund_fingerprint_version, refund_request_mode, refund_request_amount_minor,
                refund_result_amount_minor, refund_source_kind
            ) VALUES (?, ?, 'REFUND', 'OK', ?, 1, 'EXPLICIT', ?, ?, 'ATOMIC_ACTION')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, "constraint-atomic-${UUID.randomUUID()}")
            statement.setString(3, amountMinor.toString())
            statement.setLong(4, amountMinor)
            statement.setLong(5, amountMinor)
            check(statement.executeUpdate() == 1)
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong(1)
            }
        }

@Suppress("LongParameterList")
private fun assertLedgerInsertRejected(
    connection: Connection,
    bookingId: UUID,
    sourceKind: String,
    actionId: Long?,
    sourcePaymentId: UUID?,
    sourceAction: String?,
    sourceStatus: String,
    amountMinor: Long,
) {
    assertThrows<SQLException> {
        insertLedger(
            connection,
            bookingId,
            sourceKind,
            actionId,
            sourcePaymentId,
            sourceAction,
            sourceStatus,
            amountMinor,
        )
    }
}

@Suppress("LongParameterList")
private fun insertLedger(
    connection: Connection,
    bookingId: UUID,
    sourceKind: String,
    actionId: Long?,
    sourcePaymentId: UUID?,
    sourceAction: String?,
    sourceStatus: String,
    amountMinor: Long,
) {
    connection
        .prepareStatement(
            """
            INSERT INTO payment_refunds (
                booking_id, source_kind, action_id, source_payment_id,
                source_action, source_status, amount_minor
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, sourceKind)
            statement.setObject(3, actionId)
            statement.setObject(4, sourcePaymentId)
            statement.setString(5, sourceAction)
            statement.setString(6, sourceStatus)
            statement.setLong(7, amountMinor)
            statement.executeUpdate()
        }
}

private fun ensureAtomicActionLedger(
    connection: Connection,
    bookingId: UUID,
    actionId: Long,
    amountMinor: Long,
) {
    if (ledgerSourceCount(connection, "action_id", actionId) == 0L) {
        insertLedger(
            connection,
            bookingId,
            "ATOMIC_ACTION",
            actionId,
            null,
            "REFUND",
            "OK",
            amountMinor,
        )
    }
}

private fun ensurePaymentStatusLedger(
    connection: Connection,
    bookingId: UUID,
    paymentId: UUID,
    amountMinor: Long,
) {
    if (ledgerSourceCount(connection, "source_payment_id", paymentId) == 0L) {
        insertLedger(
            connection,
            bookingId,
            "PAYMENT_STATUS",
            null,
            paymentId,
            null,
            "REFUNDED",
            amountMinor,
        )
    }
}

private fun ledgerSourceCount(
    connection: Connection,
    column: String,
    value: Any,
): Long {
    check(column == "action_id" || column == "source_payment_id")
    return connection.prepareStatement("SELECT COUNT(*) FROM payment_refunds WHERE $column = ?").use { statement ->
        statement.setObject(1, value)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            resultSet.getLong(1)
        }
    }
}

internal fun assertPostgresRefundWriterCompatibility(connection: Connection) {
    val legacyActionBookingId = insertLegacyBooking(connection, "post-v056-action")
    val legacyActionKey = insertLegacyAction(connection, legacyActionBookingId, "REFUND", "OK", "250")
    val legacyAction = checkNotNull(loadLegacyRefundActions(connection)[legacyActionKey])
    check(legacyAction.resultAmountMinor == 250L)
    check(legacyAction.sourceKind == "LEGACY_ACTION")
    assertLegacyRefundBackfill(connection, legacyAction.id, 250L)

    val malformedBookingId = insertLegacyBooking(connection, "post-v056-malformed")
    val malformedKey = insertLegacyAction(connection, malformedBookingId, "REFUND", "OK", "malformed")
    val malformedAction = checkNotNull(loadLegacyRefundActions(connection)[malformedKey])
    check(malformedAction.resultAmountMinor == null && malformedAction.sourceKind == null)
    assertNoLegacyRefundBackfill(connection, malformedAction.id)
    assertBlocked(connection, malformedBookingId, "MALFORMED_LEGACY_REFUND_ACTION")

    listOf("0", "-1").forEach { nonPositiveReason ->
        val label = if (nonPositiveReason == "0") "zero" else "negative"
        val bookingId = insertLegacyBooking(connection, "post-v056-$label")
        val key = insertLegacyAction(connection, bookingId, "REFUND", "OK", nonPositiveReason)
        val action = checkNotNull(loadLegacyRefundActions(connection)[key])
        check(action.resultAmountMinor == null && action.sourceKind == null)
        assertNoLegacyRefundBackfill(connection, action.id)
        assertBlocked(connection, bookingId, "MALFORMED_LEGACY_REFUND_ACTION")
    }

    val paymentBookingId = insertLegacyBooking(connection, "post-v056-payment")
    val paymentId = insertLegacyPayment(connection, paymentBookingId, 600L, "CAPTURED")
    updatePaymentStatus(connection, paymentId, "REFUNDED")
    updatePaymentStatus(connection, paymentId, "REFUNDED")
    assertPaymentStatusRefundBackfill(connection, paymentBookingId, paymentId, 600L)

    val ambiguousBookingId = insertLegacyBooking(connection, "post-v056-ambiguous")
    val ambiguousKey = insertLegacyAction(connection, ambiguousBookingId, "REFUND", "OK", "100")
    val ambiguousAction = checkNotNull(loadLegacyRefundActions(connection)[ambiguousKey])
    val ambiguousPaymentId = insertLegacyPayment(connection, ambiguousBookingId, 500L, "CAPTURED")
    updatePaymentStatus(connection, ambiguousPaymentId, "REFUNDED")
    assertLegacyRefundBackfill(connection, ambiguousAction.id, 100L)
    assertPaymentStatusRefundBackfill(connection, ambiguousBookingId, ambiguousPaymentId, 500L)
    assertBlocked(connection, ambiguousBookingId, "AMBIGUOUS_LEGACY_REFUND_SOURCES")

    val reverseAmbiguousBookingId = insertLegacyBooking(connection, "post-v056-ambiguous-reverse")
    val reversePaymentId = insertLegacyPayment(connection, reverseAmbiguousBookingId, 700L, "CAPTURED")
    updatePaymentStatus(connection, reversePaymentId, "REFUNDED")
    val reverseActionKey = insertLegacyAction(connection, reverseAmbiguousBookingId, "REFUND", "OK", "125")
    val reverseAction = checkNotNull(loadLegacyRefundActions(connection)[reverseActionKey])
    assertPaymentStatusRefundBackfill(connection, reverseAmbiguousBookingId, reversePaymentId, 700L)
    assertLegacyRefundBackfill(connection, reverseAction.id, 125L)
    assertBlocked(connection, reverseAmbiguousBookingId, "AMBIGUOUS_LEGACY_REFUND_SOURCES")

    assertPostgresLedgerCollisionFailsClosed(connection)
    assertPostgresRefundLedgerAppendOnly(connection, legacyAction.id)
}

private fun updatePaymentStatus(
    connection: Connection,
    paymentId: UUID,
    status: String,
) {
    connection.prepareStatement("UPDATE payments SET status = ? WHERE id = ?").use { statement ->
        statement.setString(1, status)
        statement.setObject(2, paymentId)
        check(statement.executeUpdate() == 1)
    }
}

private fun assertPostgresLedgerCollisionFailsClosed(connection: Connection) {
    val existingRows =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM payment_refunds WHERE id = 1").use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
    check(existingRows == 1L) { "Expected a ledger row with id=1 for sequence collision test" }
    val bookingId = insertLegacyBooking(connection, "post-v056-sequence-collision")
    val key = "legacy-sequence-collision-${UUID.randomUUID()}"
    connection.createStatement().use { statement ->
        statement.execute("ALTER SEQUENCE payment_refunds_id_seq RESTART WITH 1")
    }
    try {
        assertThrows<SQLException> {
            connection
                .prepareStatement(
                    """
                    INSERT INTO payment_actions (booking_id, idempotency_key, action, status, reason)
                    VALUES (?, ?, 'REFUND', 'OK', '75')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, bookingId)
                    statement.setString(2, key)
                    statement.executeUpdate()
                }
        }
        connection.prepareStatement("SELECT COUNT(*) FROM payment_actions WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next() && resultSet.getLong(1) == 0L) {
                    "Ledger sequence collision committed an orphan terminal action"
                }
            }
        }
    } finally {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                SELECT setval(
                    'payment_refunds_id_seq',
                    (SELECT COALESCE(MAX(id), 0) + 1 FROM payment_refunds),
                    false
                )
                """.trimIndent(),
            )
        }
    }
}

private fun assertPostgresRefundLedgerAppendOnly(
    connection: Connection,
    actionId: Long,
) {
    val ledgerId =
        connection.prepareStatement("SELECT id FROM payment_refunds WHERE action_id = ?").use { statement ->
            statement.setLong(1, actionId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
    assertThrows<SQLException> {
        connection.prepareStatement("UPDATE payment_refunds SET amount_minor = amount_minor + 1 WHERE id = ?").use {
            it.setLong(1, ledgerId)
            it.executeUpdate()
        }
    }
    assertThrows<SQLException> {
        connection.prepareStatement("DELETE FROM payment_refunds WHERE id = ?").use {
            it.setLong(1, ledgerId)
            it.executeUpdate()
        }
    }
    check(ledgerSourceCount(connection, "action_id", actionId) == 1L)
}

internal fun assertPostgresRefundQueryUsesBookingIndex(connection: Connection) {
    val bookingId = insertLegacyBooking(connection, "index-plan")
    insertLegacyPayment(connection, bookingId, 100L, "CAPTURED")
    val suffix = UUID.randomUUID().toString().replace("-", "")
    connection.createStatement().use { statement ->
        statement.executeUpdate(
            """
            INSERT INTO payments (
                provider, currency, amount_minor, status, payload, idempotency_key
            )
            SELECT
                'index-test', 'RUB', 1, 'CAPTURED',
                'index-payload-$suffix-' || value,
                'index-key-$suffix-' || value
            FROM generate_series(1, 3000) AS series(value)
            """.trimIndent(),
        )
        statement.execute("ANALYZE payments")
    }
    val plan =
        connection.prepareStatement("EXPLAIN SELECT * FROM payments WHERE booking_id = ?").use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { resultSet ->
                buildString {
                    while (resultSet.next()) {
                        appendLine(resultSet.getString(1))
                    }
                }
            }
        }
    check(plan.contains("payments_booking_idx")) { "Refund payment lookup has no indexed path:\n$plan" }
}

internal fun assertPostgresRefundTrustedSearchPath(connection: Connection) {
    val trustedSchema =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_schema()").use { resultSet ->
                check(resultSet.next())
                resultSet.getString(1)
            }
        }
    check(trustedSchema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
        "Unexpected trusted schema identifier: $trustedSchema"
    }
    val trusted = "\"$trustedSchema\""
    val positiveBookingId = insertLegacyBooking(connection, "hostile-positive")
    val malformedBookingId = insertLegacyBooking(connection, "hostile-malformed")
    val paymentBookingId = insertLegacyBooking(connection, "hostile-payment")
    val paymentId = insertLegacyPayment(connection, paymentBookingId, 225L, "CAPTURED")
    val hostileSchema = "hostile_refund_${UUID.randomUUID().toString().replace("-", "")}"
    val trustedLedgerRowsBefore = qualifiedCount(connection, "$trusted.payment_refunds")

    connection.createStatement().use { statement ->
        statement.execute("CREATE SCHEMA $hostileSchema")
        statement.execute("CREATE TABLE $hostileSchema.payment_actions (marker text)")
        statement.execute("CREATE TABLE $hostileSchema.payments (marker text)")
        statement.execute("CREATE TABLE $hostileSchema.payment_refunds (marker text)")
        statement.execute("CREATE TABLE $hostileSchema.booking_refund_reconciliation (marker text)")
        statement.execute("CREATE TABLE $hostileSchema.helper_calls (booking_id uuid, reason varchar)")
        statement.execute(
            """
            CREATE FUNCTION $hostileSchema.payment_refund_block_booking(uuid, varchar)
            RETURNS void
            LANGUAGE sql
            AS 'INSERT INTO $hostileSchema.helper_calls VALUES ($1, $2)'
            """.trimIndent(),
        )
        statement.execute("SET search_path = $hostileSchema, $trusted")
    }

    insertLegacyActionQualified(connection, trusted, positiveBookingId, "275")
    insertLegacyActionQualified(connection, trusted, malformedBookingId, "0")
    connection.prepareStatement("UPDATE $trusted.payments SET status = 'REFUNDED' WHERE id = ?").use { statement ->
        statement.setObject(1, paymentId)
        check(statement.executeUpdate() == 1)
    }

    check(qualifiedCount(connection, "$trusted.payment_refunds") == trustedLedgerRowsBefore + 2L)
    check(qualifiedCount(connection, "$hostileSchema.payment_refunds") == 0L)
    check(qualifiedCount(connection, "$hostileSchema.booking_refund_reconciliation") == 0L)
    check(qualifiedCount(connection, "$hostileSchema.helper_calls") == 0L)
    connection
        .prepareStatement(
            "SELECT blocked_reason FROM $trusted.booking_refund_reconciliation WHERE booking_id = ?",
        ).use { statement ->
            statement.setObject(1, malformedBookingId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                check(resultSet.getString(1) == "MALFORMED_LEGACY_REFUND_ACTION")
            }
        }

    val expectedFunctions =
        setOf(
            "payment_refund_block_booking",
            "payment_refund_prepare_legacy_action",
            "payment_refund_track_source",
            "payment_refund_record_action_source",
            "payment_refund_reject_mutation",
            "payment_refund_record_payment_status",
        )
    val protectedFunctions = mutableSetOf<String>()
    connection
        .prepareStatement(
            """
            SELECT p.proname, p.proconfig
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ?
              AND p.proname LIKE 'payment_refund_%'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, trustedSchema)
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    val functionName = resultSet.getString(1)
                    val config = resultSet.getArray(2)?.array as? Array<*>
                    val searchPath = config?.mapNotNull { it?.toString() }?.singleOrNull()
                    val hasTrustedSearchPath =
                        searchPath != null &&
                            searchPath.contains("pg_catalog") &&
                            searchPath.contains(trustedSchema)
                    check(hasTrustedSearchPath) {
                        "Financial function $functionName has unsafe search_path: $searchPath"
                    }
                    protectedFunctions += functionName
                }
            }
        }
    check(protectedFunctions == expectedFunctions) {
        "Unexpected trusted financial function set: $protectedFunctions"
    }
    connection.createStatement().use { statement ->
        statement.execute("SET search_path = $trusted")
    }
}

private fun insertLegacyActionQualified(
    connection: Connection,
    trustedSchema: String,
    bookingId: UUID,
    reason: String,
) {
    connection
        .prepareStatement(
            """
            INSERT INTO $trustedSchema.payment_actions (booking_id, idempotency_key, action, status, reason)
            VALUES (?, ?, 'REFUND', 'OK', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setString(2, "hostile-search-path-${UUID.randomUUID()}")
            statement.setString(3, reason)
            check(statement.executeUpdate() == 1)
        }
}

private fun qualifiedCount(
    connection: Connection,
    qualifiedTable: String,
): Long =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $qualifiedTable").use { resultSet ->
            check(resultSet.next())
            resultSet.getLong(1)
        }
    }

private data class LegacyRefundFixture(
    val numericBookingId: UUID,
    val numericKey: String,
    val malformedBookingId: UUID,
    val malformedKey: String,
    val zeroBookingId: UUID,
    val zeroKey: String,
    val negativeBookingId: UUID,
    val negativeKey: String,
    val errorKey: String,
    val conflictKey: String,
    val cancelKey: String,
    val refundedPaymentBookingId: UUID,
    val refundedPaymentId: UUID,
    val ambiguousBookingId: UUID,
    val ambiguousActionKey: String,
    val ambiguousPaymentId: UUID,
    val multiplePaymentsBookingId: UUID,
    val multiplePaymentIds: List<UUID>,
)

private data class LegacyRefundAction(
    val id: Long,
    val action: String,
    val status: String,
    val reason: String?,
    val fingerprintVersion: Any?,
    val requestMode: Any?,
    val requestAmountMinor: Any?,
    val resultAmountMinor: Long?,
    val sourceKind: String?,
) {
    fun hasNoRequestFingerprint(): Boolean =
        fingerprintVersion == null &&
            requestMode == null &&
            requestAmountMinor == null
}

internal inline fun withConnection(
    resourcesToClose: MutableList<AutoCloseable>,
    block: (Connection) -> Unit,
) {
    val dataSource = resourcesToClose.filterIsInstance<HikariDataSource>().last()
    dataSource.connection.use(block)
}

internal fun assertUuidDefault(connection: Connection) {
    val key = "smoke-" + UUID.randomUUID()
    connection
        .prepareStatement(
            """
            INSERT INTO payments (provider, currency, amount_minor, status, payload, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "stripe")
            statement.setString(2, "USD")
            statement.setLong(3, 1_000L)
            statement.setString(4, "PENDING")
            statement.setString(5, "payload-$key")
            statement.setString(6, key)
            statement.executeUpdate()
        }

    connection.prepareStatement("SELECT id FROM payments WHERE idempotency_key = ?").use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rs ->
            require(rs.next()) { "Payment row was not inserted" }
            val value = rs.getString(1)
            UUID.fromString(value)
        }
    }
}

internal fun assertPaymentsSchema(
    connection: Connection,
    vendor: String,
) {
    val metadata = connection.metaData
    val schemaPattern = connection.schema
    val columns = mutableMapOf<String, PaymentColumnSchema>()
    metadata.getColumns(connection.catalog, schemaPattern, "payments", null).use { resultSet ->
        while (resultSet.next()) {
            val name = resultSet.getString("COLUMN_NAME").lowercase()
            columns[name] =
                PaymentColumnSchema(
                    jdbcType = resultSet.getInt("DATA_TYPE"),
                    typeName = resultSet.getString("TYPE_NAME"),
                    nullable = resultSet.getInt("NULLABLE"),
                )
        }
    }

    val actual =
        columns.entries
            .sortedBy { it.key }
            .joinToString { (name, column) ->
                "$name(type=${column.typeName}, nullable=${column.nullable})"
            }
    check("amount" !in columns) {
        "$vendor payments schema still contains legacy amount; actual columns: $actual"
    }

    val amountMinor = columns["amount_minor"]
    checkNotNull(amountMinor) {
        "$vendor payments.amount_minor is missing; actual columns: $actual"
    }
    check(amountMinor.jdbcType == Types.BIGINT) {
        "$vendor payments.amount_minor must be BIGINT, was ${amountMinor.typeName}; actual columns: $actual"
    }
    check(amountMinor.nullable == DatabaseMetaData.columnNoNulls) {
        "$vendor payments.amount_minor must be NOT NULL; actual columns: $actual"
    }

    val payload = columns["payload"]
    checkNotNull(payload) {
        "$vendor payments.payload is missing; actual columns: $actual"
    }
    check(payload.nullable == DatabaseMetaData.columnNoNulls) {
        "$vendor payments.payload must be NOT NULL; actual columns: $actual"
    }
}

private data class PaymentColumnSchema(
    val jdbcType: Int,
    val typeName: String,
    val nullable: Int,
)

internal fun assertJsonColumnType(
    connection: Connection,
    expectedType: String,
) {
    val metadata = connection.metaData
    val schemaPattern =
        connection.schema ?: when (metadata.databaseProductName.lowercase()) {
            "postgresql" -> "public"
            else -> null
        }
    metadata.getColumns(connection.catalog, schemaPattern, "notifications_outbox", "payload").use { columns ->
        require(columns.next()) { "notifications_outbox.payload column not found" }
        val typeName = columns.getString("TYPE_NAME").lowercase()
        check(typeName == expectedType) {
            "Expected JSON column type $expectedType but was $typeName"
        }
    }
}

internal fun assertGuestListLimitRemoved(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'guest_lists' AND lower(COLUMN_NAME) = 'limit'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(!rs.next()) { "legacy column guest_lists.limit should be absent" }
            }
        }

    connection
        .prepareStatement(
            """
        SELECT IS_NULLABLE, COLUMN_DEFAULT
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'guest_lists' AND lower(COLUMN_NAME) = 'capacity'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists.capacity column not found" }
                val nullable = rs.getString("IS_NULLABLE").equals("YES", ignoreCase = true)
                check(!nullable) { "guest_lists.capacity must be NOT NULL" }
            }
        }
}

internal fun assertCheckinsSchema(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'checkins' AND lower(COLUMN_NAME) = 'subject_id'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins.subject_id column not found" }
                val type = rs.getString("DATA_TYPE")
                val isVarchar =
                    type.equals("VARCHAR", ignoreCase = true) ||
                        type.equals("CHARACTER VARYING", ignoreCase = true)
                check(isVarchar) { "checkins.subject_id must be VARCHAR but was $type" }
                val length = rs.getInt("CHARACTER_MAXIMUM_LENGTH")
                check(length >= 64) { "checkins.subject_id length expected >= 64 but was $length" }
            }
        }

    connection
        .prepareStatement(
            """
        SELECT CHECK_CLAUSE
        FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
        WHERE lower(CONSTRAINT_NAME) = 'checkins_deny_reason_consistency'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins_deny_reason_consistency constraint missing" }
            }
        }
}

internal fun assertGuestListStatusConstraintH2(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT 1
        FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
        WHERE lower(CONSTRAINT_NAME) = 'guest_lists_status_check'
          AND lower(TABLE_NAME) = 'guest_lists'
          AND upper(CONSTRAINT_TYPE) = 'CHECK'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists_status_check constraint missing" }
            }
        }
}

internal fun assertCheckinsConstraintEnforced(connection: Connection) {
    val nonDeniedWithReason =
        """
        INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
        VALUES ('GUEST_LIST_ENTRY', '1', 'QR', 'ARRIVED', 'x')
        """.trimIndent()

    assertThrows<SQLException> {
        connection.createStatement().use { statement -> statement.executeUpdate(nonDeniedWithReason) }
    }

    val deniedWithoutReason =
        """
        INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
        VALUES ('GUEST_LIST_ENTRY', '2', 'QR', 'DENIED', NULL)
        """.trimIndent()

    assertThrows<SQLException> {
        connection.createStatement().use { statement -> statement.executeUpdate(deniedWithoutReason) }
    }
}

internal fun assertGuestListStatuses(
    connection: Connection,
    baseTime: OffsetDateTime,
) {
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = true
    try {
        val fixture = insertBaseFixture(connection, baseTime)

        insertGuestList(connection, fixture, status = "CANCELLED")

        val guestListForEntries = insertGuestList(connection, fixture, status = "ACTIVE")
        insertGuestListEntry(connection, guestListForEntries, status = "ADDED")
        insertGuestListEntry(connection, guestListForEntries, status = "CONFIRMED")

        assertThrows<SQLException> {
            insertGuestListEntry(connection, guestListForEntries, status = "BROKEN_STATUS")
        }

        assertThrows<SQLException> {
            insertGuestList(connection, fixture, status = "BROKEN_STATUS")
        }
    } finally {
        connection.autoCommit = previousAutoCommit
    }
}

internal fun insertBaseFixture(
    connection: Connection,
    baseTime: OffsetDateTime,
): GuestListFixture {
    val userId = insertUser(connection)
    val clubId = insertClub(connection)
    val eventId = insertEvent(connection, clubId, baseTime)
    return GuestListFixture(userId = userId, clubId = clubId, eventId = eventId)
}

internal fun insertUser(connection: Connection): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO users (username, display_name, telegram_user_id, phone_e164)
            VALUES (?, ?, NULL, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, "smoke_user")
            statement.setString(2, "Smoke User")
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "User id not returned" }
                keys.getLong(1)
            }
        }

internal fun insertClub(connection: Connection): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO clubs (
                name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id
            ) VALUES (?, NULL, ?, NULL, NULL, NULL, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, "Smoke Club")
            statement.setString(2, "Europe/Moscow")
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Club id not returned" }
                keys.getLong(1)
            }
        }

internal fun insertEvent(
    connection: Connection,
    clubId: Long,
    baseTime: OffsetDateTime,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO events (club_id, title, start_at, end_at, is_special, poster_url)
            VALUES (?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, clubId)
            statement.setString(2, "Smoke Event")
            statement.setObject(3, baseTime)
            statement.setObject(4, baseTime.plusHours(2))
            statement.setBoolean(5, false)
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Event id not returned" }
                keys.getLong(1)
            }
        }

internal fun insertGuestList(
    connection: Connection,
    fixture: GuestListFixture,
    status: String,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO guest_lists (
                club_id, event_id, owner_type, owner_user_id, title, capacity,
                arrival_window_start, arrival_window_end, status
            ) VALUES (?, ?, 'ADMIN', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, fixture.clubId)
            statement.setLong(2, fixture.eventId)
            statement.setLong(3, fixture.userId)
            statement.setString(4, "Smoke list ${UUID.randomUUID()}")
            statement.setInt(5, 10)
            statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setString(8, status)
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Guest list id not returned" }
                keys.getLong(1)
            }
        }

internal fun insertGuestListEntry(
    connection: Connection,
    guestListId: Long,
    status: String,
) {
    connection
        .prepareStatement(
            """
            INSERT INTO guest_list_entries (guest_list_id, full_name, display_name, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, guestListId)
            statement.setString(2, "Smoke Guest")
            statement.setString(3, "Smoke Guest")
            statement.setString(4, status)
            statement.executeUpdate()
        }
}

internal data class GuestListFixture(
    val userId: Long,
    val clubId: Long,
    val eventId: Long,
)

internal fun assertGuestListLimitRemovedPostgres(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'guest_lists'
          AND column_name = 'limit'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(!rs.next()) { "legacy column guest_lists.limit should be absent" }
            }
        }

    connection
        .prepareStatement(
            """
        SELECT is_nullable
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'guest_lists'
          AND column_name = 'capacity'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists.capacity column not found" }
                val nullable = rs.getString("is_nullable").equals("YES", ignoreCase = true)
                check(!nullable) { "guest_lists.capacity must be NOT NULL" }
            }
        }
}

internal fun assertCheckinsSchemaPostgres(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT data_type
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'checkins'
          AND column_name = 'subject_id'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins.subject_id column not found" }
                val type = rs.getString("data_type")
                check(type.equals("text", ignoreCase = true)) { "checkins.subject_id must be TEXT but was $type" }
            }
        }

    connection
        .prepareStatement(
            """
        SELECT 1
        FROM information_schema.check_constraints
        WHERE constraint_schema = current_schema()
          AND constraint_name = 'checkins_deny_reason_consistency'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins_deny_reason_consistency constraint missing" }
            }
        }
}

internal fun assertGuestListStatusConstraintPostgres(connection: Connection) {
    connection
        .prepareStatement(
            """
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = current_schema()
          AND table_name = 'guest_lists'
          AND constraint_name = 'guest_lists_status_check'
          AND constraint_type = 'CHECK'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists_status_check constraint missing" }
            }
        }
}

internal fun assertCheckinsConstraintEnforcedPostgres(connection: Connection) {
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = true
    try {
        val nonDeniedWithReason =
            """
            INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason, occurred_at)
            VALUES ('GUEST_LIST_ENTRY', '1', 'QR', 'ARRIVED', 'x', now())
            """.trimIndent()
        assertThrows<SQLException> {
            connection.createStatement().use { statement -> statement.executeUpdate(nonDeniedWithReason) }
        }

        val deniedWithoutReason =
            """
            INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason, occurred_at)
            VALUES ('GUEST_LIST_ENTRY', '2', 'QR', 'DENIED', NULL, now())
            """.trimIndent()
        assertThrows<SQLException> {
            connection.createStatement().use { statement -> statement.executeUpdate(deniedWithoutReason) }
        }
    } finally {
        connection.autoCommit = previousAutoCommit
    }
}
