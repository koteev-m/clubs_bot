package com.example.bot.admin

import java.time.Instant

data class AdminClub(
    val id: Long,
    val name: String,
    val city: String,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminClubCreate(
    val name: String,
    val city: String,
    val isActive: Boolean = true,
)

data class AdminClubUpdate(
    val name: String? = null,
    val city: String? = null,
    val isActive: Boolean? = null,
)

interface AdminClubsRepository {
    suspend fun list(): List<AdminClub>

    suspend fun getById(id: Long): AdminClub?

    suspend fun create(request: AdminClubCreate): AdminClub

    suspend fun update(id: Long, request: AdminClubUpdate): AdminClub?

    suspend fun delete(id: Long): Boolean
}

data class AdminHall(
    val id: Long,
    val clubId: Long,
    val name: String,
    val isActive: Boolean,
    val layoutRevision: Long,
    val geometryFingerprint: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminHallCreate(
    val name: String,
    val geometryJson: String,
    val isActive: Boolean = false,
)

data class AdminHallUpdate(
    val name: String? = null,
    val geometryJson: String? = null,
)

interface AdminHallsRepository {
    suspend fun listForClub(clubId: Long): List<AdminHall>

    suspend fun getById(id: Long): AdminHall?

    suspend fun findActiveForClub(clubId: Long): AdminHall?

    suspend fun create(clubId: Long, request: AdminHallCreate): AdminHall

    suspend fun update(id: Long, request: AdminHallUpdate): AdminHall?

    suspend fun delete(id: Long): Boolean

    suspend fun makeActive(id: Long): AdminHall?

    suspend fun isHallNameTaken(clubId: Long, name: String, excludeHallId: Long? = null): Boolean
}
