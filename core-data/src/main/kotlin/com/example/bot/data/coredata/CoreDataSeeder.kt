package com.example.bot.data.coredata

import com.example.bot.coredata.CoreDataSeed
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.layout.HallTablesTable
import com.example.bot.data.layout.HallZonesTable
import com.example.bot.data.layout.HallsTable
import com.example.bot.data.layout.LayoutDbRepository
import com.example.bot.layout.toRangeString
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory

class CoreDataSeeder(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(CoreDataSeeder::class.java)
    private val json = Json { encodeDefaults = true }

    suspend fun seedIfEmpty(seed: CoreDataSeed) {
        withRetriedTx(name = "coredata.seed", database = database) {
            if (Clubs.selectAll().limit(1).firstOrNull() == null) {
                val now = clock.instant().toOffsetDateTime()
                seed.clubs.forEach { club ->
                    Clubs.insert {
                        it[id] = EntityID(club.id.toInt(), Clubs)
                        it[name] = club.name
                        it[description] = null
                        it[timezone] = "Europe/Moscow"
                        it[adminChatId] = null
                        it[city] = club.city
                        it[genres] = encodeTags(club.genres)
                        it[tags] = encodeTags(club.tags)
                        it[logoUrl] = club.logoUrl
                        it[isActive] = club.isActive
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
                logger.info("coredata.seeded_clubs count={}", seed.clubs.size)
            }

            if (EventsTable.selectAll().limit(1).firstOrNull() == null) {
                seed.events.forEach { event ->
                    EventsTable.insert {
                        it[id] = event.id
                        it[clubId] = event.clubId
                        it[startAt] = event.startUtc.toOffsetDateTime()
                        it[endAt] = event.endUtc.toOffsetDateTime()
                        it[title] = event.title
                        it[isSpecial] = event.isSpecial
                        it[posterUrl] = null
                    }
                }
                logger.info("coredata.seeded_events count={}", seed.events.size)
            }

            if (HallsTable.selectAll().limit(1).firstOrNull() == null) {
                val now = clock.instant().toOffsetDateTime()
                seed.halls.forEach { hall ->
                    val fingerprint = LayoutDbRepository.fingerprintFor(hall.geometryJson)
                    HallsTable.insert {
                        it[id] = EntityID(hall.id, HallsTable)
                        it[clubId] = hall.clubId
                        it[name] = hall.name
                        it[isActive] = hall.isActive
                        it[layoutRevision] = hall.layoutRevision
                        it[geometryJson] = hall.geometryJson
                        it[geometryFingerprint] = fingerprint
                        it[createdAt] = now
                        it[updatedAt] = now
                    }

                    hall.zones.forEach { zone ->
                        HallZonesTable.insert {
                            it[hallId] = hall.id
                            it[zoneId] = zone.id
                            it[name] = zone.name
                            it[tags] = encodeTags(zone.tags)
                            it[sortOrder] = zone.order
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                    }

                    hall.tables.forEach { table ->
                        HallTablesTable.insert {
                            it[id] = EntityID(table.id, HallTablesTable)
                            it[hallId] = hall.id
                            it[tableNumber] = table.tableNumber
                            it[label] = table.label
                            it[capacity] = table.capacity
                            it[minimumTier] = table.minimumTier
                            it[minDeposit] = table.minDeposit
                            it[zoneId] = table.zoneId
                            it[zone] = table.zone
                            it[arrivalWindow] = table.arrivalWindow?.toRangeString()
                            it[mysteryEligible] = table.mysteryEligible
                            it[x] = table.x
                            it[y] = table.y
                            it[isActive] = table.isActive
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                    }
                }
                logger.info("coredata.seeded_halls count={}", seed.halls.size)
            }
        }
    }

    private fun encodeTags(tags: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), tags)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
