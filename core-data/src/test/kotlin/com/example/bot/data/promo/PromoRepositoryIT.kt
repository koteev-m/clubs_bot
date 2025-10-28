package com.example.bot.data.promo

import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.club.PostgresClubIntegrationTest
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateResult
import com.example.bot.promo.PromoAttributionError
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionResult
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.promo.PromoLinkResult
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PromoRepositoryIT : PostgresClubIntegrationTest() {
    private val fixedInstant: Instant = Instant.parse("2024-09-01T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val bookingSlotDuration: Duration = Duration.ofHours(3)

    private lateinit var promoLinkRepository: PromoLinkRepository
    private lateinit var promoAttributionRepository: PromoAttributionRepository
    private lateinit var bookingTemplateRepository: BookingTemplateRepository

    @BeforeEach
    fun setUpRepositories() {
        promoLinkRepository = PromoLinkRepositoryImpl(database, fixedClock)
        promoAttributionRepository = PromoAttributionRepositoryImpl(database, fixedClock)
        bookingTemplateRepository = BookingTemplateRepositoryImpl(database, fixedClock)
        transaction(database) {
            exec("TRUNCATE TABLE promo_attribution, promo_links, booking_templates RESTART IDENTITY CASCADE")
        }
    }

    @Test
    fun `promo link lifecycle`() =
        runBlocking {
            val promoterId = insertUser(username = "promo", displayName = "Promo User")
            val clubId = insertClub(name = "Nebula")

            val created =
                promoLinkRepository.issueLink(
                    promoterUserId = promoterId,
                    clubId = clubId,
                    utmSource = "telegram",
                    utmMedium = "bot",
                    utmCampaign = "launch",
                    utmContent = "banner",
                )

            assertEquals(promoterId, created.promoterUserId)
            assertEquals(fixedInstant, created.createdAt)

            val fetched = promoLinkRepository.get(created.id)
            assertEquals(created, fetched)

            val listed = promoLinkRepository.listByPromoter(promoterId, clubId)
            assertEquals(listOf(created), listed)

            val deactivateResult = promoLinkRepository.deactivate(created.id)
            assertTrue(deactivateResult is PromoLinkResult.Success)

            val afterRemoval = promoLinkRepository.get(created.id)
            assertNull(afterRemoval)

            val missing = promoLinkRepository.deactivate(created.id)
            assertTrue(missing is PromoLinkResult.Failure)
        }

    @Test
    fun `promo attribution enforces uniqueness per booking`() =
        runBlocking {
            val promoterId = insertUser(username = "owner", displayName = "Owner")
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Showcase",
                    startAt = fixedInstant.plus(Duration.ofHours(2)),
                    endAt = fixedInstant.plus(Duration.ofHours(6)),
                )
            val tableNumber = 12
            val tableCapacity = 6
            val guestCount = 4
            val minDeposit = BigDecimal("150.00")
            val tableId =
                insertTable(
                    clubId = clubId,
                    tableNumber = tableNumber,
                    capacity = tableCapacity,
                    minDeposit = minDeposit,
                )

            val promoLink =
                promoLinkRepository.issueLink(
                    promoterUserId = promoterId,
                    clubId = clubId,
                    utmSource = "telegram",
                    utmMedium = "bot",
                    utmCampaign = "launch",
                    utmContent = null,
                )

            val bookingId =
                insertBooking(
                    clubId = clubId,
                    tableId = tableId,
                    eventId = eventId,
                    tableNumber = tableNumber,
                    guests = guestCount,
                    promoterUserId = promoterId,
                    deposit = minDeposit,
                )

            val attached =
                promoAttributionRepository.attachUnique(
                    bookingId = bookingId,
                    promoLinkId = promoLink.id,
                    promoterUserId = promoterId,
                    utmSource = "telegram",
                    utmMedium = "bot",
                    utmCampaign = "launch",
                    utmContent = "banner",
                )
            assertTrue(attached is PromoAttributionResult.Success)

            val stored = (attached as PromoAttributionResult.Success).value
            assertEquals(bookingId, stored.bookingId)
            assertEquals(fixedInstant, stored.createdAt)

            val fetched = promoAttributionRepository.findByBooking(bookingId)
            assertEquals(stored, fetched)

            val duplicate =
                promoAttributionRepository.attachUnique(
                    bookingId = bookingId,
                    promoLinkId = promoLink.id,
                    promoterUserId = promoterId,
                    utmSource = "telegram",
                    utmMedium = "bot",
                    utmCampaign = "launch",
                    utmContent = "banner",
                )
            assertTrue(duplicate is PromoAttributionResult.Failure)
            assertEquals(PromoAttributionError.AlreadyAttributed, (duplicate as PromoAttributionResult.Failure).error)
        }

    @Test
    fun `booking template lifecycle`() =
        runBlocking {
            val promoterId = insertUser(username = "templater", displayName = "Template Owner")
            val clubId = insertClub(name = "Cosmos")
            val initialCapacity = 4
            val updatedCapacity = 6

            val created =
                bookingTemplateRepository.create(
                    promoterUserId = promoterId,
                    clubId = clubId,
                    tableCapacityMin = initialCapacity,
                    notes = "VIP",
                )
            assertEquals(fixedInstant, created.createdAt)
            assertTrue(created.isActive)

            val fetched = bookingTemplateRepository.get(created.id)
            assertEquals(created, fetched)

            val byOwner = bookingTemplateRepository.listByOwner(promoterId)
            assertEquals(listOf(created), byOwner)

            val byClub = bookingTemplateRepository.listByClub(clubId)
            assertEquals(listOf(created), byClub)

            val updateResult =
                bookingTemplateRepository.update(
                    id = created.id,
                    tableCapacityMin = updatedCapacity,
                    notes = "Updated",
                    isActive = true,
                )
            assertTrue(updateResult is BookingTemplateResult.Success)
            val updated = (updateResult as BookingTemplateResult.Success).value
            assertEquals(updatedCapacity, updated.tableCapacityMin)
            assertEquals("Updated", updated.notes)

            val signatureResult = bookingTemplateRepository.applyTemplateSignature(updated.id)
            assertTrue(signatureResult is BookingTemplateResult.Success)
            val signature = (signatureResult as BookingTemplateResult.Success).value
            assertEquals(updated.id, signature.templateId)
            assertTrue(signature.value.isNotBlank())

            val secondSignature = bookingTemplateRepository.applyTemplateSignature(updated.id)
            assertTrue(secondSignature is BookingTemplateResult.Success)
            assertEquals(signature.value, (secondSignature as BookingTemplateResult.Success).value.value)

            val deactivateResult = bookingTemplateRepository.deactivate(updated.id)
            assertTrue(deactivateResult is BookingTemplateResult.Success)

            val activeTemplates = bookingTemplateRepository.listByClub(clubId)
            assertTrue(activeTemplates.isEmpty())

            val allTemplates = bookingTemplateRepository.listByClub(clubId, onlyActive = false)
            assertEquals(1, allTemplates.size)
            assertFalse(allTemplates.first().isActive)

            val missing = bookingTemplateRepository.deactivate(updated.id + 1)
            assertTrue(missing is BookingTemplateResult.Failure)
        }

    private fun insertBooking(
        clubId: Long,
        tableId: Long,
        eventId: Long,
        tableNumber: Int,
        guests: Int,
        promoterUserId: Long?,
        deposit: BigDecimal,
    ): UUID {
        val bookingId = UUID.randomUUID()
        val slotStart = fixedInstant.plus(Duration.ofHours(4))
        val slotEnd = slotStart.plus(bookingSlotDuration)
        val timestamp = fixedInstant.atOffset(ZoneOffset.UTC)
        transaction(database) {
            BookingsTable.insert {
                it[BookingsTable.id] = bookingId
                it[BookingsTable.eventId] = eventId
                it[BookingsTable.clubId] = clubId
                it[BookingsTable.tableId] = tableId
                it[BookingsTable.tableNumber] = tableNumber
                it[BookingsTable.guestUserId] = null
                it[BookingsTable.guestName] = null
                it[BookingsTable.phoneE164] = null
                it[BookingsTable.promoterUserId] = promoterUserId
                it[BookingsTable.guestsCount] = guests
                it[BookingsTable.minDeposit] = deposit
                it[BookingsTable.totalDeposit] = deposit
                it[BookingsTable.slotStart] = slotStart.atOffset(ZoneOffset.UTC)
                it[BookingsTable.slotEnd] = slotEnd.atOffset(ZoneOffset.UTC)
                it[BookingsTable.arrivalBy] = null
                it[BookingsTable.status] = "BOOKED"
                it[BookingsTable.qrSecret] = UUID.randomUUID().toString().replace("-", "")
                it[BookingsTable.idempotencyKey] = UUID.randomUUID().toString()
                it[BookingsTable.createdAt] = timestamp
                it[BookingsTable.updatedAt] = timestamp
            }
        }
        return bookingId
    }
}
