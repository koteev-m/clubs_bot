package com.example.bot.data.finance

import com.example.bot.data.TestDatabase
import com.example.bot.data.booking.AllocationInput
import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.security.UsersTable
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShiftReportRepositoryTest {
    private lateinit var testDb: TestDatabase

    @BeforeEach
    fun setUp() {
        testDb = TestDatabase()
    }

    @AfterEach
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun `getOrCreateDraft is idempotent`() =
        runBlocking {
            val clubId = insertClub()
            val repo = ShiftReportRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")

            val first = repo.getOrCreateDraft(clubId, nightStart)
            val second = repo.getOrCreateDraft(clubId, nightStart)

            assertEquals(first.id, second.id)
            val count =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    ShiftReportsTable.selectAll().count()
                }
            assertEquals(1, count)
        }

    @Test
    fun `close updates status and prevents second close`() =
        runBlocking {
            val clubId = insertClub()
            val actorId = insertUser()
            val templateRepo = ShiftReportTemplateRepository(testDb.database)
            val group = templateRepo.createRevenueGroup(clubId, "Bar", 0)
            val article =
                templateRepo.createRevenueArticle(
                    clubId = clubId,
                    groupId = group.id,
                    name = "Cocktails",
                    includeInTotal = true,
                    showSeparately = false,
                    orderIndex = 0,
                )
            val bracelet = templateRepo.createBraceletType(clubId, "VIP", 0)
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))

            val updated =
                reportRepo.updateDraft(
                    report.id,
                    ShiftReportUpdatePayload(
                        peopleWomen = 1,
                        peopleMen = 2,
                        peopleRejected = 0,
                        comment = "ok",
                        bracelets = listOf(ShiftReportBraceletInput(bracelet.id, 3)),
                        revenueEntries =
                            listOf(
                                RevenueEntryInput(
                                    articleId = article.id,
                                    name = null,
                                    groupId = null,
                                    amountMinor = 1000,
                                    includeInTotal = null,
                                    showSeparately = null,
                                    orderIndex = null,
                                ),
                            ),
                    ),
                )

            assertNotNull(updated)
            val closedAt = Instant.parse("2024-03-02T02:00:00Z")
            val firstClose = reportRepo.close(report.id, actorId, closedAt)
            val secondClose = reportRepo.close(report.id, actorId, closedAt)

            assertTrue(firstClose)
            assertFalse(secondClose)
            val closedRow =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    ShiftReportsTable
                        .selectAll()
                        .where { ShiftReportsTable.id eq report.id }
                        .limit(1)
                        .firstOrNull()
                }
            assertEquals(ShiftReportStatus.CLOSED.name, closedRow?.get(ShiftReportsTable.status))
            assertEquals(closedAt.toOffsetDateTimeUtc(), closedRow?.get(ShiftReportsTable.closedAt))
            assertEquals(actorId, closedRow?.get(ShiftReportsTable.closedBy))
        }

    @Test
    fun `calculations helpers return totals and non totals`() {
        val entries =
            listOf(
                ShiftReportRevenueEntry(1, 10, null, "Bar", 1, 100, true, false, 0),
                ShiftReportRevenueEntry(2, 10, null, "Kitchen", 1, 200, true, false, 1),
                ShiftReportRevenueEntry(3, 10, null, "Tips", 2, 50, false, true, 2),
            )

        val totals = totalsPerGroup(entries, mapOf(1L to "Main"))
        val totalRevenue = totalRevenue(entries)
        val nonTotals = nonTotalIndicators(entries)

        assertEquals(1, totals.size)
        assertEquals(300, totals.first().amountMinor)
        assertEquals("Main", totals.first().groupName)
        assertEquals(300, totalRevenue)
        assertEquals(1, nonTotals.all.size)
        assertEquals(1, nonTotals.showSeparately.size)
    }

    @Test
    fun `deposit hints return summary when deposits exist`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val sessionRepo = TableSessionRepository(testDb.database)
            val depositRepo = TableDepositRepository(testDb.database)
            val reportRepo = ShiftReportRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)

            depositRepo.createDeposit(
                clubId = clubId,
                nightStartUtc = nightStart,
                tableId = tableId,
                sessionId = session.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 100,
                allocations =
                    listOf(
                        AllocationInput(categoryCode = "BAR", amountMinor = 60),
                        AllocationInput(categoryCode = "FOOD", amountMinor = 40),
                    ),
                actorId = actorId,
                now = now,
            )
            depositRepo.createDeposit(
                clubId = clubId,
                nightStartUtc = nightStart,
                tableId = tableId,
                sessionId = session.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 50,
                allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 50)),
                actorId = actorId,
                now = now,
            )

            val hints = reportRepo.getDepositHints(clubId, nightStart)

            assertEquals(150, hints.sumDepositsForNight)
            assertEquals(2, hints.allocationSummaryForNight.size)
            assertEquals(110, hints.allocationSummaryForNight["BAR"])
            assertEquals(40, hints.allocationSummaryForNight["FOOD"])
        }

    @Test
    fun `updateDraft rejects ad-hoc revenue entry with missing fields`() =
        runBlocking {
            val clubId = insertClub()
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))
            assertThrowsSuspend<IllegalArgumentException> {
                reportRepo.updateDraft(
                    report.id,
                    ShiftReportUpdatePayload(
                        peopleWomen = 0,
                        peopleMen = 0,
                        peopleRejected = 0,
                        comment = null,
                        bracelets = emptyList(),
                        revenueEntries =
                            listOf(
                                RevenueEntryInput(
                                    articleId = null,
                                    name = "Adhoc",
                                    groupId = null,
                                    amountMinor = 100,
                                    includeInTotal = null,
                                    showSeparately = null,
                                    orderIndex = null,
                                ),
                            ),
                    ),
                )
            }
        }

    @Test
    fun `updateDraft rejects unknown revenue article`() =
        runBlocking {
            val clubId = insertClub()
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))
            assertThrowsSuspend<IllegalArgumentException> {
                reportRepo.updateDraft(
                    report.id,
                    ShiftReportUpdatePayload(
                        peopleWomen = 0,
                        peopleMen = 0,
                        peopleRejected = 0,
                        comment = null,
                        bracelets = emptyList(),
                        revenueEntries =
                            listOf(
                                RevenueEntryInput(
                                    articleId = 999,
                                    name = null,
                                    groupId = null,
                                    amountMinor = 100,
                                    includeInTotal = null,
                                    showSeparately = null,
                                    orderIndex = null,
                                ),
                            ),
                    ),
                )
            }
        }

    @Test
    fun `updateDraft rejects duplicate bracelet types`() =
        runBlocking {
            val clubId = insertClub()
            val templateRepo = ShiftReportTemplateRepository(testDb.database)
            val bracelet = templateRepo.createBraceletType(clubId, "VIP", 0)
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))
            assertThrowsSuspend<IllegalArgumentException> {
                reportRepo.updateDraft(
                    report.id,
                    ShiftReportUpdatePayload(
                        peopleWomen = 0,
                        peopleMen = 0,
                        peopleRejected = 0,
                        comment = null,
                        bracelets =
                            listOf(
                                ShiftReportBraceletInput(bracelet.id, 1),
                                ShiftReportBraceletInput(bracelet.id, 2),
                            ),
                        revenueEntries = emptyList(),
                    ),
                )
            }
        }

    @Test
    fun `updateDraft rejects duplicate revenue article entries`() =
        runBlocking {
            val clubId = insertClub()
            val templateRepo = ShiftReportTemplateRepository(testDb.database)
            val group = templateRepo.createRevenueGroup(clubId, "Bar", 0)
            val article =
                templateRepo.createRevenueArticle(
                    clubId = clubId,
                    groupId = group.id,
                    name = "Cocktails",
                    includeInTotal = true,
                    showSeparately = false,
                    orderIndex = 0,
                )
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))
            assertThrowsSuspend<IllegalArgumentException> {
                reportRepo.updateDraft(
                    report.id,
                    ShiftReportUpdatePayload(
                        peopleWomen = 0,
                        peopleMen = 0,
                        peopleRejected = 0,
                        comment = null,
                        bracelets = emptyList(),
                        revenueEntries =
                            listOf(
                                RevenueEntryInput(
                                    articleId = article.id,
                                    name = null,
                                    groupId = null,
                                    amountMinor = 100,
                                    includeInTotal = null,
                                    showSeparately = null,
                                    orderIndex = null,
                                ),
                                RevenueEntryInput(
                                    articleId = article.id,
                                    name = null,
                                    groupId = null,
                                    amountMinor = 200,
                                    includeInTotal = null,
                                    showSeparately = null,
                                    orderIndex = null,
                                ),
                            ),
                    ),
                )
            }
        }

    @Test
    fun `updateDraft rejects revenue article group mismatch`() =
        runBlocking {
            val clubId = insertClub()
            val templateRepo = ShiftReportTemplateRepository(testDb.database)
            val group = templateRepo.createRevenueGroup(clubId, "Bar", 0)
            val article =
                templateRepo.createRevenueArticle(
                    clubId = clubId,
                    groupId = group.id,
                    name = "Cocktails",
                    includeInTotal = true,
                    showSeparately = false,
                    orderIndex = 0,
                )
            val reportRepo = ShiftReportRepository(testDb.database)
            val report = reportRepo.getOrCreateDraft(clubId, Instant.parse("2024-03-01T20:00:00Z"))

            val ex =
                assertThrowsSuspend<IllegalArgumentException> {
                    reportRepo.updateDraft(
                        report.id,
                        ShiftReportUpdatePayload(
                            peopleWomen = 0,
                            peopleMen = 0,
                            peopleRejected = 0,
                            comment = null,
                            bracelets = emptyList(),
                            revenueEntries =
                                listOf(
                                    RevenueEntryInput(
                                        articleId = article.id,
                                        name = null,
                                        groupId = 999999,
                                        amountMinor = 100,
                                        includeInTotal = null,
                                        showSeparately = null,
                                        orderIndex = null,
                                    ),
                                ),
                        ),
                    )
                }
            assertEquals("revenue_article_group_mismatch", ex.message)
        }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (ex: Throwable) {
            if (ex is T) {
                return ex
            }
            throw ex
        }
        return fail("Expected ${T::class.java.name} to be thrown")
    }

    private suspend fun insertClub(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            Clubs.insert {
                it[name] = "Test Club"
                it[timezone] = "UTC"
                it[city] = "City"
                it[isActive] = true
            }[Clubs.id].value.toLong()
        }

    private suspend fun insertUser(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            UsersTable.insert {
                it[telegramUserId] = System.currentTimeMillis()
                it[username] = "tester"
                it[displayName] = "Tester"
                it[phoneE164] = null
            }[UsersTable.id]
        }

    private suspend fun insertTable(clubId: Long): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            TablesTable.insert {
                it[TablesTable.clubId] = clubId
                it[TablesTable.zoneId] = null
                it[TablesTable.tableNumber] = 1
                it[TablesTable.capacity] = 4
                it[TablesTable.minDeposit] = java.math.BigDecimal.ZERO
                it[TablesTable.active] = true
            }[TablesTable.id]
        }
}
