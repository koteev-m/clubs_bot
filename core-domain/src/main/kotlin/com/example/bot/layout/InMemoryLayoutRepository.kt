package com.example.bot.layout

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

class InMemoryLayoutRepository(
    layouts: List<LayoutSeed>,
    private val baseUpdatedAt: Instant? = null,
    private val eventUpdatedAt: Map<Long?, Instant> = emptyMap(),
    private val clock: Clock = Clock.systemUTC(),
) : LayoutRepository, AdminTablesRepository {
    private val layoutsByClub: MutableMap<Long, LayoutSeed> = layouts.associateBy { it.clubId }.toMutableMap()
    private var updatedAt: Instant? = baseUpdatedAt ?: clock.instant()

    override suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout? {
        val seed = layoutsByClub[clubId] ?: return null

        val zones = seed.zones.sortedBy { it.order }
        val tables =
            seed.tables
                .map { table ->
                    val statusOverride = seed.statusOverrides[eventId]?.get(table.id)
                    table.copy(status = statusOverride ?: table.status)
                }
                .sortedWith(compareBy<Table> { it.zoneId }.thenBy { it.id })

        return ClubLayout(
            clubId = clubId,
            eventId = eventId,
            zones = zones,
            tables = tables,
            assets =
                LayoutAssets(
                    geometryUrl = "/assets/layouts/$clubId/${seed.fingerprint}.json",
                    fingerprint = seed.fingerprint,
                ),
        )
    }

    override suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant? =
        maxOfNotNull(updatedAt, eventUpdatedAt[eventId])

    override suspend fun listForClub(clubId: Long): List<Table> =
        layoutsByClub[clubId]?.tables?.sortedBy { it.id } ?: emptyList()

    override suspend fun listZonesForClub(clubId: Long): List<Zone> =
        layoutsByClub[clubId]?.zones ?: emptyList()

    override suspend fun create(request: AdminTableCreate): Table {
        val seed = layoutsByClub[request.clubId] ?: LayoutSeed(request.clubId, emptyList(), emptyList(), DEFAULT_GEOMETRY_JSON)
        val nextId = (seed.tables.maxOfOrNull { it.id } ?: 0L) + 1
        val defaultZoneId = request.zone ?: (seed.zones.firstOrNull()?.id ?: "main")
        val newTable =
            Table(
                id = nextId,
                zoneId = defaultZoneId,
                label = request.label,
                capacity = request.capacity,
                minimumTier = "standard",
                status = TableStatus.FREE,
                minDeposit = request.minDeposit,
                zone = request.zone ?: defaultZoneId,
                arrivalWindow = request.arrivalWindow,
                mysteryEligible = request.mysteryEligible,
            )
        val updatedSeed = seed.copy(tables = seed.tables + newTable)
        layoutsByClub[request.clubId] = updatedSeed
        touch()
        return newTable
    }

    override suspend fun update(request: AdminTableUpdate): Table? {
        val seed = layoutsByClub[request.clubId] ?: return null
        val existing = seed.tables.firstOrNull { it.id == request.id } ?: return null
        val updated =
            existing.copy(
                label = request.label ?: existing.label,
                capacity = request.capacity ?: existing.capacity,
                minDeposit = request.minDeposit ?: existing.minDeposit,
                zone = request.zone ?: existing.zone ?: existing.zoneId,
                zoneId = request.zone ?: existing.zoneId,
                arrivalWindow = request.arrivalWindow ?: existing.arrivalWindow,
                mysteryEligible = request.mysteryEligible ?: existing.mysteryEligible,
            )
        val updatedSeed = seed.copy(tables = seed.tables.map { if (it.id == existing.id) updated else it })
        layoutsByClub[request.clubId] = updatedSeed
        touch()
        return updated
    }

    override suspend fun delete(clubId: Long, id: Long): Boolean {
        val seed = layoutsByClub[clubId] ?: return false
        val updatedTables = seed.tables.filterNot { it.id == id }
        if (updatedTables.size == seed.tables.size) return false

        layoutsByClub[clubId] = seed.copy(tables = updatedTables)
        touch()
        return true
    }

    override suspend fun lastUpdatedAt(clubId: Long): Instant? = updatedAt

    private fun touch() {
        updatedAt = clock.instant()
    }

    data class LayoutSeed(
        val clubId: Long,
        val zones: List<Zone>,
        val tables: List<Table>,
        val geometryJson: String,
        val statusOverrides: Map<Long?, Map<Long, TableStatus>> = emptyMap(),
    ) {
        val fingerprint: String = fingerprintFor(geometryJson)
        // geometryJson must stay in sync with resources/layouts/{clubId}/{fingerprint}.json
    }

    companion object {
        val DEFAULT_GEOMETRY_JSON: String =
            """
            {
              "version": 1,
              "zones": [
                { "id": "vip", "name": "VIP", "polygon": [[0,0], [4,0], [4,4], [0,4]] },
                { "id": "dancefloor", "name": "Dancefloor", "polygon": [[5,0], [12,0], [12,6], [5,6]] },
                { "id": "balcony", "name": "Balcony", "polygon": [[0,5], [4,5], [4,9], [0,9]] }
              ],
              "tables": [
                { "id": 1, "zoneId": "vip", "x": 1.0, "y": 1.0 },
                { "id": 2, "zoneId": "vip", "x": 3.0, "y": 1.0 },
                { "id": 3, "zoneId": "dancefloor", "x": 6.0, "y": 1.0 },
                { "id": 4, "zoneId": "dancefloor", "x": 8.0, "y": 2.5 },
                { "id": 5, "zoneId": "dancefloor", "x": 10.5, "y": 4.5 },
                { "id": 6, "zoneId": "balcony", "x": 1.0, "y": 6.0 },
                { "id": 7, "zoneId": "balcony", "x": 3.0, "y": 7.5 }
              ]
            }
            """.trimIndent()

        fun fingerprintFor(geometryJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(geometryJson.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun maxOfNotNull(a: Instant?, b: Instant?): Instant? =
            when {
                a == null -> b
                b == null -> a
                a.isAfter(b) -> a
                else -> b
            }
    }
}
