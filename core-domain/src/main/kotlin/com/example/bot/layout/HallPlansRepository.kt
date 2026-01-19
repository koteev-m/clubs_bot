package com.example.bot.layout

import java.time.Instant

data class HallPlan(
    val hallId: Long,
    val bytes: ByteArray,
    val contentType: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface HallPlansRepository {
    suspend fun upsertPlan(
        hallId: Long,
        contentType: String,
        bytes: ByteArray,
        sha256: String,
        sizeBytes: Long,
    ): HallPlan

    suspend fun getPlanForClub(clubId: Long, hallId: Long): HallPlan?
}
