package com.example.bot.data.admin

import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallCreate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.admin.InvalidHallGeometryException
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.layout.HallZonesTable
import com.example.bot.data.layout.HallsTable
import com.example.bot.data.layout.LayoutDbRepository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insert

class AdminHallsDbRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminHallsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listForClub(clubId: Long): List<AdminHall> =
        withRetriedTx(name = "admin.halls.list", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { HallsTable.clubId eq clubId }
                .orderBy(HallsTable.id, SortOrder.ASC)
                .map { it.toAdminHall() }
        }

    override suspend fun getById(id: Long): AdminHall? =
        withRetriedTx(name = "admin.halls.get", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { HallsTable.id eq id }
                .firstOrNull()
                ?.toAdminHall()
        }

    override suspend fun findActiveForClub(clubId: Long): AdminHall? =
        withRetriedTx(name = "admin.halls.active", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where { (HallsTable.clubId eq clubId) and (HallsTable.isActive eq true) }
                .orderBy(HallsTable.id, SortOrder.ASC)
                .firstOrNull()
                ?.toAdminHall()
        }

    override suspend fun create(clubId: Long, request: AdminHallCreate): AdminHall =
        withRetriedTx(name = "admin.halls.create", database = database) {
            val now = clock.instant().toOffsetDateTime()
            val zones = parseZones(request.geometryJson)
            if (request.isActive) {
                HallsTable.update({ HallsTable.clubId eq clubId }) {
                    it[isActive] = false
                    it[updatedAt] = now
                }
            }
            val fingerprint = LayoutDbRepository.fingerprintFor(request.geometryJson)
            val newId =
                HallsTable.insertAndGetId {
                    it[HallsTable.clubId] = clubId
                    it[name] = request.name
                    it[isActive] = request.isActive
                    it[layoutRevision] = 1
                    it[geometryJson] = request.geometryJson
                    it[geometryFingerprint] = fingerprint
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            zones.forEachIndexed { index, zone ->
                HallZonesTable.insert {
                    it[hallId] = newId.value
                    it[zoneId] = zone.id
                    it[name] = zone.name
                    it[tags] = "[]"
                    it[sortOrder] = index + 1
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            HallsTable
                .selectAll()
                .where { HallsTable.id eq newId.value }
                .first()
                .toAdminHall()
        }

    override suspend fun update(id: Long, request: AdminHallUpdate): AdminHall? =
        withRetriedTx(name = "admin.halls.update", database = database) {
            val existing =
                HallsTable
                    .selectAll()
                    .where { HallsTable.id eq id }
                    .firstOrNull()
                    ?: return@withRetriedTx null
            val now = clock.instant().toOffsetDateTime()
            val newGeometryJson = request.geometryJson
            val newFingerprint = newGeometryJson?.let { LayoutDbRepository.fingerprintFor(it) }
            val zones = newGeometryJson?.let { parseZones(it) }
            HallsTable.update({ HallsTable.id eq id }) {
                request.name?.let { value -> it[name] = value }
                newGeometryJson?.let { value -> it[geometryJson] = value }
                newFingerprint?.let { value -> it[geometryFingerprint] = value }
                it[layoutRevision] = existing[HallsTable.layoutRevision] + 1
                it[updatedAt] = now
            }
            if (zones != null) {
                upsertZones(id, zones, now)
            }
            existing.toAdminHall().copy(
                name = request.name ?: existing[HallsTable.name],
                geometryFingerprint = newFingerprint ?: existing[HallsTable.geometryFingerprint],
                layoutRevision = existing[HallsTable.layoutRevision] + 1,
                updatedAt = now.toInstant(),
            )
        }

    override suspend fun delete(id: Long): Boolean =
        withRetriedTx(name = "admin.halls.delete", database = database) {
            val now = clock.instant().toOffsetDateTime()
            val hall =
                HallsTable
                    .selectAll()
                    .where { HallsTable.id eq id }
                    .firstOrNull()
                    ?: return@withRetriedTx false
            val clubId = hall[HallsTable.clubId]
            if (hall[HallsTable.isActive]) {
                val replacement =
                    HallsTable
                        .selectAll()
                        .where { (HallsTable.clubId eq clubId) and (HallsTable.id neq id) }
                        .orderBy(HallsTable.id, SortOrder.ASC)
                        .firstOrNull()
                        ?: return@withRetriedTx false
                HallsTable.update({ HallsTable.clubId eq clubId }) {
                    it[isActive] = false
                    it[updatedAt] = now
                }
                HallsTable.update({ HallsTable.id eq replacement[HallsTable.id].value }) {
                    it[isActive] = true
                    it[layoutRevision] = replacement[HallsTable.layoutRevision] + 1
                    it[updatedAt] = now
                }
            }
            HallsTable.update({ HallsTable.id eq id }) {
                it[isActive] = false
                it[layoutRevision] = hall[HallsTable.layoutRevision] + 1
                it[updatedAt] = now
            } > 0
        }

    override suspend fun makeActive(id: Long): AdminHall? =
        withRetriedTx(name = "admin.halls.makeActive", database = database) {
            val hall =
                HallsTable
                    .selectAll()
                    .where { HallsTable.id eq id }
                    .firstOrNull()
                    ?: return@withRetriedTx null
            val now = clock.instant().toOffsetDateTime()
            val clubId = hall[HallsTable.clubId]
            HallsTable.update({ HallsTable.clubId eq clubId }) {
                it[isActive] = false
                it[updatedAt] = now
            }
            val nextRevision = hall[HallsTable.layoutRevision] + 1
            HallsTable.update({ HallsTable.id eq id }) {
                it[isActive] = true
                it[layoutRevision] = nextRevision
                it[updatedAt] = now
            }
            hall.toAdminHall().copy(isActive = true, layoutRevision = nextRevision, updatedAt = now.toInstant())
        }

    override suspend fun isHallNameTaken(clubId: Long, name: String, excludeHallId: Long?): Boolean =
        withRetriedTx(name = "admin.halls.nameTaken", readOnly = true, database = database) {
            HallsTable
                .selectAll()
                .where {
                        (HallsTable.clubId eq clubId) and
                        (HallsTable.name.lowerCase() eq name.lowercase()) and
                        (excludeHallId?.let { HallsTable.id neq it } ?: Op.TRUE)
                }
                .limit(1)
                .any()
        }

    private fun ResultRow.toAdminHall(): AdminHall =
        AdminHall(
            id = this[HallsTable.id].value,
            clubId = this[HallsTable.clubId],
            name = this[HallsTable.name],
            isActive = this[HallsTable.isActive],
            layoutRevision = this[HallsTable.layoutRevision],
            geometryFingerprint = this[HallsTable.geometryFingerprint],
            createdAt = this[HallsTable.createdAt].toInstant(),
            updatedAt = this[HallsTable.updatedAt].toInstant(),
        )

    private fun parseZones(geometryJson: String): List<HallZoneSpec> {
        val root = runCatching { json.parseToJsonElement(geometryJson) }.getOrNull()
        val rootObject = root as? JsonObject ?: throw InvalidHallGeometryException()
        val zones = rootObject["zones"] as? JsonArray ?: throw InvalidHallGeometryException()
        if (zones.isEmpty()) throw InvalidHallGeometryException()
        val parsed =
            zones.map { element ->
                val zoneObject = element as? JsonObject ?: throw InvalidHallGeometryException()
                val id = zoneObject.zoneField("id")
                val name = zoneObject.zoneField("name")
                HallZoneSpec(id = id, name = name)
            }
        if (parsed.map { it.id }.distinct().size != parsed.size) {
            throw InvalidHallGeometryException()
        }
        return parsed
    }

    private fun JsonObject.zoneField(field: String): String {
        val primitive = this[field] as? JsonPrimitive ?: throw InvalidHallGeometryException()
        if (!primitive.isString) throw InvalidHallGeometryException()
        val raw = primitive.content.trim()
        if (raw.isBlank()) throw InvalidHallGeometryException()
        return raw
    }

    private fun upsertZones(hallId: Long, zones: List<HallZoneSpec>, now: OffsetDateTime) {
        zones.forEachIndexed { index, zone ->
            val updated =
                HallZonesTable.update({ (HallZonesTable.hallId eq hallId) and (HallZonesTable.zoneId eq zone.id) }) {
                    it[name] = zone.name
                    it[sortOrder] = index + 1
                    it[updatedAt] = now
                }
            if (updated == 0) {
                HallZonesTable.insert {
                    it[HallZonesTable.hallId] = hallId
                    it[HallZonesTable.zoneId] = zone.id
                    it[name] = zone.name
                    it[tags] = "[]"
                    it[sortOrder] = index + 1
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    private data class HallZoneSpec(
        val id: String,
        val name: String,
    )
}

private fun java.time.Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
