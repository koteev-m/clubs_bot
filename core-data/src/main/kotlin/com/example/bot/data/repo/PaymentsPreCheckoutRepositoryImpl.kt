package com.example.bot.data.repo

import com.example.bot.data.booking.BookingsTable
import com.example.bot.payments.PaymentsPreCheckoutRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

class PaymentsPreCheckoutRepositoryImpl(
    private val db: Database,
) : PaymentsPreCheckoutRepository {
    override suspend fun findBookingSnapshot(bookingId: UUID): PaymentsPreCheckoutRepository.BookingSnapshot? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            BookingsTable
                .selectAll()
                .where { BookingsTable.id eq bookingId }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    PaymentsPreCheckoutRepository.BookingSnapshot(
                        status = row[BookingsTable.status],
                        guestUserId = row[BookingsTable.guestUserId],
                        arrivalBy = row[BookingsTable.arrivalBy]?.toInstant(),
                    )
                }
        }
}
