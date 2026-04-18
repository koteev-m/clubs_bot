package com.example.bot.data.stories

import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.sql.Clob
import java.time.Instant

data class AnalyticsSnapshot(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val windowDays: Int,
    val schemaVersion: Int,
    val status: PostEventStoryStatus,
    val payloadJson: String,
    val errorCode: String?,
    val generatedAt: Instant,
    val updatedAt: Instant,
)

object AnalyticsSnapshotsTable : Table("analytics_snapshots") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val windowDays = integer("window_days")
    val schemaVersion = integer("schema_version")
    val status = text("status")
    val payloadJson = jsonbString("payload_json")
    val errorCode = text("error_code").nullable()
    val generatedAt = timestampWithTimeZone("generated_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

class AnalyticsSnapshotRepository(
    private val db: Database,
) {
    suspend fun getByKey(
        clubId: Long,
        nightStartUtc: Instant,
        windowDays: Int,
        schemaVersion: Int,
    ): AnalyticsSnapshot? {
        require(windowDays > 0) { "windowDays must be > 0" }
        require(schemaVersion > 0) { "schemaVersion must be > 0" }
        return withRetriedTx(name = "analytics_snapshot.get", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                AnalyticsSnapshotsTable
                    .selectAll()
                    .where {
                        (AnalyticsSnapshotsTable.clubId eq clubId) and
                            (AnalyticsSnapshotsTable.nightStartUtc eq nightStartUtc.toOffsetDateTimeUtc()) and
                            (AnalyticsSnapshotsTable.windowDays eq windowDays) and
                            (AnalyticsSnapshotsTable.schemaVersion eq schemaVersion)
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.toAnalyticsSnapshot()
            }
        }
    }

    suspend fun upsert(
        clubId: Long,
        nightStartUtc: Instant,
        windowDays: Int,
        schemaVersion: Int,
        status: PostEventStoryStatus,
        payloadJson: String,
        generatedAt: Instant,
        now: Instant,
        errorCode: String? = null,
    ): AnalyticsSnapshot {
        require(windowDays > 0) { "windowDays must be > 0" }
        require(schemaVersion > 0) { "schemaVersion must be > 0" }
        require(payloadJson.isNotBlank()) { "payloadJson must not be blank" }
        runCatching { Json.decodeFromString<JsonElement>(payloadJson) }
            .getOrElse { throw IllegalArgumentException("payloadJson must be valid JSON", it) }

        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val generatedAtUtc = generatedAt.toOffsetDateTimeUtc()
        val updatedAtUtc = now.toOffsetDateTimeUtc()

        return withRetriedTx(name = "analytics_snapshot.upsert", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated =
                    AnalyticsSnapshotsTable.update({
                        (AnalyticsSnapshotsTable.clubId eq clubId) and
                            (AnalyticsSnapshotsTable.nightStartUtc eq nightStart) and
                            (AnalyticsSnapshotsTable.windowDays eq windowDays) and
                            (AnalyticsSnapshotsTable.schemaVersion eq schemaVersion)
                    }) {
                        it[AnalyticsSnapshotsTable.status] = status.name
                        it[AnalyticsSnapshotsTable.payloadJson] = payloadJson
                        it[AnalyticsSnapshotsTable.errorCode] = errorCode
                        it[AnalyticsSnapshotsTable.generatedAt] = generatedAtUtc
                        it[AnalyticsSnapshotsTable.updatedAt] = updatedAtUtc
                    }

                if (updated == 0) {
                    val inserted =
                        AnalyticsSnapshotsTable.insertIgnore {
                            it[AnalyticsSnapshotsTable.clubId] = clubId
                            it[AnalyticsSnapshotsTable.nightStartUtc] = nightStart
                            it[AnalyticsSnapshotsTable.windowDays] = windowDays
                            it[AnalyticsSnapshotsTable.schemaVersion] = schemaVersion
                            it[AnalyticsSnapshotsTable.status] = status.name
                            it[AnalyticsSnapshotsTable.payloadJson] = payloadJson
                            it[AnalyticsSnapshotsTable.errorCode] = errorCode
                            it[AnalyticsSnapshotsTable.generatedAt] = generatedAtUtc
                            it[AnalyticsSnapshotsTable.updatedAt] = updatedAtUtc
                        }
                    if (inserted.insertedCount == 0) {
                        AnalyticsSnapshotsTable.update({
                            (AnalyticsSnapshotsTable.clubId eq clubId) and
                                (AnalyticsSnapshotsTable.nightStartUtc eq nightStart) and
                                (AnalyticsSnapshotsTable.windowDays eq windowDays) and
                                (AnalyticsSnapshotsTable.schemaVersion eq schemaVersion)
                        }) {
                            it[AnalyticsSnapshotsTable.status] = status.name
                            it[AnalyticsSnapshotsTable.payloadJson] = payloadJson
                            it[AnalyticsSnapshotsTable.errorCode] = errorCode
                            it[AnalyticsSnapshotsTable.generatedAt] = generatedAtUtc
                            it[AnalyticsSnapshotsTable.updatedAt] = updatedAtUtc
                        }
                    }
                }

                AnalyticsSnapshotsTable
                    .selectAll()
                    .where {
                        (AnalyticsSnapshotsTable.clubId eq clubId) and
                            (AnalyticsSnapshotsTable.nightStartUtc eq nightStart) and
                            (AnalyticsSnapshotsTable.windowDays eq windowDays) and
                            (AnalyticsSnapshotsTable.schemaVersion eq schemaVersion)
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.toAnalyticsSnapshot()
                    ?: error("Failed to load analytics snapshot after upsert")
            }
        }
    }
}

private fun ResultRow.toAnalyticsSnapshot(): AnalyticsSnapshot =
    AnalyticsSnapshot(
        id = this[AnalyticsSnapshotsTable.id],
        clubId = this[AnalyticsSnapshotsTable.clubId],
        nightStartUtc = this[AnalyticsSnapshotsTable.nightStartUtc].toInstant(),
        windowDays = this[AnalyticsSnapshotsTable.windowDays],
        schemaVersion = this[AnalyticsSnapshotsTable.schemaVersion],
        status = PostEventStoryStatus.valueOf(this[AnalyticsSnapshotsTable.status]),
        payloadJson = this[AnalyticsSnapshotsTable.payloadJson],
        errorCode = this[AnalyticsSnapshotsTable.errorCode],
        generatedAt = this[AnalyticsSnapshotsTable.generatedAt].toInstant(),
        updatedAt = this[AnalyticsSnapshotsTable.updatedAt].toInstant(),
    )

private fun Table.jsonbString(name: String): Column<String> = registerColumn(name, AnalyticsJsonbStringColumnType())

private class AnalyticsJsonbStringColumnType : ColumnType() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): Any =
        when (value) {
            is String -> value
            is Clob -> value.characterStream.readText()
            else -> value.toString()
        }

    override fun notNullValueToDB(value: Any): Any =
        when (value) {
            is String -> value
            else -> value.toString()
        }
}
