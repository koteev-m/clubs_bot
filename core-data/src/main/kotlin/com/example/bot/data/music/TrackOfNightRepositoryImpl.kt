package com.example.bot.data.music

import com.example.bot.music.TrackOfNight
import com.example.bot.music.TrackOfNightRepository
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class TrackOfNightRepositoryImpl(
    private val db: Database,
) : TrackOfNightRepository {
    override suspend fun setTrackOfNight(
        setId: Long,
        trackId: Long,
        actorId: Long,
        markedAt: Instant,
    ): TrackOfNight =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val inserted =
                MusicTrackOfNightTable.insertIgnore {
                    it[this.setId] = setId
                    it[this.trackId] = trackId
                    it[this.markedBy] = actorId
                    it[this.markedAt] = markedAt.atOffset(ZoneOffset.UTC)
                }

            if (inserted.insertedCount == 0) {
                val existing =
                    MusicTrackOfNightTable
                        .select { MusicTrackOfNightTable.setId eq setId }
                        .firstOrNull()

                if (existing != null && existing[MusicTrackOfNightTable.trackId] != trackId) {
                    MusicTrackOfNightTable.update({ MusicTrackOfNightTable.setId eq setId }) {
                        it[this.trackId] = trackId
                        it[this.markedBy] = actorId
                        it[this.markedAt] = markedAt.atOffset(ZoneOffset.UTC)
                    }
                }
            }

            MusicTrackOfNightTable
                .select { MusicTrackOfNightTable.setId eq setId }
                .first()
                .toTrackOfNight()
        }

    override suspend fun currentForSet(setId: Long): TrackOfNight? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicTrackOfNightTable
                .select { MusicTrackOfNightTable.setId eq setId }
                .limit(1)
                .firstOrNull()
                ?.toTrackOfNight()
        }

    override suspend fun currentTracksForSets(setIds: Collection<Long>): Map<Long, Long> {
        if (setIds.isEmpty()) return emptyMap()
        return newSuspendedTransaction(Dispatchers.IO, db) {
            MusicTrackOfNightTable
                .select { MusicTrackOfNightTable.setId inList setIds }
                .associate { row -> row[MusicTrackOfNightTable.setId] to row[MusicTrackOfNightTable.trackId] }
        }
    }

    override suspend fun lastUpdatedAt(): Instant? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicTrackOfNightTable
                .slice(MusicTrackOfNightTable.markedAt.max())
                .selectAll()
                .firstOrNull()
                ?.get(MusicTrackOfNightTable.markedAt.max())
                ?.toInstant()
        }

    override suspend fun currentGlobal(): TrackOfNight? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicTrackOfNightTable
                .selectAll()
                .orderBy(MusicTrackOfNightTable.markedAt, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toTrackOfNight()
        }

    private fun org.jetbrains.exposed.sql.ResultRow.toTrackOfNight(): TrackOfNight =
        TrackOfNight(
            setId = this[MusicTrackOfNightTable.setId],
            trackId = this[MusicTrackOfNightTable.trackId],
            markedBy = this[MusicTrackOfNightTable.markedBy] ?: 0,
            markedAt = this[MusicTrackOfNightTable.markedAt].toInstant(),
        )
}
