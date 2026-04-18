package com.example.bot.analytics

import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.totalRevenue
import com.example.bot.data.stories.AnalyticsSnapshot
import com.example.bot.data.stories.AnalyticsSnapshotRepository
import com.example.bot.data.stories.GuestSegmentsRepository
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.data.stories.PostEventStoryStatus
import com.example.bot.data.stories.SegmentType
import com.example.bot.data.visits.VisitRepository
import com.example.bot.owner.AttendanceHealth
import com.example.bot.owner.OwnerHealthService
import com.example.bot.routes.AnalyticsMeta
import com.example.bot.routes.AnalyticsResponse
import com.example.bot.routes.DepositSummary
import com.example.bot.routes.SegmentSummary
import com.example.bot.routes.ShiftSummary
import com.example.bot.routes.VisitSummary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val STORY_SCHEMA_VERSION = 1

data class AnalyticsSnapshotRuntimeConfig(
    val freshTtl: Duration = Duration.ofMinutes(5),
    val staleMaxAge: Duration = Duration.ofHours(2),
    val cacheTtl: Duration = Duration.ofSeconds(30),
) {
    companion object {
        fun fromEnv(): AnalyticsSnapshotRuntimeConfig =
            AnalyticsSnapshotRuntimeConfig(
                freshTtl = envDuration("ANALYTICS_SNAPSHOT_FRESH_TTL_SECONDS", Duration.ofMinutes(5)),
                staleMaxAge = envDuration("ANALYTICS_SNAPSHOT_STALE_MAX_AGE_SECONDS", Duration.ofHours(2)),
                cacheTtl = envDuration("ANALYTICS_SNAPSHOT_CACHE_TTL_SECONDS", Duration.ofSeconds(30)),
            )

        private fun envDuration(key: String, fallback: Duration): Duration {
            val value = System.getenv(key)?.toLongOrNull() ?: return fallback
            if (value <= 0) return fallback
            return Duration.ofSeconds(value)
        }
    }
}

enum class SnapshotState {
    FRESH,
    STALE_ALLOWED,
    STALE_TOO_OLD,
}

data class AnalyticsSnapshotView(
    val response: AnalyticsResponse,
    val snapshot: AnalyticsSnapshot,
    val state: SnapshotState,
)

