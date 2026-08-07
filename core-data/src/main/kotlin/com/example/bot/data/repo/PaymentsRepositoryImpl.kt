package com.example.bot.data.repo

import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.PaymentsRepository.Action
import com.example.bot.payments.PaymentsRepository.CancelExecution
import com.example.bot.payments.PaymentsRepository.PaymentRecord
import com.example.bot.payments.PaymentsRepository.RefundExecution
import com.example.bot.payments.PaymentsRepository.RefundFingerprint
import com.example.bot.payments.PaymentsRepository.RefundRequestMode
import com.example.bot.payments.PaymentsRepository.RefundSourceKind
import com.example.bot.payments.PaymentsRepository.Result
import com.example.bot.payments.PaymentsRepository.Result.Status
import com.example.bot.payments.PaymentsRepository.SavedAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * Exposed-based implementation of [PaymentsRepository].
 */
@Suppress("LargeClass")
class PaymentsRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : PaymentsRepository {
    object PaymentsTable : Table("payments") {
        val id = uuid("id").autoGenerate()
        val bookingId = uuid("booking_id").nullable()
        val provider = text("provider")
        val currency = varchar("currency", 8)
        val amountMinor = long("amount_minor")
        val status = text("status")
        val payload = text("payload").uniqueIndex()
        val externalId = text("external_id").nullable()
        val telegramPaymentChargeId = text("telegram_payment_charge_id").nullable().uniqueIndex()
        val providerPaymentChargeId = text("provider_payment_charge_id").nullable().uniqueIndex()
        val idempotencyKey = text("idempotency_key").uniqueIndex()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(id)

        init {
            index(isUnique = false, columns = arrayOf(bookingId))
        }
    }

    object PaymentActionsTable : Table("payment_actions") {
        val id = long("id").autoIncrement()
        val bookingId = uuid("booking_id")
        val idempotencyKey = text("idempotency_key").uniqueIndex()
        val action = text("action")
        val status = text("status")
        val reason = text("reason").nullable()
        val refundFingerprintVersion = integer("refund_fingerprint_version").nullable()
        val refundRequestMode = varchar("refund_request_mode", 32).nullable()
        val refundRequestAmountMinor = long("refund_request_amount_minor").nullable()
        val refundResultAmountMinor = long("refund_result_amount_minor").nullable()
        val refundSourceKind = varchar("refund_source_kind", 32).nullable()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(id)

        init {
            index(isUnique = false, columns = arrayOf(bookingId))
        }
    }

    object PaymentRefundsTable : Table("payment_refunds") {
        val id = long("id").autoIncrement()
        val bookingId = uuid("booking_id")
        val sourceKind = varchar("source_kind", 32)
        val actionId = long("action_id").nullable().uniqueIndex()
        val sourcePaymentId = uuid("source_payment_id").nullable().uniqueIndex()
        val sourceAction = text("source_action").nullable()
        val sourceStatus = text("source_status")
        val amountMinor = long("amount_minor")
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(id)

        init {
            index(isUnique = false, columns = arrayOf(bookingId))
        }
    }

    object BookingRefundReconciliationTable : Table("booking_refund_reconciliation") {
        val bookingId = uuid("booking_id")
        val hasAtomicActionSource = bool("has_atomic_action_source").default(false)
        val hasLegacyActionSource = bool("has_legacy_action_source").default(false)
        val hasPaymentStatusSource = bool("has_payment_status_source").default(false)
        val blockedReason = varchar("blocked_reason", 64).nullable()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(bookingId)
    }

