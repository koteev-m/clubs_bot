package com.example.bot.data.admin

import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallCreate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.layout.HallsTable
import com.example.bot.data.layout.LayoutDbRepository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
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

class AdminHallsDbRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminHallsRepository {
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
            HallsTable.update({ HallsTable.id eq id }) {
                request.name?.let { value -> it[name] = value }
                newGeometryJson?.let { value -> it[geometryJson] = value }
                newFingerprint?.let { value -> it[geometryFingerprint] = value }
                it[layoutRevision] = existing[HallsTable.layoutRevision] + 1
                it[updatedAt] = now
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
}

private fun java.time.Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
