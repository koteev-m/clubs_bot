package com.example.bot.clubs

import java.time.Instant

data class Club(
    val id: Long,
    val city: String,
    val name: String,
    val genres: List<String>,
    val tags: List<String>,
    val logoUrl: String?,
)

data class Event(
    val id: Long,
    val clubId: Long,
    val startUtc: Instant,
    val endUtc: Instant,
    val title: String?,
    val isSpecial: Boolean,
)

interface ClubsRepository {
    suspend fun list(
        city: String?,
        query: String?,
        tag: String?,
        genre: String?,
        offset: Int,
        limit: Int,
    ): List<Club>

    suspend fun lastUpdatedAt(): Instant?
}

interface EventsRepository {
    suspend fun list(
        clubId: Long?,
        city: String?,
        from: Instant?,
        to: Instant?,
        offset: Int,
        limit: Int,
    ): List<Event>

    suspend fun lastUpdatedAt(): Instant?
}

class InMemoryClubsRepository(
    private val clubs: List<Club>,
    private val updatedAt: Instant? = null,
) : ClubsRepository {
    override suspend fun list(
        city: String?,
        query: String?,
        tag: String?,
        genre: String?,
        offset: Int,
        limit: Int,
    ): List<Club> {
        return clubs
            .asSequence()
            .filter { city == null || it.city.equals(city, ignoreCase = true) }
            .filter { tag == null || it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
            .filter { genre == null || it.genres.any { g -> g.equals(genre, ignoreCase = true) } }
            .filter {
                query == null ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.genres.any { genre -> genre.contains(query, ignoreCase = true) }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Club::name).thenBy(Club::id))
            .drop(offset)
            .take(limit)
            .toList()
    }

    override suspend fun lastUpdatedAt(): Instant? = updatedAt
}

class InMemoryEventsRepository(
    private val events: List<Event>,
    private val clubs: Map<Long, Club> = emptyMap(),
    private val updatedAt: Instant? = null,
) : EventsRepository {
    override suspend fun list(
        clubId: Long?,
        city: String?,
        from: Instant?,
        to: Instant?,
        offset: Int,
        limit: Int,
    ): List<Event> {
        return events
            .asSequence()
            .filter { clubId == null || it.clubId == clubId }
            .filter { city == null || clubs[it.clubId]?.city?.equals(city, ignoreCase = true) == true }
            .filter { from == null || !it.startUtc.isBefore(from) }
            .filter { to == null || !it.startUtc.isAfter(to) }
            .sortedWith(compareBy<Event> { it.startUtc }.thenBy { it.id })
            .drop(offset)
            .take(limit)
            .toList()
    }

    override suspend fun lastUpdatedAt(): Instant? = updatedAt
}
