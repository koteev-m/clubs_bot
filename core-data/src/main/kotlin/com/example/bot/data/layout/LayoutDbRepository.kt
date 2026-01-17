package com.example.bot.data.layout

import com.example.bot.data.db.withRetriedTx
import com.example.bot.layout.AdminTableCreate
import com.example.bot.layout.AdminTableUpdate
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.layout.parseArrivalWindowOrNull
import com.example.bot.layout.toRangeString
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class LayoutDbRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : LayoutRepository, AdminTablesRepository, LayoutAssetsRepository {
    private val logger = LoggerFactory.getLogger(LayoutDbRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout? =
        withRetriedTx(name = "layout.get", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            val hallId = hall.id
            val zones =
                HallZonesTable
                    .selectAll()
                    .where { HallZonesTable.hallId eq hallId }
                    .orderBy(HallZonesTable.sortOrder, SortOrder.ASC)
                    .map { it.toZone() }
            val tables =
                HallTablesTable
                    .selectAll()
                    .where { (HallTablesTable.hallId eq hallId) and (HallTablesTable.isActive eq true) }
                    .orderBy(HallTablesTable.id, SortOrder.ASC)
                    .map { it.toTable() }

            ClubLayout(
                clubId = clubId,
                eventId = eventId,
                zones = zones,
                tables = tables,
                assets =
                    LayoutAssets(
                        geometryUrl = "/assets/layouts/$clubId/${hall.geometryFingerprint}.json",
                        fingerprint = hall.geometryFingerprint,
                    ),
            )
        }

    override suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant? =
        withRetriedTx(name = "layout.lastUpdatedAt", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            bumpInstantWithRevision(hall.updatedAt, hall.layoutRevision)
        }

    override suspend fun listForClub(clubId: Long): List<Table> =
        withRetriedTx(name = "layout.listForClub", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx emptyList()
            HallTablesTable
                .selectAll()
                .where { (HallTablesTable.hallId eq hall.id) and (HallTablesTable.isActive eq true) }
                .orderBy(HallTablesTable.id, SortOrder.ASC)
                .map { it.toTable() }
        }

    override suspend fun listZonesForClub(clubId: Long): List<Zone> =
        withRetriedTx(name = "layout.listZones", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx emptyList()
            HallZonesTable
                .selectAll()
                .where { HallZonesTable.hallId eq hall.id }
                .orderBy(HallZonesTable.sortOrder, SortOrder.ASC)
                .map { it.toZone() }
        }

    override suspend fun findById(clubId: Long, id: Long): Table? =
        withRetriedTx(name = "layout.findById", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            HallTablesTable
                .selectAll()
                .where { (HallTablesTable.hallId eq hall.id) and (HallTablesTable.id eq id) }
                .firstOrNull()
                ?.toTable()
        }

    override suspend fun create(request: AdminTableCreate): Table =
        withRetriedTx(name = "layout.create", database = database) {
            val hall = requireActiveHall(request.clubId)
            val now = clock.instant()
            val nowOffset = now.toOffsetDateTime()
            val nextNumber =
                HallTablesTable
                    .selectAll()
                    .where { HallTablesTable.hallId eq hall.id }
                    .maxOfOrNull { it[HallTablesTable.tableNumber] }
                    ?.plus(1) ?: 1L
            val newId =
                HallTablesTable.insertAndGetId {
                it[hallId] = hall.id
                it[tableNumber] = nextNumber.toInt()
                it[label] = request.label
                it[capacity] = request.capacity
                it[minimumTier] = "standard"
                it[minDeposit] = request.minDeposit
                it[zoneId] = request.zone ?: defaultZoneId(hall.id)
                it[zone] = request.zone
                it[arrivalWindow] = request.arrivalWindow?.toRangeString()
                it[mysteryEligible] = request.mysteryEligible
                it[x] = 0.5
                it[y] = 0.5
                it[isActive] = true
                it[createdAt] = nowOffset
                it[updatedAt] = nowOffset
            }
            bumpHallRevision(hall.id, nowOffset)
            loadTableById(hall.id, newId.value) ?: error("Failed to create table")
        }

    override suspend fun update(request: AdminTableUpdate): Table? =
        withRetriedTx(name = "layout.update", database = database) {
            val hall = requireActiveHall(request.clubId)
            val existing = loadTableById(hall.id, request.id) ?: return@withRetriedTx null
            val now = clock.instant()
            val nowOffset = now.toOffsetDateTime()
            HallTablesTable.update({ (HallTablesTable.hallId eq hall.id) and (HallTablesTable.id eq request.id) }) {
                request.label?.let { value -> it[label] = value }
                request.capacity?.let { value -> it[capacity] = value }
                request.minDeposit?.let { value -> it[minDeposit] = value }
                request.zone?.let { value ->
                    it[zoneId] = value
                    it[zone] = value
                }
                it[arrivalWindow] = request.arrivalWindow?.toRangeString() ?: existing.arrivalWindow?.toRangeString()
                request.mysteryEligible?.let { value -> it[mysteryEligible] = value }
                it[updatedAt] = nowOffset
            }
            bumpHallRevision(hall.id, nowOffset)
            loadTableById(hall.id, request.id)
        }

    override suspend fun delete(clubId: Long, id: Long): Boolean =
        withRetriedTx(name = "layout.delete", database = database) {
            val hall = requireActiveHall(clubId)
            val nowOffset = clock.instant().toOffsetDateTime()
            val updated =
                HallTablesTable.update({ (HallTablesTable.hallId eq hall.id) and (HallTablesTable.id eq id) }) {
                    it[isActive] = false
                    it[updatedAt] = nowOffset
                }
            if (updated > 0) {
                bumpHallRevision(hall.id, nowOffset)
                true
            } else {
                false
            }
        }

    override suspend fun lastUpdatedAt(clubId: Long): Instant? =
        withRetriedTx(name = "layout.adminLastUpdated", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            bumpInstantWithRevision(hall.updatedAt, hall.layoutRevision)
        }

    override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? =
        withRetriedTx(name = "layout.geometry", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { (HallsTable.clubId eq clubId) and (HallsTable.geometryFingerprint eq fingerprint) }
                .firstOrNull()
                ?.get(HallsTable.geometryJson)
        }

    private fun loadActiveHall(clubId: Long): HallRow? {
        return HallsTable
            .selectAll()
            .where { (HallsTable.clubId eq clubId) and (HallsTable.isActive eq true) }
            .orderBy(HallsTable.id, SortOrder.ASC)
            .firstOrNull()
            ?.let { row ->
            HallRow(
                id = row[HallsTable.id].value,
                geometryFingerprint = row[HallsTable.geometryFingerprint],
                updatedAt = row[HallsTable.updatedAt].toInstant(),
                layoutRevision = row[HallsTable.layoutRevision],
            )
        }
    }

    private fun requireActiveHall(clubId: Long): HallRow {
        return loadActiveHall(clubId) ?: error("No active hall for club $clubId")
    }

    private fun loadTableById(hallId: Long, id: Long): Table? {
        return HallTablesTable
            .selectAll()
            .where { (HallTablesTable.hallId eq hallId) and (HallTablesTable.id eq id) }
            .firstOrNull()
            ?.toTable()
    }

    private fun defaultZoneId(hallId: Long): String {
        return HallZonesTable
            .selectAll()
            .where { HallZonesTable.hallId eq hallId }
            .orderBy(HallZonesTable.sortOrder, SortOrder.ASC)
            .firstOrNull()
            ?.get(HallZonesTable.zoneId)
            ?: "main"
    }

    private fun bumpHallRevision(hallId: Long, now: OffsetDateTime) {
        val currentRevision =
            HallsTable
                .selectAll()
                .where { HallsTable.id eq hallId }
                .limit(1)
                .firstOrNull()
                ?.get(HallsTable.layoutRevision)
                ?: return
        val updated =
            HallsTable.update({ HallsTable.id eq hallId }) {
                it[layoutRevision] = currentRevision + 1
                it[updatedAt] = now
            }
        if (updated == 0) {
            logger.warn("layout.revision.bump_failed hall_id={}", hallId)
        }
    }

    private fun ResultRow.toZone(): Zone =
        Zone(
            id = this[HallZonesTable.zoneId],
            name = this[HallZonesTable.name],
            tags = decodeTags(this[HallZonesTable.tags]),
            order = this[HallZonesTable.sortOrder],
        )

    private fun ResultRow.toTable(): Table =
        Table(
            id = this[HallTablesTable.id].value,
            zoneId = this[HallTablesTable.zoneId],
            label = this[HallTablesTable.label],
            capacity = this[HallTablesTable.capacity],
            minimumTier = this[HallTablesTable.minimumTier],
            status = TableStatus.FREE,
            minDeposit = this[HallTablesTable.minDeposit],
            zone = this[HallTablesTable.zone],
            arrivalWindow = this[HallTablesTable.arrivalWindow]?.let(::parseArrivalWindowOrNull),
            mysteryEligible = this[HallTablesTable.mysteryEligible],
        )

    private fun decodeTags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrElse { parseTagsFallback(raw) }
    }

    private fun parseTagsFallback(raw: String): List<String> =
        raw
            .removePrefix("[")
            .removeSuffix("]")
            .split(',')
            .mapNotNull { it.trim().trim('"').takeIf { value -> value.isNotBlank() } }

    private fun bumpInstantWithRevision(base: Instant, revision: Long): Instant {
        val nanoBump = (revision % 1_000_000_000L).toInt()
        return base.plusNanos(nanoBump.toLong())
    }

    data class HallRow(
        val id: Long,
        val geometryFingerprint: String,
        val updatedAt: Instant,
        val layoutRevision: Long,
    )

    companion object {
        fun fingerprintFor(geometryJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(geometryJson.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
