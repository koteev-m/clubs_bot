package com.example.bot.layout

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class InMemoryLayoutRepository(
    layouts: List<LayoutSeed>,
    private val updatedAt: Instant? = null,
    private val eventUpdatedAt: Map<Long?, Instant> = emptyMap(),
) : LayoutRepository {
    private val layoutsByClub: Map<Long, LayoutSeed> = layouts.associateBy { it.clubId }

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