class AdminAnalyticsSnapshotService(
    private val ownerHealthService: OwnerHealthService,
    private val visitRepository: VisitRepository,
    private val tableDepositRepository: TableDepositRepository,
    private val shiftReportRepository: ShiftReportRepository,
    private val storyRepository: PostEventStoryRepository,
    private val guestSegmentsRepository: GuestSegmentsRepository,
    private val snapshotRepository: AnalyticsSnapshotRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val runtimeConfig: AnalyticsSnapshotRuntimeConfig = AnalyticsSnapshotRuntimeConfig.fromEnv(),
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<SnapshotKey, CacheValue>()

    suspend fun fetchLatest(clubId: Long, nightStartUtc: Instant, windowDays: Int): AnalyticsSnapshotView? {
        val key = SnapshotKey(clubId, nightStartUtc, windowDays)
        val now = Instant.now(clock)
        val cached = cache[key]
        if (cached != null && now.isBefore(cached.cachedUntil)) {
            return cached.view
        }

        val snapshot = snapshotRepository.getByKey(clubId, nightStartUtc, windowDays, STORY_SCHEMA_VERSION) ?: return null
        val response = json.decodeFromString<AnalyticsResponse>(snapshot.payloadJson)
        val age = Duration.between(snapshot.generatedAt, now)
        val state =
            when {
                age <= runtimeConfig.freshTtl -> SnapshotState.FRESH
                age <= runtimeConfig.staleMaxAge -> SnapshotState.STALE_ALLOWED
                else -> SnapshotState.STALE_TOO_OLD
            }
        val view = AnalyticsSnapshotView(response = response, snapshot = snapshot, state = state)
        if (!runtimeConfig.cacheTtl.isZero && !runtimeConfig.cacheTtl.isNegative) {
            cache[key] = CacheValue(view = view, cachedUntil = now.plus(runtimeConfig.cacheTtl))
        }
        return view
    }

    suspend fun recompute(clubId: Long, nightStartUtc: Instant, windowDays: Int): AnalyticsSnapshot {
        val generatedAt = Instant.now(clock)
        val caveats = mutableListOf<String>()
        var hasIncomplete = false

        fun recordCaveat(code: String) {
            if (code !in caveats) {
                caveats += code
            }
            hasIncomplete = true
        }

        val attendance =
            runCatching { ownerHealthService.attendanceForNight(clubId, nightStartUtc) }
                .getOrElseNonCancellation {
                    recordCaveat("attendance_unavailable")
                    null
                }

        if (attendance == null) {
            recordCaveat("attendance_unavailable")
        } else if (attendance.channels.promoterBookings.plannedGuests > 0 && attendance.channels.promoterBookings.arrivedGuests == 0) {
            recordCaveat("promoter_arrived_unavailable")
        }

        val visits =
            runCatching {
                VisitSummary(
                    uniqueVisitors = visitRepository.countNightUniqueVisitors(clubId, nightStartUtc),
                    earlyVisits = visitRepository.countNightEarlyVisits(clubId, nightStartUtc),
                    tableNights = visitRepository.countNightTableNights(clubId, nightStartUtc),
                )
            }.getOrElseNonCancellation {
                recordCaveat("visits_unavailable")
                VisitSummary(uniqueVisitors = 0, earlyVisits = 0, tableNights = 0)
            }

        val deposits =
            runCatching {
                DepositSummary(
                    totalMinor = tableDepositRepository.sumDepositsForNight(clubId, nightStartUtc),
                    allocationSummary = tableDepositRepository.allocationSummaryForNight(clubId, nightStartUtc),
                )
            }.getOrElseNonCancellation {
                recordCaveat("deposits_unavailable")
                DepositSummary(totalMinor = 0, allocationSummary = emptyMap())
            }

        val shift =
            runCatching {
                val report = shiftReportRepository.getByClubAndNight(clubId, nightStartUtc)
                    ?: return@runCatching null.also { recordCaveat("shift_report_missing") }
                val details = shiftReportRepository.getDetails(report.id)
                    ?: return@runCatching null.also { recordCaveat("shift_report_unavailable") }
                ShiftSummary(
                    status = report.status.name,
                    peopleWomen = report.peopleWomen,
                    peopleMen = report.peopleMen,
                    peopleRejected = report.peopleRejected,
                    revenueTotalMinor = totalRevenue(details.revenueEntries),
                )
            }.getOrElseNonCancellation {
                recordCaveat("shift_report_unavailable")
                null
            }

        val segments =
            runCatching {
                val result = guestSegmentsRepository.computeSegments(clubId, windowDays, generatedAt)
                SegmentSummary(counts = result.counts.mapKeys { it.key.name.lowercase() })
            }.getOrElseNonCancellation {
                recordCaveat("segments_unavailable")
                SegmentSummary(
                    counts = SegmentType.entries.associate { it.name.lowercase() to 0 },
                )
            }

        val response =
            AnalyticsResponse(
                schemaVersion = STORY_SCHEMA_VERSION,
                clubId = clubId,
                nightStartUtc = nightStartUtc.toString(),
                generatedAt = generatedAt.toString(),
                meta = AnalyticsMeta(hasIncompleteData = hasIncomplete, caveats = caveats),
                attendance = attendance,
                visits = visits,
                deposits = deposits,
                shift = shift,
                segments = segments,
            )

        val payloadJson = json.encodeToString(response)
        val snapshot =
            snapshotRepository.upsert(
                clubId = clubId,
                nightStartUtc = nightStartUtc,
                windowDays = windowDays,
                schemaVersion = STORY_SCHEMA_VERSION,
                status = PostEventStoryStatus.READY,
                payloadJson = payloadJson,
                generatedAt = generatedAt,
                now = generatedAt,
            )
        cache[SnapshotKey(clubId, nightStartUtc, windowDays)] =
            CacheValue(
                view = AnalyticsSnapshotView(response = response, snapshot = snapshot, state = SnapshotState.FRESH),
                cachedUntil = generatedAt.plus(runtimeConfig.cacheTtl),
            )

        runCatching {
            storyRepository.upsert(
                clubId = clubId,
                nightStartUtc = nightStartUtc,
                schemaVersion = STORY_SCHEMA_VERSION,
                status = PostEventStoryStatus.READY,
                payloadJson = payloadJson,
                generatedAt = generatedAt,
                now = generatedAt,
            )
        }.onFailure {
            if (it is CancellationException) {
                throw it
            }
            logger.warn("Post-event story upsert failed for clubId={} nightStartUtc={}", clubId, nightStartUtc, it)
        }

        return snapshot
    }

    private data class SnapshotKey(
        val clubId: Long,
        val nightStartUtc: Instant,
        val windowDays: Int,
    )

    private data class CacheValue(
        val view: AnalyticsSnapshotView,
        val cachedUntil: Instant,
    )

    private inline fun <T> Result<T>.getOrElseNonCancellation(onFailure: (Throwable) -> T): T =
        getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            onFailure(throwable)
        }
}

class AdminAnalyticsRefreshWorker(
    private val service: AdminAnalyticsSnapshotService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val inFlight = ConcurrentHashMap<WorkerKey, Boolean>()
    private val isShutdown = AtomicBoolean(false)

    fun schedule(clubId: Long, nightStartUtc: Instant, windowDays: Int) {
        if (isShutdown.get()) {
            logger.debug(
                "Ignoring analytics recompute schedule after shutdown for clubId={} nightStartUtc={} windowDays={}",
                clubId,
                nightStartUtc,
                windowDays,
            )
            return
        }
        val key = WorkerKey(clubId, nightStartUtc, windowDays)
        if (inFlight.putIfAbsent(key, true) != null) {
            return
        }

        scope.launch {
            try {
                service.recompute(clubId, nightStartUtc, windowDays)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.warn("Analytics snapshot recompute failed for clubId={} nightStartUtc={} windowDays={}", clubId, nightStartUtc, windowDays, t)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scope.cancel(CancellationException("AdminAnalyticsRefreshWorker shutdown"))
            inFlight.clear()
        }
    }

    fun close() = shutdown()

    private data class WorkerKey(
        val clubId: Long,
        val nightStartUtc: Instant,
        val windowDays: Int,
    )
}
