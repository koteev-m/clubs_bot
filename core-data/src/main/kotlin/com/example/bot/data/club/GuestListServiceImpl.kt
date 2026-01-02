package com.example.bot.data.club

import com.example.bot.club.GuestListBulkAddResult
import com.example.bot.club.GuestListConfig
import com.example.bot.club.GuestListEntryInfo
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListInfo
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListService
import com.example.bot.club.GuestListServiceError
import com.example.bot.club.GuestListServiceResult
import com.example.bot.club.GuestListStats
import com.example.bot.club.GuestListStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val DEFAULT_GUEST_LIST_TITLE: String = "Guest list"

class GuestListServiceImpl(
    private val guestListRepo: GuestListDbRepository,
    private val guestListEntryRepo: GuestListEntryDbRepository,
    private val config: GuestListConfig = GuestListConfig.fromEnv(),
    private val clock: Clock = Clock.systemUTC(),
    private val parser: GuestListBulkParser = GuestListBulkParser(),
) : GuestListService {
    override suspend fun createGuestList(
        promoterId: Long?,
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        limit: Int,
        title: String?,
    ): GuestListServiceResult<GuestListInfo> {
        if (limit <= 0) {
            return GuestListServiceResult.Failure(GuestListServiceError.InvalidLimit)
        }
        if (arrivalWindowStart != null && arrivalWindowEnd != null && !arrivalWindowStart.isBefore(arrivalWindowEnd)) {
            return GuestListServiceResult.Failure(GuestListServiceError.InvalidArrivalWindow)
        }
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GUEST_LIST_TITLE
        val record =
            guestListRepo.create(
                NewGuestList(
                    clubId = clubId,
                    eventId = eventId,
                    promoterId = promoterId,
                    ownerType = ownerType,
                    ownerUserId = ownerUserId,
                    title = normalizedTitle,
                    capacity = limit,
                    arrivalWindowStart = arrivalWindowStart,
                    arrivalWindowEnd = arrivalWindowEnd,
                    status = GuestListStatus.ACTIVE,
                ),
            )
        return GuestListServiceResult.Success(record.toInfo())
    }

    override suspend fun addEntriesBulk(listId: Long, rawText: String): GuestListServiceResult<GuestListBulkAddResult> {
        val list = guestListRepo.findById(listId) ?: return GuestListServiceResult.Failure(GuestListServiceError.GuestListNotFound)
        if (list.status != GuestListStatus.ACTIVE) {
            return GuestListServiceResult.Failure(GuestListServiceError.GuestListNotActive)
        }
        if (rawText.length > config.bulkMaxChars) {
            return GuestListServiceResult.Failure(GuestListServiceError.BulkParseTooLarge)
        }
        val parsed = parser.parse(rawText)
        val existingEntries = guestListEntryRepo.listByGuestList(listId)
        val existingKeys = existingEntries.map { normalizeNameKey(it.displayName) }.toMutableSet()
        val toInsert = mutableListOf<NewGuestListEntry>()
        var skippedExisting = 0
        for (displayName in parsed.entries) {
            val normalizedKey = normalizeNameKey(displayName)
            if (!existingKeys.add(normalizedKey)) {
                skippedExisting += 1
                continue
            }
            toInsert += NewGuestListEntry(displayName = displayName, telegramUserId = null)
        }
        val potentialTotal = existingEntries.size + toInsert.size
        if (potentialTotal > list.capacity) {
            return GuestListServiceResult.Failure(GuestListServiceError.GuestListLimitExceeded)
        }
        val inserted = guestListEntryRepo.insertMany(listId, toInsert)
        val stats = calculateStats(list.arrivalWindowEnd, existingEntries + inserted)
        return GuestListServiceResult.Success(
            GuestListBulkAddResult(
                addedCount = inserted.size,
                skippedDuplicatesCount = parsed.skippedDuplicates + skippedExisting,
                totalCount = existingEntries.size + inserted.size,
                stats = stats,
            ),
        )
    }

    override suspend fun addEntrySingle(
        listId: Long,
        displayName: String,
    ): GuestListServiceResult<GuestListEntryInfo> {
        val list = guestListRepo.findById(listId) ?: return GuestListServiceResult.Failure(GuestListServiceError.GuestListNotFound)
        if (list.status != GuestListStatus.ACTIVE) {
            return GuestListServiceResult.Failure(GuestListServiceError.GuestListNotActive)
        }
        val normalizedName = collapseSpaces(displayName)
        if (normalizedName.isEmpty()) {
            return GuestListServiceResult.Failure(GuestListServiceError.InvalidDisplayName)
        }
        val existingEntries = guestListEntryRepo.listByGuestList(listId)
        val normalizedKey = normalizeNameKey(normalizedName)
        val existing = existingEntries.firstOrNull { normalizeNameKey(it.displayName) == normalizedKey }
        if (existing != null) {
            return GuestListServiceResult.Success(existing.toInfo())
        }
        if (existingEntries.size >= list.capacity) {
            return GuestListServiceResult.Failure(GuestListServiceError.GuestListLimitExceeded)
        }
        val inserted = guestListEntryRepo.insertOne(listId, normalizedName)
        return GuestListServiceResult.Success(inserted.toInfo())
    }

    override suspend fun getStats(listId: Long, now: Instant): GuestListServiceResult<GuestListStats> {
        val list = guestListRepo.findById(listId) ?: return GuestListServiceResult.Failure(GuestListServiceError.GuestListNotFound)
        val entries = guestListEntryRepo.listByGuestList(listId)
        return GuestListServiceResult.Success(calculateStats(list.arrivalWindowEnd, entries, now))
    }

    private fun calculateStats(
        arrivalWindowEnd: Instant?,
        entries: List<GuestListEntryRecord>,
        now: Instant = Instant.now(clock),
    ): GuestListStats {
        val added = entries.size
        val invited = entries.count { it.status != GuestListEntryStatus.ADDED }
        val confirmed = entries.count { it.status == GuestListEntryStatus.CONFIRMED }
        val declined = entries.count { it.status == GuestListEntryStatus.DECLINED }
        val arrivedStatuses =
            setOf(
                GuestListEntryStatus.ARRIVED,
                GuestListEntryStatus.LATE,
                GuestListEntryStatus.CHECKED_IN,
            )
        val arrived = entries.count { it.status in arrivedStatuses }
        val explicitNoShow = entries.count { it.status == GuestListEntryStatus.NO_SHOW }
        val noShowDerived =
            if (arrivalWindowEnd == null) {
                0
            } else {
                val cutoff = arrivalWindowEnd.plus(Duration.ofMinutes(config.noShowGraceMinutes.toLong()))
                if (now <= cutoff) {
                    0
                } else {
                    entries.count {
                        it.status !in arrivedStatuses &&
                            it.status != GuestListEntryStatus.DECLINED &&
                            it.status != GuestListEntryStatus.DENIED &&
                            it.status != GuestListEntryStatus.NO_SHOW
                    }
                }
            }
        val noShow = explicitNoShow + noShowDerived
        return GuestListStats(
            added = added,
            invited = invited,
            confirmed = confirmed,
            declined = declined,
            arrived = arrived,
            noShow = noShow,
        )
    }
}

private fun GuestListRecord.toInfo(): GuestListInfo =
    GuestListInfo(
        id = id,
        clubId = clubId,
        eventId = eventId,
        promoterId = promoterId,
        ownerType = ownerType,
        ownerUserId = ownerUserId,
        title = title,
        capacity = capacity,
        arrivalWindowStart = arrivalWindowStart,
        arrivalWindowEnd = arrivalWindowEnd,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun GuestListEntryRecord.toInfo(): GuestListEntryInfo =
    GuestListEntryInfo(
        id = id,
        guestListId = guestListId,
        displayName = displayName,
        telegramUserId = telegramUserId,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
