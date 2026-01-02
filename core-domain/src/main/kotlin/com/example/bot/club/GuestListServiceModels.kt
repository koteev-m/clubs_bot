package com.example.bot.club

import com.example.bot.config.envInt
import java.time.Instant

/** Конфигурация сервисов гостевых листов. */
data class GuestListConfig(
    val bulkMaxChars: Int = envInt(ENV_GUEST_LIST_BULK_MAX_CHARS, DEFAULT_BULK_MAX_CHARS),
    val noShowGraceMinutes: Int = envInt(ENV_GUEST_LIST_NO_SHOW_GRACE_MINUTES, DEFAULT_NO_SHOW_GRACE_MINUTES),
) {
    companion object {
        const val ENV_GUEST_LIST_BULK_MAX_CHARS: String = "GUEST_LIST_BULK_MAX_CHARS"
        const val ENV_GUEST_LIST_NO_SHOW_GRACE_MINUTES: String = "GUEST_LIST_NO_SHOW_GRACE_MINUTES"
        private const val DEFAULT_BULK_MAX_CHARS: Int = 20_000
        private const val DEFAULT_NO_SHOW_GRACE_MINUTES: Int = 30

        fun fromEnv(): GuestListConfig = GuestListConfig()
    }
}

/** Ошибки домена гостевых листов (P0.1). */
sealed interface GuestListServiceError {
    data object GuestListNotFound : GuestListServiceError
    data object GuestListNotActive : GuestListServiceError
    data object GuestListLimitExceeded : GuestListServiceError
    data object BulkParseTooLarge : GuestListServiceError
    data object InvalidArrivalWindow : GuestListServiceError
    data object InvalidLimit : GuestListServiceError
}

sealed interface GuestListServiceResult<out T> {
    data class Success<T>(val value: T) : GuestListServiceResult<T>

    data class Failure(val error: GuestListServiceError) : GuestListServiceResult<Nothing>
}

data class GuestListInfo(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val promoterId: Long?,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val title: String,
    val capacity: Int,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val status: GuestListStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GuestListEntryInfo(
    val id: Long,
    val guestListId: Long,
    val displayName: String,
    val telegramUserId: Long?,
    val status: GuestListEntryStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GuestListStats(
    val added: Int,
    val invited: Int,
    val confirmed: Int,
    val declined: Int,
    val arrived: Int,
    val noShow: Int,
)

data class GuestListBulkAddResult(
    val addedCount: Int,
    val skippedDuplicatesCount: Int,
    val totalCount: Int,
    val stats: GuestListStats,
)

interface GuestListService {
    suspend fun createGuestList(
        promoterId: Long?,
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        limit: Int,
        title: String?,
    ): GuestListServiceResult<GuestListInfo>

    suspend fun addEntriesBulk(
        listId: Long,
        rawText: String,
    ): GuestListServiceResult<GuestListBulkAddResult>

    suspend fun addEntrySingle(
        listId: Long,
        displayName: String,
    ): GuestListServiceResult<GuestListEntryInfo>

    suspend fun getStats(
        listId: Long,
        now: Instant = Instant.now(),
    ): GuestListServiceResult<GuestListStats>
}
