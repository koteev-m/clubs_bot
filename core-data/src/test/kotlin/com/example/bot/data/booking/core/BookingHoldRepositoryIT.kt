package com.example.bot.data.booking.core

import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.Clubs
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@RequiresDocker
@Tag("it")
class BookingHoldRepositoryIT : PostgresIntegrationTest() {
    @Test
    fun `create hold respects ttl`() =
        runBlocking {
            val context = seedData()
            val clock = Clock.fixed(context.now, ZoneOffset.UTC)
            val repo = BookingHoldRepository(database, clock)
            val ttl = Duration.ofMinutes(30)
            val result =
                repo.createHold(
                    context.tableId,
                    context.slotStart,
                    context.slotEnd,
                    guestsCount = 2,
                    ttl = ttl,
                    idempotencyKey = "hold-ttl",
                )
            val hold = (result as BookingCoreResult.Success).value
            assertEquals(context.now.plus(ttl), hold.expiresAt)
            assertEquals(2, hold.guests)
            assertEquals(context.clubId, hold.clubId)
            assertEquals("hold-ttl", hold.idempotencyKey)
        }

    @Test
    fun `prolong hold extends expiry`() =
        runBlocking {
            val context = seedData()
            val createClock = Clock.fixed(context.now, ZoneOffset.UTC)
            val repoCreate = BookingHoldRepository(database, createClock)
            val created =
                repoCreate.createHold(
                    context.tableId,
                    context.slotStart,
                    context.slotEnd,
                    guestsCount = 3,
                    ttl = Duration.ofMinutes(10),
                    idempotencyKey = "hold-prolong",
                ) as BookingCoreResult.Success
            val newClock = Clock.fixed(context.now.plusSeconds(300), ZoneOffset.UTC)
            val repoProlong = BookingHoldRepository(database, newClock)
            val prolonged =
                repoProlong.prolongHold(created.value.id, Duration.ofMinutes(20)) as BookingCoreResult.Success
            assertEquals(context.now.plusSeconds(300).plus(Duration.ofMinutes(20)), prolonged.value.expiresAt)
        }

    @Test
    fun `consume hold removes record`() =
        runBlocking {
            val context = seedData()
            val repo = BookingHoldRepository(database, Clock.fixed(context.now, ZoneOffset.UTC))
            val created =
                repo.createHold(
                    context.tableId,
                    context.slotStart,
                    context.slotEnd,
                    guestsCount = 2,
                    ttl = Duration.ofMinutes(5),
                    idempotencyKey = "hold-consume",
                ) as BookingCoreResult.Success
            val consumed = repo.consumeHold(created.value.id) as BookingCoreResult.Success
            assertEquals(created.value.id, consumed.value.id)
            val stillThere =
                transaction(database) {
                    com.example.bot.data.booking.BookingHoldsTable
                        .selectAll()
                        .where { com.example.bot.data.booking.BookingHoldsTable.id eq created.value.id }
                        .empty()
                }
            assertTrue(stillThere)
        }

    @Test
    fun `cleanup removes expired holds`() =
        runBlocking {
            val context = seedData()
            val repo = BookingHoldRepository(database, Clock.fixed(context.now, ZoneOffset.UTC))
            repo.createHold(
                context.tableId,
                context.slotStart,
                context.slotEnd,
                guestsCount = 1,
                ttl = Duration.ofMinutes(1),
                idempotencyKey = "hold-expired",
            )
            val active =
                repo.createHold(
                    context.tableId,
                    context.slotStart,
                    context.slotEnd,
                    guestsCount = 1,
                    ttl = Duration.ofHours(1),
                    idempotencyKey = "hold-active",
                ) as BookingCoreResult.Success
            val removed = repo.cleanupExpired(context.now.plusSeconds(600))
            assertEquals(1, removed)
            val remaining =
                com.example.bot.data.booking.BookingHoldsTable
                    .selectAll()
                    .where { com.example.bot.data.booking.BookingHoldsTable.id eq active.value.id }
                    .empty()
            assertFalse(remaining)
        }

    private fun seedData(): SeedContext {
        val now = Instant.parse("2025-02-01T00:00:00Z")
        val slotStart = Instant.parse("2025-02-02T20:00:00Z")
        val slotEnd = Instant.parse("2025-02-02T23:00:00Z")
        return transaction(database) {
            val clubIdEntity =
                Clubs.insert {
                    it[name] = "Club"
                    it[description] = "Hold"
                    it[timezone] = "UTC"
                } get Clubs.id
            val clubId = clubIdEntity.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubId
                    it[zoneId] = null
                    it[tableNumber] = 10
                    it[capacity] = 6
                    it[minDeposit] = BigDecimal("50.00")
                    it[active] = true
                } get TablesTable.id
            EventsTable.insert {
                it[EventsTable.clubId] = clubId
                it[startAt] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
                it[endAt] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
                it[title] = "Show"
                it[isSpecial] = false
                it[posterUrl] = null
            }
            SeedContext(
                now = now,
                clubId = clubId,
                tableId = tableId,
                slotStart = slotStart,
                slotEnd = slotEnd,
            )
        }
    }

    private data class SeedContext(
        val now: Instant,
        val clubId: Long,
        val tableId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
    )
}
