package com.example.bot.data.clubs

import com.example.bot.clubs.Club
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withRetriedTx
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class ClubsDbRepository(
    private val database: Database,
) : ClubsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getById(id: Long): Club? =
        withRetriedTx(name = "clubs.get", readOnly = true, database = database) {
            Clubs
                .selectAll()
                .where { (Clubs.id eq id.toInt()) and (Clubs.isActive eq true) }
                .firstOrNull()
                ?.toClub()
        }

    override suspend fun list(
        city: String?,
        query: String?,
        tag: String?,
        genre: String?,
        offset: Int,
        limit: Int,
    ): List<Club> =
        withRetriedTx(name = "clubs.list", readOnly = true, database = database) {
            val rows =
                Clubs
                    .selectAll()
                    .where { Clubs.isActive eq true }
                    .map { it.toClub() }
            rows
                .asSequence()
                .filter { city == null || it.city.equals(city, ignoreCase = true) }
                .filter { tag == null || it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
                .filter { genre == null || it.genres.any { g -> g.equals(genre, ignoreCase = true) } }
                .filter {
                    query == null ||
                        it.name.contains(query, ignoreCase = true) ||
                        it.genres.any { g -> g.contains(query, ignoreCase = true) }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Club::name).thenBy(Club::id))
                .drop(offset)
                .take(limit)
                .toList()
        }

    override suspend fun lastUpdatedAt(): Instant? =
        withRetriedTx(name = "clubs.lastUpdatedAt", readOnly = true, database = database) {
            Clubs
                .selectAll()
                .where { Clubs.isActive eq true }
                .maxOfOrNull { it[Clubs.updatedAt].toInstant() }
        }

    private fun ResultRow.toClub(): Club =
        Club(
            id = this[Clubs.id].value.toLong(),
            city = this[Clubs.city],
            name = this[Clubs.name],
            genres = decodeTags(this[Clubs.genres]),
            tags = decodeTags(this[Clubs.tags]),
            logoUrl = this[Clubs.logoUrl],
        )

    private fun decodeTags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrElse { emptyList() }
    }
}

class EventsDbRepository(
    private val database: Database,
) : EventsRepository {
    override suspend fun list(
        clubId: Long?,
        city: String?,
        from: Instant?,
        to: Instant?,
        offset: Int,
        limit: Int,
    ): List<Event> =
        withRetriedTx(name = "events.list", readOnly = true, database = database) {
            val clubIds =
                city?.let { cityFilter ->
                    Clubs
                        .selectAll()
                        .where { Clubs.isActive eq true }
                        .map { row -> row[Clubs.id].value.toLong() to row[Clubs.city] }
                        .filter { (_, clubCity) -> clubCity.equals(cityFilter, ignoreCase = true) }
                        .map { it.first }
                }

            if (clubIds != null && clubIds.isEmpty()) return@withRetriedTx emptyList()

            EventsTable
                .selectAll()
                .let { query ->
                    if (clubId != null) {
                        query.where { EventsTable.clubId eq clubId }
                    } else if (clubIds != null) {
                        query.where { EventsTable.clubId inList clubIds }
                    } else {
                        query
                    }
                }
                .filter { row -> from == null || !row[EventsTable.startAt].toInstant().isBefore(from) }
                .filter { row -> to == null || !row[EventsTable.startAt].toInstant().isAfter(to) }
                .sortedWith(compareBy<ResultRow> { it[EventsTable.startAt] }.thenBy { it[EventsTable.id] })
                .drop(offset)
                .take(limit)
                .map { row ->
                    Event(
                        id = row[EventsTable.id],
                        clubId = row[EventsTable.clubId],
                        startUtc = row[EventsTable.startAt].toInstant(),
                        endUtc = row[EventsTable.endAt].toInstant(),
                        title = row[EventsTable.title],
                        isSpecial = row[EventsTable.isSpecial],
                    )
                }
        }

    override suspend fun lastUpdatedAt(): Instant? =
        withRetriedTx(name = "events.lastUpdatedAt", readOnly = true, database = database) {
            EventsTable
                .selectAll()
                .maxOfOrNull { it[EventsTable.startAt].toInstant() }
        }

    override fun findById(clubId: Long, eventId: Long): Event? =
        transaction(database) {
            EventsTable
                .selectAll()
                .where { (EventsTable.clubId eq clubId) and (EventsTable.id eq eventId) }
                .firstOrNull()
                ?.let { row ->
                    Event(
                        id = row[EventsTable.id],
                        clubId = row[EventsTable.clubId],
                        startUtc = row[EventsTable.startAt].toInstant(),
                        endUtc = row[EventsTable.endAt].toInstant(),
                        title = row[EventsTable.title],
                        isSpecial = row[EventsTable.isSpecial],
                    )
                }
        }
}
