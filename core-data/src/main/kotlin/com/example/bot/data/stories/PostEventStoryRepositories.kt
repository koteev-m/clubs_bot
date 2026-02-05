package com.example.bot.data.stories

import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant

enum class PostEventStoryStatus {
    READY,
    FAILED,
}

data class PostEventStory(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val schemaVersion: Int,
    val status: PostEventStoryStatus,
    val payloadJson: String,
    val errorCode: String?,
    val generatedAt: Instant,
    val updatedAt: Instant,
)

enum class SegmentType {
    NEW,
    FREQUENT,
    SLEEPING,
}

data class SegmentComputationResult(
    val counts: Map<SegmentType, Int>,
)

object PostEventStoriesTable : org.jetbrains.exposed.sql.Table("post_event_stories") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val schemaVersion = integer("schema_version")
    val status = text("status")
    val payloadJson = text("payload_json")
    val errorCode = text("error_code").nullable()
    val generatedAt = timestampWithTimeZone("generated_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object GuestSegmentsTable : org.jetbrains.exposed.sql.Table("guest_segments") {
    val clubId = long("club_id")
    val userId = long("user_id")
    val windowDays = integer("window_days")
    val segmentType = text("segment_type")
    val computedAt = timestampWithTimeZone("computed_at")

    override val primaryKey = PrimaryKey(clubId, userId, windowDays)
}

class PostEventStoryRepository(
    private val db: Database,
) {
    suspend fun getByClubAndNight(
        clubId: Long,
        nightStartUtc: Instant,
        schemaVersion: Int = 1,
    ): PostEventStory? {
        require(schemaVersion > 0) { "schemaVersion must be > 0" }
        return withRetriedTx(name = "story.get", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PostEventStoriesTable
                    .selectAll()
                    .where {
                        (PostEventStoriesTable.clubId eq clubId) and
                            (PostEventStoriesTable.nightStartUtc eq nightStartUtc.toOffsetDateTimeUtc()) and
                            (PostEventStoriesTable.schemaVersion eq schemaVersion)
                    }.limit(1)
                    .firstOrNull()
                    ?.toPostEventStory()
            }
        }
    }

    suspend fun listByClub(
        clubId: Long,
        limit: Int,
        offset: Long,
    ): List<PostEventStory> {
        require(limit > 0) { "limit must be > 0" }
        require(offset >= 0) { "offset must be >= 0" }
        return withRetriedTx(name = "story.list", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PostEventStoriesTable
                    .selectAll()
                    .where { PostEventStoriesTable.clubId eq clubId }
                    .orderBy(PostEventStoriesTable.nightStartUtc, SortOrder.DESC)
                    .orderBy(PostEventStoriesTable.id, SortOrder.DESC)
                    .limit(limit, offset)
                    .map { it.toPostEventStory() }
            }
        }
    }

    suspend fun upsert(
        clubId: Long,
        nightStartUtc: Instant,
        schemaVersion: Int,
        status: PostEventStoryStatus,
        payloadJson: String,
        generatedAt: Instant,
        now: Instant,
        errorCode: String? = null,
    ): PostEventStory {
        require(schemaVersion > 0) { "schemaVersion must be > 0" }
        require(payloadJson.isNotBlank()) { "payloadJson must not be blank" }
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val generatedAtUtc = generatedAt.toOffsetDateTimeUtc()
        val updatedAtUtc = now.toOffsetDateTimeUtc()

        return withRetriedTx(name = "story.upsert", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated =
                    PostEventStoriesTable.update({
                        (PostEventStoriesTable.clubId eq clubId) and
                            (PostEventStoriesTable.nightStartUtc eq nightStart) and
                            (PostEventStoriesTable.schemaVersion eq schemaVersion)
                    }) {
                        it[PostEventStoriesTable.status] = status.name
                        it[PostEventStoriesTable.payloadJson] = payloadJson
                        it[PostEventStoriesTable.errorCode] = errorCode
                        it[PostEventStoriesTable.generatedAt] = generatedAtUtc
                        it[PostEventStoriesTable.updatedAt] = updatedAtUtc
                    }

                if (updated == 0) {
                    val inserted =
                        PostEventStoriesTable.insertIgnore {
                            it[PostEventStoriesTable.clubId] = clubId
                            it[PostEventStoriesTable.nightStartUtc] = nightStart
                            it[PostEventStoriesTable.schemaVersion] = schemaVersion
                            it[PostEventStoriesTable.status] = status.name
                            it[PostEventStoriesTable.payloadJson] = payloadJson
                            it[PostEventStoriesTable.errorCode] = errorCode
                            it[PostEventStoriesTable.generatedAt] = generatedAtUtc
                            it[PostEventStoriesTable.updatedAt] = updatedAtUtc
                        }
                    if (inserted.insertedCount == 0) {
                        PostEventStoriesTable.update({
                            (PostEventStoriesTable.clubId eq clubId) and
                                (PostEventStoriesTable.nightStartUtc eq nightStart) and
                                (PostEventStoriesTable.schemaVersion eq schemaVersion)
                        }) {
                            it[PostEventStoriesTable.status] = status.name
                            it[PostEventStoriesTable.payloadJson] = payloadJson
                            it[PostEventStoriesTable.errorCode] = errorCode
                            it[PostEventStoriesTable.generatedAt] = generatedAtUtc
                            it[PostEventStoriesTable.updatedAt] = updatedAtUtc
                        }
                    }
                }

                PostEventStoriesTable
                    .selectAll()
                    .where {
                        (PostEventStoriesTable.clubId eq clubId) and
                            (PostEventStoriesTable.nightStartUtc eq nightStart) and
                            (PostEventStoriesTable.schemaVersion eq schemaVersion)
                    }.limit(1)
                    .firstOrNull()
                    ?.toPostEventStory()
                    ?: error("Failed to load post-event story after upsert")
            }
        }
    }
}

class GuestSegmentsRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Rules:
     * - windowStart = nowUtc - windowDays
     * - last_visit_at < windowStart => SLEEPING
     * - else if first_visit_at >= windowStart => NEW
     * - else => FREQUENT
     */
    suspend fun computeSegments(
        clubId: Long,
        windowDays: Int,
        now: Instant = Instant.now(clock),
    ): SegmentComputationResult {
        require(windowDays > 0) { "windowDays must be > 0" }
        val windowStart = now.minusSeconds(windowDays.toLong() * 24L * 60L * 60L).toOffsetDateTimeUtc()
        val computedAt = now.toOffsetDateTimeUtc()

        return withRetriedTx(name = "segments.compute", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestSegmentsTable.deleteWhere {
                    (GuestSegmentsTable.clubId eq clubId) and (GuestSegmentsTable.windowDays eq windowDays)
                }

                val sql =
                    """
                    INSERT INTO guest_segments (club_id, user_id, window_days, segment_type, computed_at)
                    SELECT
                        cv.club_id,
                        cv.user_id,
                        ?,
                        CASE
                            WHEN MAX(cv.first_checkin_at) < ? THEN 'SLEEPING'
                            WHEN MIN(cv.first_checkin_at) >= ? THEN 'NEW'
                            ELSE 'FREQUENT'
                        END,
                        ?
                    FROM club_visits cv
                    WHERE cv.club_id = ?
                    GROUP BY cv.club_id, cv.user_id
                    """.trimIndent()

                val statement = TransactionManager.current().connection.prepareStatement(sql, false)
                statement[1] = windowDays
                statement[2] = windowStart
                statement[3] = windowStart
                statement[4] = computedAt
                statement[5] = clubId
                statement.executeUpdate()

                SegmentComputationResult(counts = buildSummaryInternal(clubId, windowDays))
            }
        }
    }

    suspend fun getSummary(
        clubId: Long,
        windowDays: Int,
    ): Map<SegmentType, Int> {
        require(windowDays > 0) { "windowDays must be > 0" }
        return withRetriedTx(name = "segments.summary", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                buildSummaryInternal(clubId, windowDays)
            }
        }
    }

    private fun buildSummaryInternal(
        clubId: Long,
        windowDays: Int,
    ): Map<SegmentType, Int> {
        val rowCount = GuestSegmentsTable.userId.count()
        val result = mutableMapOf<SegmentType, Int>()
        GuestSegmentsTable
            .slice(GuestSegmentsTable.segmentType, rowCount)
            .selectAll()
            .where {
                (GuestSegmentsTable.clubId eq clubId) and
                    (GuestSegmentsTable.windowDays eq windowDays)
            }.groupBy(GuestSegmentsTable.segmentType)
            .forEach { row ->
                val type = SegmentType.valueOf(row[GuestSegmentsTable.segmentType])
                result[type] = row[rowCount].toInt()
            }
        return SegmentType.entries.associateWith { result[it] ?: 0 }
    }
}

private fun ResultRow.toPostEventStory(): PostEventStory =
    PostEventStory(
        id = this[PostEventStoriesTable.id],
        clubId = this[PostEventStoriesTable.clubId],
        nightStartUtc = this[PostEventStoriesTable.nightStartUtc].toInstant(),
        schemaVersion = this[PostEventStoriesTable.schemaVersion],
        status = PostEventStoryStatus.valueOf(this[PostEventStoriesTable.status]),
        payloadJson = this[PostEventStoriesTable.payloadJson],
        errorCode = this[PostEventStoriesTable.errorCode],
        generatedAt = this[PostEventStoriesTable.generatedAt].toInstant(),
        updatedAt = this[PostEventStoriesTable.updatedAt].toInstant(),
    )
