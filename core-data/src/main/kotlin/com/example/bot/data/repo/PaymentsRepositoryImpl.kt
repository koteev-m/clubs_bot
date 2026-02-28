package com.example.bot.data.repo

import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.PaymentsRepository.Action
import com.example.bot.payments.PaymentsRepository.PaymentRecord
import com.example.bot.payments.PaymentsRepository.Result
import com.example.bot.payments.PaymentsRepository.Result.Status
import com.example.bot.payments.PaymentsRepository.SavedAction
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Exposed-based implementation of [PaymentsRepository].
 */
class PaymentsRepositoryImpl(
    private val db: Database,
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
    }

    object PaymentActionsTable : Table("payment_actions") {
        val id = long("id").autoIncrement()
        val bookingId = uuid("booking_id")
        val idempotencyKey = text("idempotency_key").uniqueIndex()
        val action = text("action")
        val status = text("status")
        val reason = text("reason").nullable()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(id)

        init {
            index(isUnique = false, columns = arrayOf(bookingId))
        }
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
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val paymentRow =
                PaymentsTable
                    .selectAll()
                    .where { PaymentsTable.id eq id }
                    .firstOrNull()
                    ?: return@newSuspendedTransaction PaymentsRepository.CaptureResult.PAYMENT_NOT_FOUND

            val conflictClause = chargeConflictCondition(id, telegramPaymentChargeId, providerPaymentChargeId)
            if (conflictClause != null) {
                val conflictingChargeExists =
                    PaymentsTable
                        .selectAll()
                        .where { conflictClause }
                        .limit(1)
                        .any()
                if (conflictingChargeExists) {
                    return@newSuspendedTransaction PaymentsRepository.CaptureResult.CHARGE_CONFLICT
                }
            }

            if (paymentRow[PaymentsTable.status] == CAPTURED_STATUS) {
                return@newSuspendedTransaction PaymentsRepository.CaptureResult.ALREADY_CAPTURED
            }

            PaymentsTable.update({ PaymentsTable.id eq id }) {
                it[status] = CAPTURED_STATUS
                it[PaymentsTable.externalId] = paymentRow[PaymentsTable.externalId] ?: externalId
                it[PaymentsTable.telegramPaymentChargeId] = telegramPaymentChargeId ?: paymentRow[PaymentsTable.telegramPaymentChargeId]
                it[PaymentsTable.providerPaymentChargeId] = providerPaymentChargeId ?: paymentRow[PaymentsTable.providerPaymentChargeId]
            }
            PaymentsRepository.CaptureResult.CAPTURED
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
        updateStatus(id, "REFUNDED", externalId)
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
            PaymentActionsTable
                .insert {
                    it[PaymentActionsTable.bookingId] = bookingId
                    it[PaymentActionsTable.idempotencyKey] = key
                    it[PaymentActionsTable.action] = action.name
                    it[PaymentActionsTable.status] = result.status.name
                    it[PaymentActionsTable.reason] = result.reason
                }.resultedValues!!
                .first()
                .toSavedAction()
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

    override suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    ) = newSuspendedTransaction(context = Dispatchers.IO, db = db) {
        PaymentsTable.update({ PaymentsTable.id eq id }) {
            it[PaymentsTable.status] = status
            it[PaymentsTable.externalId] = externalId
        }
        Unit
    }

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

    private fun chargeLookupCondition(
        telegramPaymentChargeId: String?,
        providerPaymentChargeId: String?,
    ): Op<Boolean>? {
        val telegramClause = telegramPaymentChargeId?.let { PaymentsTable.telegramPaymentChargeId eq it }
        val providerClause = providerPaymentChargeId?.let { PaymentsTable.providerPaymentChargeId eq it }
        return when {
            telegramClause != null && providerClause != null -> telegramClause or providerClause
            telegramClause != null -> telegramClause
            else -> providerClause
        }
    }

    private fun chargeConflictCondition(
        paymentId: UUID,
        telegramPaymentChargeId: String?,
        providerPaymentChargeId: String?,
    ): Op<Boolean>? {
        val lookupClause = chargeLookupCondition(telegramPaymentChargeId, providerPaymentChargeId) ?: return null
        return (PaymentsTable.id neq paymentId) and lookupClause
    }

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
        )

    private companion object {
        private const val CAPTURED_STATUS = "CAPTURED"
    }
}
