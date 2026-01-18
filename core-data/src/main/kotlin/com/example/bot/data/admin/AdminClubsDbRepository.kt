package com.example.bot.data.admin

import com.example.bot.admin.AdminClub
import com.example.bot.admin.AdminClubCreate
import com.example.bot.admin.AdminClubUpdate
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withRetriedTx
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insertAndGetId

class AdminClubsDbRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminClubsRepository {
    override suspend fun list(): List<AdminClub> =
        withRetriedTx(name = "admin.clubs.list", readOnly = true, database = database) {
            Clubs
                .selectAll()
                .orderBy(Clubs.id, SortOrder.ASC)
                .map { it.toAdminClub() }
        }

    override suspend fun getById(id: Long): AdminClub? =
        withRetriedTx(name = "admin.clubs.get", readOnly = true, database = database) {
            Clubs
                .selectAll()
                .where { Clubs.id eq id.toInt() }
                .firstOrNull()
                ?.toAdminClub()
        }

    override suspend fun create(request: AdminClubCreate): AdminClub =
        withRetriedTx(name = "admin.clubs.create", database = database) {
            val now = clock.instant().toOffsetDateTime()
            val newId =
                Clubs.insertAndGetId {
                    it[name] = request.name
                    it[description] = null
                    it[adminChatId] = null
                    it[timezone] = "Europe/Moscow"
                    it[city] = request.city
                    it[genres] = "[]"
                    it[tags] = "[]"
                    it[logoUrl] = null
                    it[isActive] = request.isActive
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[generalTopicId] = null
                    it[bookingsTopicId] = null
                    it[listsTopicId] = null
                    it[qaTopicId] = null
                    it[mediaTopicId] = null
                    it[systemTopicId] = null
                }
            Clubs
                .selectAll()
                .where { Clubs.id eq newId.value }
                .first()
                .toAdminClub()
        }

    override suspend fun update(id: Long, request: AdminClubUpdate): AdminClub? =
        withRetriedTx(name = "admin.clubs.update", database = database) {
            val existing =
                Clubs
                    .selectAll()
                    .where { Clubs.id eq id.toInt() }
                    .firstOrNull()
                    ?: return@withRetriedTx null
            val now = clock.instant().toOffsetDateTime()
            Clubs.update({ Clubs.id eq id.toInt() }) {
                request.name?.let { value -> it[name] = value }
                request.city?.let { value -> it[city] = value }
                request.isActive?.let { value -> it[isActive] = value }
                it[updatedAt] = now
            }
            existing.toAdminClub().copy(
                name = request.name ?: existing[Clubs.name],
                city = request.city ?: existing[Clubs.city],
                isActive = request.isActive ?: existing[Clubs.isActive],
                updatedAt = now.toInstant(),
            )
        }

    override suspend fun delete(id: Long): Boolean =
        withRetriedTx(name = "admin.clubs.delete", database = database) {
            val now = clock.instant().toOffsetDateTime()
            Clubs.update({ Clubs.id eq id.toInt() }) {
                it[isActive] = false
                it[updatedAt] = now
            } > 0
        }

    private fun ResultRow.toAdminClub(): AdminClub =
        AdminClub(
            id = this[Clubs.id].value.toLong(),
            name = this[Clubs.name],
            city = this[Clubs.city],
            isActive = this[Clubs.isActive],
            createdAt = this[Clubs.createdAt].toInstant(),
            updatedAt = this[Clubs.updatedAt].toInstant(),
        )
}

private fun java.time.Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
