package com.example.bot.analytics

import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.finance.ShiftReport
import com.example.bot.data.finance.ShiftReportDetails
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportRevenueEntry
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.stories.AnalyticsSnapshot
import com.example.bot.data.stories.AnalyticsSnapshotRepository
import com.example.bot.data.stories.GuestSegmentsRepository
import com.example.bot.data.stories.PostEventStory
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.data.stories.PostEventStoryStatus
import com.example.bot.data.stories.SegmentComputationResult
import com.example.bot.data.stories.SegmentType
import com.example.bot.data.visits.VisitRepository
import com.example.bot.owner.AttendanceChannel
import com.example.bot.owner.AttendanceChannels
import com.example.bot.owner.AttendanceHealth
import com.example.bot.owner.OwnerHealthService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AdminAnalyticsSnapshotServiceTest {
    private val now = Instant.parse("2024-06-02T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `recompute builds stable aggregation payload for golden dataset`() {
        val ownerHealthService = mockk<OwnerHealthService>()
        val visitRepository = mockk<VisitRepository>()
        val tableDepositRepository = mockk<TableDepositRepository>()
        val shiftReportRepository = mockk<ShiftReportRepository>()
        val storyRepository = mockk<PostEventStoryRepository>()
        val guestSegmentsRepository = mockk<GuestSegmentsRepository>()
        val snapshotRepository = mockk<AnalyticsSnapshotRepository>()

        coEvery { ownerHealthService.attendanceForNight(1, any()) } returns attendanceHealth()
        coEvery { visitRepository.countNightUniqueVisitors(1, any()) } returns 10
        coEvery { visitRepository.countNightEarlyVisits(1, any()) } returns 3
        coEvery { visitRepository.countNightTableNights(1, any()) } returns 2
        coEvery { tableDepositRepository.sumDepositsForNight(1, any()) } returns 15000
        coEvery { tableDepositRepository.allocationSummaryForNight(1, any()) } returns mapOf("BAR" to 5000)
        coEvery { shiftReportRepository.getByClubAndNight(1, any()) } returns shiftReport()
        coEvery { shiftReportRepository.getDetails(10) } returns shiftDetails()
        coEvery { guestSegmentsRepository.computeSegments(1, 30, now) } returns SegmentComputationResult(
            counts = mapOf(SegmentType.NEW to 5, SegmentType.FREQUENT to 2, SegmentType.SLEEPING to 1),
        )
        coEvery {
            snapshotRepository.upsert(
                clubId = 1,
                nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
                windowDays = 30,
                schemaVersion = 1,
                status = PostEventStoryStatus.READY,
                payloadJson = any(),
                generatedAt = now,
                now = now,
                errorCode = null,
            )
        } answers {
            AnalyticsSnapshot(
                id = 11,
                clubId = 1,
                nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
                windowDays = 30,
                schemaVersion = 1,
                status = PostEventStoryStatus.READY,
                payloadJson = arg(5),
                errorCode = null,
                generatedAt = now,
                updatedAt = now,
            )
        }
        coEvery { storyRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) } returns story()

        val service =
            AdminAnalyticsSnapshotService(
                ownerHealthService = ownerHealthService,
                visitRepository = visitRepository,
                tableDepositRepository = tableDepositRepository,
                shiftReportRepository = shiftReportRepository,
                storyRepository = storyRepository,
                guestSegmentsRepository = guestSegmentsRepository,
                snapshotRepository = snapshotRepository,
                clock = clock,
                runtimeConfig = AnalyticsSnapshotRuntimeConfig(cacheTtl = Duration.ZERO),
            )

        val snapshot = runBlocking { service.recompute(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }

        assertEquals(11, snapshot.id)
        val payload = Json.parseToJsonElement(snapshot.payloadJson).jsonObject
        assertEquals("2024-06-01T20:00:00Z", payload["nightStartUtc"]!!.jsonPrimitive.content)
        assertEquals(10, payload["visits"]!!.jsonObject["uniqueVisitors"]!!.jsonPrimitive.content.toLong())
        assertEquals(15000, payload["deposits"]!!.jsonObject["totalMinor"]!!.jsonPrimitive.content.toLong())
        assertEquals(5, payload["segments"]!!.jsonObject["counts"]!!.jsonObject["new"]!!.jsonPrimitive.content.toInt())

        coVerify(exactly = 1) { snapshotRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { storyRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fetchLatest marks stale snapshot within allowed max age`() {
        val snapshotRepository = mockk<AnalyticsSnapshotRepository>()
        coEvery { snapshotRepository.getByKey(1, any(), 30, 1) } returns
            AnalyticsSnapshot(
                id = 1,
                clubId = 1,
                nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
                windowDays = 30,
                schemaVersion = 1,
                status = PostEventStoryStatus.READY,
                payloadJson =
                    """{"schemaVersion":1,"clubId":1,"nightStartUtc":"2024-06-01T20:00:00Z","generatedAt":"2024-06-01T23:50:00Z","meta":{"hasIncompleteData":false,"caveats":[]},"attendance":null,"visits":{"uniqueVisitors":1,"earlyVisits":0,"tableNights":0},"deposits":{"totalMinor":0,"allocationSummary":{}},"shift":null,"segments":{"counts":{"new":0,"frequent":0,"sleeping":0}}}""",
                errorCode = null,
                generatedAt = Instant.parse("2024-06-01T23:50:00Z"),
                updatedAt = Instant.parse("2024-06-01T23:50:00Z"),
            )

        val service =
            AdminAnalyticsSnapshotService(
                ownerHealthService = mockk(),
                visitRepository = mockk(),
                tableDepositRepository = mockk(),
                shiftReportRepository = mockk(),
                storyRepository = mockk(),
                guestSegmentsRepository = mockk(),
                snapshotRepository = snapshotRepository,
                clock = clock,
                runtimeConfig = AnalyticsSnapshotRuntimeConfig(freshTtl = Duration.ofMinutes(5), staleMaxAge = Duration.ofHours(2), cacheTtl = Duration.ZERO),
            )

        val view = runBlocking { service.fetchLatest(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }
        assertNotNull(view)
        assertEquals(SnapshotState.STALE_ALLOWED, view.state)
    }

    @Test
    fun `recompute rethrows cancellation exception without fallback`() {
        val ownerHealthService = mockk<OwnerHealthService>()
        val snapshotRepository = mockk<AnalyticsSnapshotRepository>()
        val storyRepository = mockk<PostEventStoryRepository>()
        coEvery { ownerHealthService.attendanceForNight(1, any()) } throws CancellationException("cancelled")

        val service =
            AdminAnalyticsSnapshotService(
                ownerHealthService = ownerHealthService,
                visitRepository = mockk(),
                tableDepositRepository = mockk(),
                shiftReportRepository = mockk(),
                storyRepository = storyRepository,
                guestSegmentsRepository = mockk(),
                snapshotRepository = snapshotRepository,
                clock = clock,
                runtimeConfig = AnalyticsSnapshotRuntimeConfig(cacheTtl = Duration.ZERO),
            )

        assertFailsWith<CancellationException> {
            runBlocking { service.recompute(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }
        }
        coVerify(exactly = 0) { snapshotRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun attendanceHealth(): AttendanceHealth {
        val bookings = AttendanceChannel(plannedGuests = 10, arrivedGuests = 5, noShowGuests = 5, noShowRate = 0.5)
        val guestLists = AttendanceChannel(plannedGuests = 4, arrivedGuests = 3, noShowGuests = 1, noShowRate = 0.25)
        return AttendanceHealth(
            bookings = bookings,
            guestLists = guestLists,
            channels =
                AttendanceChannels(
                    directBookings = bookings,
                    promoterBookings = AttendanceChannel(plannedGuests = 2, arrivedGuests = 1, noShowGuests = 1, noShowRate = 0.5),
                    guestLists = guestLists,
                ),
        )
    }

    private fun shiftReport(): ShiftReport =
        ShiftReport(
            id = 10,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            status = ShiftReportStatus.CLOSED,
            peopleWomen = 10,
            peopleMen = 12,
            peopleRejected = 1,
            comment = null,
            closedAt = null,
            closedBy = null,
            createdAt = Instant.parse("2024-06-01T20:00:00Z"),
            updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
        )

    private fun shiftDetails(): ShiftReportDetails =
        ShiftReportDetails(
            report = shiftReport(),
            bracelets = emptyList(),
            revenueEntries =
                listOf(
                    ShiftReportRevenueEntry(
                        id = 1,
                        reportId = 10,
                        articleId = null,
                        name = "Total",
                        groupId = 1,
                        amountMinor = 5000,
                        includeInTotal = true,
                        showSeparately = false,
                        orderIndex = 0,
                    ),
                ),
        )

    private fun story(): PostEventStory =
        PostEventStory(
            id = 1,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            schemaVersion = 1,
            status = PostEventStoryStatus.READY,
            payloadJson = """{"schemaVersion":1}""",
            errorCode = null,
            generatedAt = now,
            updatedAt = now,
        )
}
