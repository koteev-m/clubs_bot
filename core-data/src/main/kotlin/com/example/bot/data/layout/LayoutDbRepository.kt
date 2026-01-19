package com.example.bot.data.layout

import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.layout.AdminTableCreate
import com.example.bot.layout.AdminTableUpdate
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.HallPlan
import com.example.bot.layout.HallPlansRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.TableNumberConflictException
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
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class LayoutDbRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : LayoutRepository, AdminTablesRepository, LayoutAssetsRepository, HallPlansRepository {
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
            listTablesForHall(hall.id)
        }

    override suspend fun listZonesForClub(clubId: Long): List<Zone> =
        withRetriedTx(name = "layout.listZones", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx emptyList()
            listZonesForHall(hall.id)
        }

    override suspend fun findById(clubId: Long, id: Long): Table? =
        withRetriedTx(name = "layout.findById", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            loadTableById(hall.id, id)
        }

    override suspend fun create(request: AdminTableCreate): Table =
        withRetriedTx(name = "layout.create", database = database) {
            val hall = requireActiveHall(request.clubId)
            createTableForHall(hall.id, request)
        }

    override suspend fun update(request: AdminTableUpdate): Table? =
        withRetriedTx(name = "layout.update", database = database) {
            val hall = requireActiveHall(request.clubId)
            updateTableForHall(hall.id, request)
        }

    override suspend fun delete(clubId: Long, id: Long): Boolean =
        withRetriedTx(name = "layout.delete", database = database) {
            val hall = requireActiveHall(clubId)
            deleteTableForHall(hall.id, id)
        }

    override suspend fun lastUpdatedAt(clubId: Long): Instant? =
        withRetriedTx(name = "layout.adminLastUpdated", readOnly = true, database = database) {
            val hall = loadActiveHall(clubId) ?: return@withRetriedTx null
            bumpInstantWithRevision(hall.updatedAt, hall.layoutRevision)
        }

    override suspend fun listForHall(hallId: Long): List<Table> =
        withRetriedTx(name = "layout.listForHall", readOnly = true, database = database) {
            listTablesForHall(hallId)
        }

    override suspend fun listZonesForHall(hallId: Long): List<Zone> =
        withRetriedTx(name = "layout.listZonesForHall", readOnly = true, database = database) {
            HallZonesTable
                .selectAll()
                .where { HallZonesTable.hallId eq hallId }
                .orderBy(HallZonesTable.sortOrder, SortOrder.ASC)
                .map { it.toZone() }
        }

    override suspend fun findByIdForHall(hallId: Long, id: Long): Table? =
        withRetriedTx(name = "layout.findByIdForHall", readOnly = true, database = database) {
            loadTableById(hallId, id)
        }

    override suspend fun createForHall(request: AdminTableCreate): Table =
        withRetriedTx(name = "layout.createForHall", database = database) {
            val hallId = request.hallId ?: error("Hall id is required for createForHall")
            createTableForHall(hallId, request)
        }

    override suspend fun updateForHall(request: AdminTableUpdate): Table? =
        withRetriedTx(name = "layout.updateForHall", database = database) {
            val hallId = request.hallId ?: error("Hall id is required for updateForHall")
            updateTableForHall(hallId, request)
        }

    override suspend fun deleteForHall(hallId: Long, id: Long): Boolean =
        withRetriedTx(name = "layout.deleteForHall", database = database) {
            deleteTableForHall(hallId, id)
        }

    override suspend fun lastUpdatedAtForHall(hallId: Long): Instant? =
        withRetriedTx(name = "layout.adminLastUpdatedForHall", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { HallsTable.id eq hallId }
                .firstOrNull()
                ?.let { row ->
                    bumpInstantWithRevision(row[HallsTable.updatedAt].toInstant(), row[HallsTable.layoutRevision])
                }
        }

    override suspend fun isTableNumberTaken(hallId: Long, tableNumber: Int, excludeTableId: Long?): Boolean =
        withRetriedTx(name = "layout.tableNumberTaken", readOnly = true, database = database) {
            HallTablesTable
                .selectAll()
                .where {
                    (HallTablesTable.hallId eq hallId) and
                        (HallTablesTable.tableNumber eq tableNumber) and
                        (HallTablesTable.isActive eq true) and
                        (excludeTableId?.let { HallTablesTable.id neq it } ?: Op.TRUE)
                }
                .limit(1)
                .any()
        }

    override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? =
        withRetriedTx(name = "layout.geometry", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { (HallsTable.clubId eq clubId) and (HallsTable.geometryFingerprint eq fingerprint) }
                .firstOrNull()
                ?.get(HallsTable.geometryJson)
        }

    override suspend fun upsertPlan(
        hallId: Long,
        contentType: String,
        bytes: ByteArray,
        sha256: String,
        sizeBytes: Long,
    ): HallPlan =
        withRetriedTx(name = "layout.plan.upsert", database = database) {
            val now = clock.instant()
            val nowOffset = now.toOffsetDateTime()
            val updated =
                HallPlansTable.update({ HallPlansTable.hallId eq hallId }) {
                    it[HallPlansTable.bytes] = bytes
                    it[HallPlansTable.contentType] = contentType
                    it[HallPlansTable.sha256] = sha256
                    it[HallPlansTable.sizeBytes] = sizeBytes
                    it[HallPlansTable.updatedAt] = nowOffset
                }
            if (updated == 0) {
                HallPlansTable.insert {
                    it[HallPlansTable.hallId] = hallId
                    it[HallPlansTable.bytes] = bytes
                    it[HallPlansTable.contentType] = contentType
                    it[HallPlansTable.sha256] = sha256
                    it[HallPlansTable.sizeBytes] = sizeBytes
                    it[HallPlansTable.createdAt] = nowOffset
                    it[HallPlansTable.updatedAt] = nowOffset
                }
            }
            HallsTable.update({ HallsTable.id eq hallId }) {
                it[layoutRevision] = HallsTable.layoutRevision + 1
                it[updatedAt] = nowOffset
            }
            HallPlansTable
                .selectAll()
                .where { HallPlansTable.hallId eq hallId }
                .first()
                .toHallPlan()
        }

    override suspend fun getPlanForClub(clubId: Long, hallId: Long): HallPlan? =
        withRetriedTx(name = "layout.plan.get", readOnly = true, database = database) {
            (HallPlansTable innerJoin HallsTable)
                .selectAll()
                .where { (HallPlansTable.hallId eq hallId) and (HallsTable.clubId eq clubId) }
                .firstOrNull()
                ?.toHallPlan()
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

    private fun ResultRow.toHallPlan(): HallPlan =
        HallPlan(
            hallId = this[HallPlansTable.hallId],
            bytes = this[HallPlansTable.bytes],
            contentType = this[HallPlansTable.contentType],
            sha256 = this[HallPlansTable.sha256],
            sizeBytes = this[HallPlansTable.sizeBytes],
            createdAt = this[HallPlansTable.createdAt].toInstant(),
            updatedAt = this[HallPlansTable.updatedAt].toInstant(),
        )

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

    private fun listTablesForHall(hallId: Long): List<Table> {
        return HallTablesTable
            .selectAll()
            .where { (HallTablesTable.hallId eq hallId) and (HallTablesTable.isActive eq true) }
            .orderBy(HallTablesTable.id, SortOrder.ASC)
            .map { it.toTable() }
    }

    private fun createTableForHall(hallId: Long, request: AdminTableCreate): Table {
        val now = clock.instant()
        val nowOffset = now.toOffsetDateTime()
        val nextNumber =
            request.tableNumber?.toLong()
                ?: HallTablesTable
                    .selectAll()
                    .where { HallTablesTable.hallId eq hallId }
                    .maxOfOrNull { it[HallTablesTable.tableNumber] }
                    ?.plus(1) ?: 1L
        val newId =
            try {
                HallTablesTable.insertAndGetId {
                    it[HallTablesTable.hallId] = hallId
                    it[tableNumber] = nextNumber.toInt()
                    it[label] = request.label
                    it[capacity] = request.capacity
                    it[minimumTier] = "standard"
                    it[minDeposit] = request.minDeposit
                    it[zoneId] = request.zone ?: defaultZoneId(hallId)
                    it[zone] = request.zone
                    it[arrivalWindow] = request.arrivalWindow?.toRangeString()
                    it[mysteryEligible] = request.mysteryEligible
                    it[x] = request.x ?: 0.5
                    it[y] = request.y ?: 0.5
                    it[isActive] = true
                    it[createdAt] = nowOffset
                    it[updatedAt] = nowOffset
                }
            } catch (error: Throwable) {
                if (error.isUniqueViolation()) {
                    throw TableNumberConflictException()
                }
                throw error
            }
        bumpHallRevision(hallId, nowOffset)
        return loadTableById(hallId, newId.value) ?: error("Failed to create table")
    }

    private fun updateTableForHall(hallId: Long, request: AdminTableUpdate): Table? {
        val existing = loadTableById(hallId, request.id) ?: return null
        val now = clock.instant()
        val nowOffset = now.toOffsetDateTime()
        try {
            HallTablesTable.update({ (HallTablesTable.hallId eq hallId) and (HallTablesTable.id eq request.id) }) {
                request.label?.let { value -> it[label] = value }
                request.capacity?.let { value -> it[capacity] = value }
                request.minDeposit?.let { value -> it[minDeposit] = value }
                request.zone?.let { value ->
                    it[zoneId] = value
                    it[zone] = value
                }
                request.tableNumber?.let { value -> it[tableNumber] = value }
                request.x?.let { value -> it[x] = value }
                request.y?.let { value -> it[y] = value }
                it[arrivalWindow] = request.arrivalWindow?.toRangeString() ?: existing.arrivalWindow?.toRangeString()
                request.mysteryEligible?.let { value -> it[mysteryEligible] = value }
                it[updatedAt] = nowOffset
            }
        } catch (error: Throwable) {
            if (error.isUniqueViolation()) {
                throw TableNumberConflictException()
            }
            throw error
        }
        bumpHallRevision(hallId, nowOffset)
        return loadTableById(hallId, request.id)
    }

    private fun deleteTableForHall(hallId: Long, id: Long): Boolean {
        val nowOffset = clock.instant().toOffsetDateTime()
        val updated =
            HallTablesTable.update({ (HallTablesTable.hallId eq hallId) and (HallTablesTable.id eq id) }) {
                it[isActive] = false
                it[updatedAt] = nowOffset
            }
        return if (updated > 0) {
            bumpHallRevision(hallId, nowOffset)
            true
        } else {
            false
        }
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
        val updated =
            HallsTable.update({ HallsTable.id eq hallId }) {
                it[layoutRevision] = HallsTable.layoutRevision + 1
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
            tableNumber = this[HallTablesTable.tableNumber],
            x = this[HallTablesTable.x],
            y = this[HallTablesTable.y],
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