    override suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentRecord =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val row =
                PaymentsTable
                    .insert {
                        it[PaymentsTable.bookingId] = bookingId
                        it[PaymentsTable.provider] = provider
                        it[PaymentsTable.currency] = currency
                        it[PaymentsTable.amountMinor] = amountMinor
                        it[PaymentsTable.status] = "INITIATED"
                        it[PaymentsTable.payload] = payload
                        it[PaymentsTable.idempotencyKey] = idempotencyKey
                    }.resultedValues!!
                    .first()
            row.toRecord()
        }

    override suspend fun markPending(id: UUID) = updateStatus(id, "PENDING", null)

    override suspend fun markCaptured(
        id: UUID,
        externalId: String?,
    ) {
        updateStatus(id, "CAPTURED", externalId)
    }

    override suspend fun markCapturedByChargeIds(
        id: UUID,
        externalId: String?,
        telegramPaymentChargeId: String?,
        providerPaymentChargeId: String?,
    ): PaymentsRepository.CaptureResult =
        try {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val paymentRow =
                    PaymentsTable
                        .selectAll()
                        .where { PaymentsTable.id eq id }
                        .forUpdate()
                        .limit(1)
                        .firstOrNull()
                        ?: return@newSuspendedTransaction PaymentsRepository.CaptureResult.PAYMENT_NOT_FOUND

                if (paymentRow[PaymentsTable.status] == CAPTURED_STATUS) {
                    return@newSuspendedTransaction PaymentsRepository.CaptureResult.ALREADY_CAPTURED
                }

                val updated =
                    PaymentsTable.update({ (PaymentsTable.id eq id) and (PaymentsTable.status neq CAPTURED_STATUS) }) {
                        it[status] = CAPTURED_STATUS
                        it[PaymentsTable.externalId] = paymentRow[PaymentsTable.externalId] ?: externalId
                        it[PaymentsTable.telegramPaymentChargeId] =
                            paymentRow[PaymentsTable.telegramPaymentChargeId] ?: telegramPaymentChargeId
                        it[PaymentsTable.providerPaymentChargeId] =
                            paymentRow[PaymentsTable.providerPaymentChargeId] ?: providerPaymentChargeId
                    }

                if (updated == 0) {
                    PaymentsRepository.CaptureResult.ALREADY_CAPTURED
                } else {
                    PaymentsRepository.CaptureResult.CAPTURED
                }
            }
        } catch (ex: Exception) {
            mapCaptureException(ex) ?: throw ex
        }

    internal fun mapCaptureException(ex: Exception): PaymentsRepository.CaptureResult? {
        if (ex is CancellationException) {
            throw ex
        }
        return if (ex.isUniqueViolation()) {
            PaymentsRepository.CaptureResult.CHARGE_CONFLICT
        } else {
            null
        }
    }

    override suspend fun markDeclined(
        id: UUID,
        reason: String,
    ) {
        updateStatus(id, "DECLINED", reason)
    }

    override suspend fun markRefunded(
        id: UUID,
        externalId: String?,
    ) {
        markPaymentFullyRefunded(id, externalId)
    }

    override suspend fun findByPayload(payload: String): PaymentRecord? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.payload eq payload }
                .firstOrNull()
                ?.toRecord()
        }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentRecord? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.idempotencyKey eq idempotencyKey }
                .firstOrNull()
                ?.toRecord()
        }

    override suspend fun recordAction(
        bookingId: UUID,
        key: String,
        action: Action,
        result: Result,
    ): SavedAction =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            PaymentActionsTable.insertIgnore {
                it[PaymentActionsTable.bookingId] = bookingId
                it[PaymentActionsTable.idempotencyKey] = key
                it[PaymentActionsTable.action] = action.name
                it[PaymentActionsTable.status] = result.status.name
                it[PaymentActionsTable.reason] = result.reason
            }
            PaymentActionsTable
                .selectAll()
                .where { PaymentActionsTable.idempotencyKey eq key }
                .limit(1)
                .firstOrNull()
                ?.toSavedAction()
                ?: error("Failed to load payment action by idempotency key")
        }

    override suspend fun findActionByIdempotencyKey(key: String): SavedAction? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            PaymentActionsTable
                .selectAll()
                .where { PaymentActionsTable.idempotencyKey eq key }
                .limit(1)
                .firstOrNull()
                ?.toSavedAction()
        }

    override suspend fun executeCancelIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        reason: String?,
    ): CancelExecution {
        require(idempotencyKey.isNotBlank()) { "cancel idempotency key must not be blank" }
        val callerContext = currentCoroutineContext()
        return newSuspendedTransaction(
            context = Dispatchers.IO,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            val result = executeCancelTransaction(clubId, bookingId, idempotencyKey, reason)
            callerContext.ensureActive()
            result
        }
    }

    override suspend fun executeRefundIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: RefundFingerprint,
    ): RefundExecution {
        validateRefundFingerprint(fingerprint)
        val callerContext = currentCoroutineContext()
        return newSuspendedTransaction(
            context = Dispatchers.IO,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            val result =
                executeRefundTransaction(
                    clubId = clubId,
                    bookingId = bookingId,
                    idempotencyKey = idempotencyKey,
                    fingerprint = fingerprint,
                )
            callerContext.ensureActive()
            result
        }
    }

    override suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    ) {
        if (status == REFUNDED_STATUS) {
            markPaymentFullyRefunded(id, externalId)
            return
        }
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            PaymentsTable.update({ PaymentsTable.id eq id }) {
                it[PaymentsTable.status] = status
                it[PaymentsTable.externalId] = externalId
            }
        }
    }

    private suspend fun markPaymentFullyRefunded(
        id: UUID,
        externalId: String?,
    ) {
        newSuspendedTransaction(
            context = Dispatchers.IO,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            val payment =
                PaymentsTable
                    .selectAll()
                    .where { PaymentsTable.id eq id }
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@newSuspendedTransaction
            val bookingId = payment[PaymentsTable.bookingId]
            val amountMinor = payment[PaymentsTable.amountMinor]

            PaymentsTable.update({ PaymentsTable.id eq id }) {
                it[status] = REFUNDED_STATUS
                it[PaymentsTable.externalId] = externalId
            }

            if (bookingId != null) {
                if (amountMinor > 0) {
                    val inserted =
                        PaymentRefundsTable.insertIgnore {
                            it[PaymentRefundsTable.bookingId] = bookingId
                            it[sourceKind] = RefundSourceKind.PAYMENT_STATUS.name
                            it[actionId] = null
                            it[sourcePaymentId] = id
                            it[sourceAction] = null
                            it[sourceStatus] = REFUNDED_STATUS
                            it[PaymentRefundsTable.amountMinor] = amountMinor
                        }
                    if (inserted.insertedCount == 1) {
                        trackRefundSource(bookingId, RefundSourceKind.PAYMENT_STATUS)
                    }
                    check(
                        PaymentRefundsTable
                            .selectAll()
                            .where {
                                (PaymentRefundsTable.sourcePaymentId eq id) and
                                    (PaymentRefundsTable.bookingId eq bookingId) and
                                    (PaymentRefundsTable.sourceKind eq RefundSourceKind.PAYMENT_STATUS.name) and
                                    (PaymentRefundsTable.actionId eq null) and
                                    (PaymentRefundsTable.sourceAction eq null) and
                                    (PaymentRefundsTable.sourceStatus eq REFUNDED_STATUS) and
                                    (PaymentRefundsTable.amountMinor eq amountMinor)
                            }.count() == 1L,
                    ) { "Refunded payment has no matching ledger row" }
                } else {
                    blockRefundReconciliation(bookingId, INVALID_REFUNDED_PAYMENT_AMOUNT_BLOCK)
                }
            }
        }
    }

    private fun executeCancelTransaction(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        reason: String?,
    ): CancelExecution {
        val bookingRow =
            BookingsTable
                .selectAll()
                .where { (BookingsTable.id eq bookingId) and (BookingsTable.clubId eq clubId) }
                .forUpdate()
                .limit(1)
                .firstOrNull()
                ?: return CancelExecution.NotFound
        return when (
            val claim =
                claimAction(
                    bookingId = bookingId,
                    key = idempotencyKey,
                    action = Action.CANCEL,
                    fingerprint = null,
                )
        ) {
            is ActionClaim.Existing -> replayCancel(claim.action, bookingRow)
            is ActionClaim.Owned -> {
                val terminal = cancelLockedBooking(bookingRow)
                finalizeCancelAction(claim.actionId, terminal, reason)
            }
        }
    }

    private fun cancelLockedBooking(bookingRow: ResultRow): CancelExecution {
        val bookingId = bookingRow[BookingsTable.id]
        val clubId = bookingRow[BookingsTable.clubId]
        val slotStart = bookingRow[BookingsTable.slotStart].toInstant()
        return when (val status = BookingStatus.valueOf(bookingRow[BookingsTable.status])) {
            BookingStatus.BOOKED -> {
                val updated =
                    BookingsTable.update({
                        (BookingsTable.id eq bookingId) and
                            (BookingsTable.status eq BookingStatus.BOOKED.name)
                    }) {
                        it[BookingsTable.status] = BookingStatus.CANCELLED.name
                        it[updatedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
                    }
                check(updated == 1) { "Failed to atomically cancel locked booking" }
                CancelExecution.Success(
                    clubId = clubId,
                    bookingId = bookingId,
                    slotStart = slotStart,
                    idempotent = false,
                    alreadyCancelled = false,
                )
            }
            BookingStatus.CANCELLED ->
                CancelExecution.Success(
                    clubId = clubId,
                    bookingId = bookingId,
                    slotStart = slotStart,
                    idempotent = false,
                    alreadyCancelled = true,
                )
            BookingStatus.SEATED,
            BookingStatus.NO_SHOW,
            ->
                CancelExecution.Conflict(
                    reason = "cannot cancel booking in status $status",
                    idempotent = false,
                )
        }
    }

    private fun finalizeCancelAction(
        actionId: Long,
        terminal: CancelExecution,
        requestReason: String?,
    ): CancelExecution {
        val result =
            when (terminal) {
                is CancelExecution.Success ->
                    Result(
                        status = if (terminal.alreadyCancelled) Status.ALREADY else Status.OK,
                        reason =
                            if (terminal.alreadyCancelled) {
                                requestReason ?: ALREADY_CANCELLED_REASON
                            } else {
                                requestReason
                            },
                    )
                is CancelExecution.Conflict -> Result(Status.CONFLICT, terminal.reason)
                is CancelExecution.StoredError -> Result(Status.ERROR, terminal.reason)
                CancelExecution.IdempotencyBindingMismatch,
                CancelExecution.NotFound,
                -> error("Cancel mismatch/not-found cannot finalize an owned action")
            }
        val updated =
            PaymentActionsTable.update({
                (PaymentActionsTable.id eq actionId) and
                    (PaymentActionsTable.action eq Action.CANCEL.name) and
                    (PaymentActionsTable.status eq PROCESSING_STATUS)
            }) {
                it[status] = result.status.name
                it[PaymentActionsTable.reason] = result.reason
            }
        check(updated == 1) { "Failed to finalize cancel idempotency claim" }
        return terminal
    }

    private fun replayCancel(
        existing: SavedAction,
        bookingRow: ResultRow,
    ): CancelExecution {
        val bookingId = bookingRow[BookingsTable.id]
        if (existing.bookingId != bookingId || existing.action != Action.CANCEL) {
            return CancelExecution.IdempotencyBindingMismatch
        }
        return when (existing.result.status) {
            Status.OK,
            Status.ALREADY,
            ->
                CancelExecution.Success(
                    clubId = bookingRow[BookingsTable.clubId],
                    bookingId = bookingId,
                    slotStart = bookingRow[BookingsTable.slotStart].toInstant(),
                    idempotent = true,
                    alreadyCancelled = existing.result.status == Status.ALREADY,
                )
            Status.CONFLICT ->
                CancelExecution.Conflict(
                    reason = existing.result.reason ?: CANNOT_CANCEL_REASON,
                    idempotent = true,
                )
            Status.ERROR ->
                CancelExecution.StoredError(
                    reason = existing.result.reason ?: CANNOT_CANCEL_REASON,
                    idempotent = true,
                )
        }
    }

    private fun executeRefundTransaction(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: RefundFingerprint,
    ): RefundExecution {
        val bookingExists =
            BookingsTable
                .selectAll()
                .where { (BookingsTable.id eq bookingId) and (BookingsTable.clubId eq clubId) }
                .forUpdate()
                .limit(1)
                .any()
        return if (bookingExists) {
            executeRefundForLockedBooking(bookingId, idempotencyKey, fingerprint)
        } else {
            RefundExecution.Conflict(NOTHING_TO_REFUND_REASON, idempotent = false)
        }
    }

    private fun executeRefundForLockedBooking(
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: RefundFingerprint,
    ): RefundExecution {
        val actionId =
            if (idempotencyKey.isBlank()) {
                null
            } else {
                when (
                    val claim =
                        claimAction(
                            bookingId = bookingId,
                            key = idempotencyKey,
                            action = Action.REFUND,
                            fingerprint = fingerprint,
                        )
                ) {
                    is ActionClaim.Existing -> return replayRefund(claim.action, bookingId, fingerprint)
                    is ActionClaim.Owned -> claim.actionId
                }
            }

        val paymentRows =
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.bookingId eq bookingId }
                .forUpdate()
                .toList()
        val capturedPayments = paymentRows.filter { row -> row[PaymentsTable.status] in CAPTURED_FUND_STATUSES }
        val reconciliationBlock = lockRefundReconciliation(bookingId)
        val currencies =
            capturedPayments
                .map { row -> row[PaymentsTable.currency].uppercase(Locale.ROOT) }
                .toSet()
        val terminal =
            if (reconciliationBlock != null) {
                RefundExecution.Conflict(RECONCILIATION_REQUIRED_REASON, idempotent = false)
            } else if (currencies.size > 1) {
                RefundExecution.Conflict(MIXED_CURRENCY_REASON, idempotent = false)
            } else {
                val capturedTotal = capturedPayments.sumExact { row -> row[PaymentsTable.amountMinor] }
                val refundedTotal =
                    PaymentRefundsTable
                        .selectAll()
                        .where { PaymentRefundsTable.bookingId eq bookingId }
                        .forUpdate()
                        .sumExact { row -> row[PaymentRefundsTable.amountMinor] }
                val remainder = Math.subtractExact(capturedTotal, refundedTotal)
                refundOutcome(fingerprint, remainder)
            }

        val finalized = finalizeRefundAction(actionId, terminal)
        if (finalized is RefundExecution.Success && finalized.amountMinor > 0) {
            val sourceActionId =
                actionId ?: insertInternalRefundAction(bookingId, fingerprint, finalized.amountMinor)
            insertAtomicRefund(bookingId, sourceActionId, finalized.amountMinor)
            markAtomicRefundSource(bookingId)
        }
        return finalized
    }

    private sealed interface ActionClaim {
        data class Owned(
            val actionId: Long,
        ) : ActionClaim

        data class Existing(
            val action: SavedAction,
        ) : ActionClaim
    }

    private fun claimAction(
        bookingId: UUID,
        key: String,
        action: Action,
        fingerprint: RefundFingerprint?,
    ): ActionClaim {
        val inserted =
            PaymentActionsTable.insertIgnore {
                it[PaymentActionsTable.bookingId] = bookingId
                it[PaymentActionsTable.idempotencyKey] = key
                it[PaymentActionsTable.action] = action.name
                it[PaymentActionsTable.status] = PROCESSING_STATUS
                it[PaymentActionsTable.reason] = null
                if (fingerprint != null) {
                    it[PaymentActionsTable.refundFingerprintVersion] = REFUND_FINGERPRINT_VERSION
                    it[PaymentActionsTable.refundRequestMode] = fingerprint.mode.name
                    it[PaymentActionsTable.refundRequestAmountMinor] = fingerprint.requestAmountMinor
                }
            }
        if (inserted.insertedCount == 0) {
            val existing =
                PaymentActionsTable
                    .selectAll()
                    .where { PaymentActionsTable.idempotencyKey eq key }
                    .limit(1)
                    .firstOrNull()
                    ?.toSavedAction()
                    ?: error("Failed to load competing payment action")
            return ActionClaim.Existing(existing)
        }
        val actionId =
            PaymentActionsTable
                .selectAll()
                .where { PaymentActionsTable.idempotencyKey eq key }
                .limit(1)
                .single()[PaymentActionsTable.id]
        return ActionClaim.Owned(actionId)
    }

    private fun insertInternalRefundAction(
        bookingId: UUID,
        fingerprint: RefundFingerprint,
        amountMinor: Long,
    ): Long {
        val inserted =
            PaymentActionsTable.insertIgnore {
                it[PaymentActionsTable.bookingId] = bookingId
                it[idempotencyKey] = INTERNAL_REFUND_KEY_PREFIX + UUID.randomUUID()
                it[action] = Action.REFUND.name
                it[status] = Status.OK.name
                it[reason] = amountMinor.toString()
                it[refundFingerprintVersion] = REFUND_FINGERPRINT_VERSION
                it[refundRequestMode] = fingerprint.mode.name
                it[refundRequestAmountMinor] = fingerprint.requestAmountMinor
                it[refundResultAmountMinor] = amountMinor
                it[refundSourceKind] = RefundSourceKind.ATOMIC_ACTION.name
            }
        check(inserted.insertedCount == 1) { "Failed to create internal refund action" }
        return inserted.resultedValues
            ?.singleOrNull()
            ?.get(PaymentActionsTable.id)
            ?: error("Internal refund action id was not returned")
    }

    private fun insertAtomicRefund(
        bookingId: UUID,
        actionId: Long,
        amountMinor: Long,
    ) {
        PaymentRefundsTable.insertIgnore {
            it[PaymentRefundsTable.bookingId] = bookingId
            it[sourceKind] = RefundSourceKind.ATOMIC_ACTION.name
            it[PaymentRefundsTable.actionId] = actionId
            it[sourcePaymentId] = null
            it[sourceAction] = Action.REFUND.name
            it[sourceStatus] = Status.OK.name
            it[PaymentRefundsTable.amountMinor] = amountMinor
        }
        check(
            PaymentRefundsTable
                .selectAll()
                .where {
                    (PaymentRefundsTable.actionId eq actionId) and
                        (PaymentRefundsTable.bookingId eq bookingId) and
                        (PaymentRefundsTable.sourceKind eq RefundSourceKind.ATOMIC_ACTION.name) and
                        (PaymentRefundsTable.sourcePaymentId eq null) and
                        (PaymentRefundsTable.sourceAction eq Action.REFUND.name) and
                        (PaymentRefundsTable.sourceStatus eq Status.OK.name) and
                        (PaymentRefundsTable.amountMinor eq amountMinor)
                }.count() == 1L,
        ) { "Terminal refund action has no matching ledger row" }
    }

    private fun lockRefundReconciliation(bookingId: UUID): String? {
        BookingRefundReconciliationTable.insertIgnore {
            it[BookingRefundReconciliationTable.bookingId] = bookingId
        }
        return BookingRefundReconciliationTable
            .selectAll()
            .where { BookingRefundReconciliationTable.bookingId eq bookingId }
            .forUpdate()
            .single()[BookingRefundReconciliationTable.blockedReason]
    }

    private fun markAtomicRefundSource(bookingId: UUID) {
        trackRefundSource(bookingId, RefundSourceKind.ATOMIC_ACTION)
    }

    private fun trackRefundSource(
        bookingId: UUID,
        sourceKind: RefundSourceKind,
    ) {
        BookingRefundReconciliationTable.insertIgnore {
            it[BookingRefundReconciliationTable.bookingId] = bookingId
        }
        val current =
            BookingRefundReconciliationTable
                .selectAll()
                .where { BookingRefundReconciliationTable.bookingId eq bookingId }
                .forUpdate()
                .single()
        val blockedReason =
            current[BookingRefundReconciliationTable.blockedReason]
                ?: when (sourceKind) {
                    RefundSourceKind.ATOMIC_ACTION -> null
                    RefundSourceKind.LEGACY_ACTION ->
                        AMBIGUOUS_LEGACY_SOURCES_BLOCK.takeIf {
                            current[BookingRefundReconciliationTable.hasPaymentStatusSource]
                        }
                    RefundSourceKind.PAYMENT_STATUS ->
                        AMBIGUOUS_LEGACY_SOURCES_BLOCK.takeIf {
                            current[BookingRefundReconciliationTable.hasLegacyActionSource] ||
                                current[BookingRefundReconciliationTable.hasAtomicActionSource]
                        }
                }
        BookingRefundReconciliationTable.update({
            BookingRefundReconciliationTable.bookingId eq bookingId
        }) {
            it[hasAtomicActionSource] =
                current[BookingRefundReconciliationTable.hasAtomicActionSource] ||
                sourceKind == RefundSourceKind.ATOMIC_ACTION
            it[hasLegacyActionSource] =
                current[BookingRefundReconciliationTable.hasLegacyActionSource] ||
                sourceKind == RefundSourceKind.LEGACY_ACTION
            it[hasPaymentStatusSource] =
                current[BookingRefundReconciliationTable.hasPaymentStatusSource] ||
                sourceKind == RefundSourceKind.PAYMENT_STATUS
            it[BookingRefundReconciliationTable.blockedReason] = blockedReason
            it[updatedAt] = Instant.now(clock)
        }
    }

    private fun blockRefundReconciliation(
        bookingId: UUID,
        reason: String,
    ) {
        BookingRefundReconciliationTable.insertIgnore {
            it[BookingRefundReconciliationTable.bookingId] = bookingId
        }
        BookingRefundReconciliationTable.update({
            (BookingRefundReconciliationTable.bookingId eq bookingId) and
                (BookingRefundReconciliationTable.blockedReason eq null)
        }) {
            it[blockedReason] = reason
            it[updatedAt] = Instant.now(clock)
        }
    }

    private fun finalizeRefundAction(
        actionId: Long?,
        terminal: RefundExecution,
    ): RefundExecution {
        if (actionId == null) {
            return terminal
        }
        val result = terminal.toStoredResult()
        val resultAmount = (terminal as? RefundExecution.Success)?.amountMinor
        val updated =
            PaymentActionsTable.update({
                (PaymentActionsTable.id eq actionId) and
                    (PaymentActionsTable.action eq Action.REFUND.name) and
                    (PaymentActionsTable.status eq PROCESSING_STATUS)
            }) {
                it[status] = result.status.name
                it[reason] = result.reason
                it[refundResultAmountMinor] = resultAmount
                it[refundSourceKind] =
                    if (resultAmount != null && resultAmount > 0) {
                        RefundSourceKind.ATOMIC_ACTION.name
                    } else {
                        null
                    }
            }
        check(updated == 1) { "Failed to finalize refund idempotency claim" }
        return terminal
    }

    private fun RefundExecution.toStoredResult(): Result =
        when (this) {
            is RefundExecution.Success -> Result(Status.OK, amountMinor.toString())
            is RefundExecution.Conflict -> Result(Status.CONFLICT, reason)
            is RefundExecution.Unprocessable -> Result(Status.ERROR, reason)
            RefundExecution.IdempotencyBindingMismatch,
            RefundExecution.IdempotencyPayloadMismatch,
            -> error("Idempotency mismatch is not a terminal refund result")
        }

    private fun refundOutcome(
        fingerprint: RefundFingerprint,
        remainder: Long,
    ): RefundExecution {
        if (fingerprint.mode == RefundRequestMode.ALL_REMAINING && remainder <= 0) {
            return RefundExecution.Conflict(NOTHING_TO_REFUND_REASON, idempotent = false)
        }
        val target = fingerprint.requestAmountMinor ?: remainder
        return when {
            target < 0 -> RefundExecution.Conflict(INVALID_REFUND_AMOUNT_REASON, idempotent = false)
            remainder <= 0 && target > 0 ->
                RefundExecution.Conflict(NOTHING_TO_REFUND_REASON, idempotent = false)
            target > remainder -> RefundExecution.Unprocessable(EXCEEDS_REMAINDER_REASON, idempotent = false)
            else ->
                RefundExecution.Success(
                    amountMinor = target,
                    remainingMinor = Math.subtractExact(remainder, target),
                    idempotent = false,
                )
        }
    }

    private fun replayRefund(
        existing: SavedAction,
        bookingId: UUID,
        fingerprint: RefundFingerprint,
    ): RefundExecution =
        when {
            existing.bookingId != bookingId || existing.action != Action.REFUND ->
                RefundExecution.IdempotencyBindingMismatch
            existing.refundFingerprint != fingerprint -> RefundExecution.IdempotencyPayloadMismatch
            else -> replayStoredRefund(existing)
        }

    private fun replayStoredRefund(existing: SavedAction): RefundExecution =
        when (existing.result.status) {
            Status.OK ->
                existing.refundResultAmountMinor
                    ?.let { amount ->
                        RefundExecution.Success(amountMinor = amount, remainingMinor = null, idempotent = true)
                    } ?: RefundExecution.IdempotencyPayloadMismatch
            Status.CONFLICT ->
                RefundExecution.Conflict(
                    reason = existing.result.reason ?: NOTHING_TO_REFUND_REASON,
                    idempotent = true,
                )
            Status.ERROR ->
                RefundExecution.Unprocessable(
                    reason = existing.result.reason ?: REFUND_ERROR_REASON,
                    idempotent = true,
                )
            Status.ALREADY -> RefundExecution.IdempotencyPayloadMismatch
        }

    private fun validateRefundFingerprint(fingerprint: RefundFingerprint) {
        val valid =
            when (fingerprint.mode) {
                RefundRequestMode.EXPLICIT -> fingerprint.requestAmountMinor != null
                RefundRequestMode.ALL_REMAINING -> fingerprint.requestAmountMinor == null
            }
        require(valid) { "invalid refund fingerprint" }
    }

    private inline fun Iterable<ResultRow>.sumExact(amount: (ResultRow) -> Long): Long =
        fold(0L) { total, row -> Math.addExact(total, amount(row)) }

    private fun ResultRow.toRecord(): PaymentRecord =
        PaymentRecord(
            id = this[PaymentsTable.id],
            bookingId = this[PaymentsTable.bookingId],
            provider = this[PaymentsTable.provider],
            currency = this[PaymentsTable.currency],
            amountMinor = this[PaymentsTable.amountMinor],
            status = this[PaymentsTable.status],
            payload = this[PaymentsTable.payload],
            externalId = this[PaymentsTable.externalId],
            telegramPaymentChargeId = this[PaymentsTable.telegramPaymentChargeId],
            providerPaymentChargeId = this[PaymentsTable.providerPaymentChargeId],
            idempotencyKey = this[PaymentsTable.idempotencyKey],
            createdAt = this[PaymentsTable.createdAt],
            updatedAt = this[PaymentsTable.updatedAt],
        )

    private fun ResultRow.toSavedAction(): SavedAction =
        SavedAction(
            id = this[PaymentActionsTable.id],
            bookingId = this[PaymentActionsTable.bookingId],
            idempotencyKey = this[PaymentActionsTable.idempotencyKey],
            action = Action.valueOf(this[PaymentActionsTable.action]),
            result =
                Result(
                    status = Status.valueOf(this[PaymentActionsTable.status]),
                    reason = this[PaymentActionsTable.reason],
                ),
            createdAt = this[PaymentActionsTable.createdAt],
            refundFingerprint = toRefundFingerprint(),
            refundResultAmountMinor = this[PaymentActionsTable.refundResultAmountMinor],
            refundSourceKind =
                this[PaymentActionsTable.refundSourceKind]
                    ?.let { stored -> RefundSourceKind.entries.firstOrNull { it.name == stored } },
        )

    private fun ResultRow.toRefundFingerprint(): RefundFingerprint? {
        val version = this[PaymentActionsTable.refundFingerprintVersion]
        val mode =
            this[PaymentActionsTable.refundRequestMode]
                ?.let { stored -> RefundRequestMode.entries.firstOrNull { it.name == stored } }
        val amount = this[PaymentActionsTable.refundRequestAmountMinor]
        val validShape =
            when (mode) {
                RefundRequestMode.EXPLICIT -> amount != null
                RefundRequestMode.ALL_REMAINING -> amount == null
                null -> false
            }
        return if (version == REFUND_FINGERPRINT_VERSION && mode != null && validShape) {
            RefundFingerprint(mode = mode, requestAmountMinor = amount)
        } else {
            null
        }
    }

    private companion object {
        private const val CAPTURED_STATUS = "CAPTURED"
        private const val REFUNDED_STATUS = "REFUNDED"
        private const val PROCESSING_STATUS = "PROCESSING"
        private const val REFUND_FINGERPRINT_VERSION = 1
        private const val ALREADY_CANCELLED_REASON = "already_cancelled"
        private const val CANNOT_CANCEL_REASON = "cannot cancel booking"
        private const val NOTHING_TO_REFUND_REASON = "nothing to refund"
        private const val EXCEEDS_REMAINDER_REASON = "exceeds remainder"
        private const val INVALID_REFUND_AMOUNT_REASON = "invalid refund amount"
        private const val MIXED_CURRENCY_REASON = "captured payments currency mismatch"
        private const val RECONCILIATION_REQUIRED_REASON = "reconciliation_required"
        private const val REFUND_ERROR_REASON = "refund error"
        private const val INTERNAL_REFUND_KEY_PREFIX = "internal:refund:"
        private const val INVALID_REFUNDED_PAYMENT_AMOUNT_BLOCK = "INVALID_REFUNDED_PAYMENT_AMOUNT"
        private const val AMBIGUOUS_LEGACY_SOURCES_BLOCK = "AMBIGUOUS_LEGACY_REFUND_SOURCES"
        private val CAPTURED_FUND_STATUSES = setOf(CAPTURED_STATUS, REFUNDED_STATUS)
    }
}
