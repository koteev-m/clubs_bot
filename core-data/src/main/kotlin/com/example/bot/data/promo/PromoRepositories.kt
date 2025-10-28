package com.example.bot.data.promo

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import com.example.bot.promo.BookingTemplate
import com.example.bot.promo.BookingTemplateError
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateResult
import com.example.bot.promo.BookingTemplateSignature
import com.example.bot.promo.PromoAttribution
import com.example.bot.promo.PromoAttributionError
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionResult
import com.example.bot.promo.PromoLink
import com.example.bot.promo.PromoLinkError
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.promo.PromoLinkResult
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

private object PromoLinksTable : Table("promo_links") {
    val id = long("id").autoIncrement()
    val promoterUserId = long("promoter_user_id")
    val clubId = long("club_id").nullable()
    val utmSource = text("utm_source")
    val utmMedium = text("utm_medium")
    val utmCampaign = text("utm_campaign")
    val utmContent = text("utm_content").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}

private object PromoAttributionTable : Table("promo_attribution") {
    val id = long("id").autoIncrement()
    val bookingId = uuid("booking_id")
    val promoLinkId = long("promo_link_id")
    val promoterUserId = long("promoter_user_id")
    val utmSource = text("utm_source")
    val utmMedium = text("utm_medium")
    val utmCampaign = text("utm_campaign")
    val utmContent = text("utm_content").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}

private object BookingTemplatesTable : Table("booking_templates") {
    val id = long("id").autoIncrement()
    val promoterUserId = long("promoter_user_id")
    val clubId = long("club_id")
    val tableCapacityMin = integer("table_capacity_min")
    val notes = text("notes").nullable()
    val isActive = bool("is_active")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}

private val hexFormat: HexFormat = HexFormat.of()

class PromoLinkRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : PromoLinkRepository {
    override suspend fun issueLink(
        promoterUserId: Long,
        clubId: Long?,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoLink {
        val now = Instant.now(clock)
        return withTxRetry {
            transaction(db) {
                PromoLinksTable
                    .insert {
                        it[PromoLinksTable.promoterUserId] = promoterUserId
                        it[PromoLinksTable.clubId] = clubId
                        it[PromoLinksTable.utmSource] = utmSource
                        it[PromoLinksTable.utmMedium] = utmMedium
                        it[PromoLinksTable.utmCampaign] = utmCampaign
                        it[PromoLinksTable.utmContent] = utmContent
                        it[PromoLinksTable.createdAt] = now.atOffset(ZoneOffset.UTC)
                    }
                    .resultedValues
                    ?.single()
                    ?.toPromoLink()
                    ?: error("Failed to insert promo link")
            }
        }
    }

    override suspend fun get(id: Long): PromoLink? {
        return withTxRetry {
            transaction(db) {
                PromoLinksTable
                    .selectAll()
                    .where { PromoLinksTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toPromoLink()
            }
        }
    }

    override suspend fun listByPromoter(
        promoterUserId: Long,
        clubId: Long?,
    ): List<PromoLink> {
        return withTxRetry {
            transaction(db) {
                val query =
                    PromoLinksTable
                        .selectAll()
                        .where { PromoLinksTable.promoterUserId eq promoterUserId }
                clubId?.let { desiredClub -> query.andWhere { PromoLinksTable.clubId eq desiredClub } }
                query
                    .orderBy(PromoLinksTable.createdAt, SortOrder.DESC)
                    .map { it.toPromoLink() }
            }
        }
    }

    override suspend fun deactivate(id: Long): PromoLinkResult<Unit> {
        return withTxRetry {
            transaction(db) {
                val affected = PromoLinksTable.deleteWhere { PromoLinksTable.id eq id }
                if (affected == 0) {
                    PromoLinkResult.Failure(PromoLinkError.NotFound)
                } else {
                    PromoLinkResult.Success(Unit)
                }
            }
        }
    }
}

class PromoAttributionRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : PromoAttributionRepository {
    override suspend fun attachUnique(
        bookingId: UUID,
        promoLinkId: Long,
        promoterUserId: Long,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoAttributionResult<PromoAttribution> {
        val now = Instant.now(clock)
        return try {
            val inserted =
                withTxRetry {
                    transaction(db) {
                        PromoAttributionTable
                            .insert {
                                it[PromoAttributionTable.bookingId] = bookingId
                                it[PromoAttributionTable.promoLinkId] = promoLinkId
                                it[PromoAttributionTable.promoterUserId] = promoterUserId
                                it[PromoAttributionTable.utmSource] = utmSource
                                it[PromoAttributionTable.utmMedium] = utmMedium
                                it[PromoAttributionTable.utmCampaign] = utmCampaign
                                it[PromoAttributionTable.utmContent] = utmContent
                                it[PromoAttributionTable.createdAt] = now.atOffset(ZoneOffset.UTC)
                            }
                            .resultedValues
                            ?.single()
                            ?.toPromoAttribution()
                            ?: error("Failed to insert promo attribution")
                    }
                }
            PromoAttributionResult.Success(inserted)
        } catch (ex: Throwable) {
            if (ex.isUniqueViolation()) {
                PromoAttributionResult.Failure(PromoAttributionError.AlreadyAttributed)
            } else {
                throw ex
            }
        }
    }

    override suspend fun findByBooking(bookingId: UUID): PromoAttribution? {
        return withTxRetry {
            transaction(db) {
                PromoAttributionTable
                    .selectAll()
                    .where { PromoAttributionTable.bookingId eq bookingId }
                    .limit(1)
                    .firstOrNull()
                    ?.toPromoAttribution()
            }
        }
    }
}

class BookingTemplateRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : BookingTemplateRepository {
    override suspend fun create(
        promoterUserId: Long,
        clubId: Long,
        tableCapacityMin: Int,
        notes: String?,
    ): BookingTemplate {
        val now = Instant.now(clock)
        return withTxRetry {
            transaction(db) {
                BookingTemplatesTable
                    .insert {
                        it[BookingTemplatesTable.promoterUserId] = promoterUserId
                        it[BookingTemplatesTable.clubId] = clubId
                        it[BookingTemplatesTable.tableCapacityMin] = tableCapacityMin
                        it[BookingTemplatesTable.notes] = notes
                        it[BookingTemplatesTable.isActive] = true
                        it[BookingTemplatesTable.createdAt] = now.atOffset(ZoneOffset.UTC)
                    }
                    .resultedValues
                    ?.single()
                    ?.toBookingTemplate()
                    ?: error("Failed to insert booking template")
            }
        }
    }

    override suspend fun update(
        id: Long,
        tableCapacityMin: Int,
        notes: String?,
        isActive: Boolean,
    ): BookingTemplateResult<BookingTemplate> {
        return withTxRetry {
            transaction(db) {
                val updated =
                    BookingTemplatesTable.update({ BookingTemplatesTable.id eq id }) {
                        it[BookingTemplatesTable.tableCapacityMin] = tableCapacityMin
                        it[BookingTemplatesTable.notes] = notes
                        it[BookingTemplatesTable.isActive] = isActive
                    }
                if (updated == 0) {
                    BookingTemplateResult.Failure(BookingTemplateError.NotFound)
                } else {
                    val row =
                        BookingTemplatesTable
                            .selectAll()
                            .where { BookingTemplatesTable.id eq id }
                            .limit(1)
                            .first()
                    BookingTemplateResult.Success(row.toBookingTemplate())
                }
            }
        }
    }

    override suspend fun deactivate(id: Long): BookingTemplateResult<Unit> {
        return withTxRetry {
            transaction(db) {
                val updated =
                    BookingTemplatesTable.update({ BookingTemplatesTable.id eq id }) {
                        it[BookingTemplatesTable.isActive] = false
                    }
                if (updated == 0) {
                    BookingTemplateResult.Failure(BookingTemplateError.NotFound)
                } else {
                    BookingTemplateResult.Success(Unit)
                }
            }
        }
    }

    override suspend fun get(id: Long): BookingTemplate? {
        return withTxRetry {
            transaction(db) {
                BookingTemplatesTable
                    .selectAll()
                    .where { BookingTemplatesTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toBookingTemplate()
            }
        }
    }

    override suspend fun listByOwner(promoterUserId: Long): List<BookingTemplate> {
        return withTxRetry {
            transaction(db) {
                BookingTemplatesTable
                    .selectAll()
                    .where { BookingTemplatesTable.promoterUserId eq promoterUserId }
                    .orderBy(BookingTemplatesTable.createdAt, SortOrder.DESC)
                    .map { it.toBookingTemplate() }
            }
        }
    }

    override suspend fun listByClub(
        clubId: Long,
        onlyActive: Boolean,
    ): List<BookingTemplate> {
        return withTxRetry {
            transaction(db) {
                val query =
                    BookingTemplatesTable
                        .selectAll()
                        .where { BookingTemplatesTable.clubId eq clubId }
                if (onlyActive) {
                    query.andWhere { BookingTemplatesTable.isActive eq true }
                }
                query
                    .orderBy(BookingTemplatesTable.createdAt, SortOrder.DESC)
                    .map { it.toBookingTemplate() }
            }
        }
    }

    override suspend fun applyTemplateSignature(id: Long): BookingTemplateResult<BookingTemplateSignature> {
        return withTxRetry {
            transaction(db) {
                val row =
                    BookingTemplatesTable
                        .selectAll()
                        .where { BookingTemplatesTable.id eq id }
                        .limit(1)
                        .firstOrNull()
                        ?: return@transaction BookingTemplateResult.Failure(BookingTemplateError.NotFound)
                val template = row.toBookingTemplate()
                val payload =
                    buildString {
                        append(template.promoterUserId)
                        append('|')
                        append(template.clubId)
                        append('|')
                        append(template.tableCapacityMin)
                        append('|')
                        append(template.notes ?: "")
                        append('|')
                        append(template.isActive)
                    }
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(payload.toByteArray(StandardCharsets.UTF_8))
                val signature = hexFormat.formatHex(hash)
                BookingTemplateResult.Success(BookingTemplateSignature(template.id, signature))
            }
        }
    }
}

private fun ResultRow.toPromoLink(): PromoLink {
    return PromoLink(
        id = this[PromoLinksTable.id],
        promoterUserId = this[PromoLinksTable.promoterUserId],
        clubId = this[PromoLinksTable.clubId],
        utmSource = this[PromoLinksTable.utmSource],
        utmMedium = this[PromoLinksTable.utmMedium],
        utmCampaign = this[PromoLinksTable.utmCampaign],
        utmContent = this[PromoLinksTable.utmContent],
        createdAt = this[PromoLinksTable.createdAt].toInstant(),
    )
}

private fun ResultRow.toPromoAttribution(): PromoAttribution {
    return PromoAttribution(
        id = this[PromoAttributionTable.id],
        bookingId = this[PromoAttributionTable.bookingId],
        promoLinkId = this[PromoAttributionTable.promoLinkId],
        promoterUserId = this[PromoAttributionTable.promoterUserId],
        utmSource = this[PromoAttributionTable.utmSource],
        utmMedium = this[PromoAttributionTable.utmMedium],
        utmCampaign = this[PromoAttributionTable.utmCampaign],
        utmContent = this[PromoAttributionTable.utmContent],
        createdAt = this[PromoAttributionTable.createdAt].toInstant(),
    )
}

private fun ResultRow.toBookingTemplate(): BookingTemplate {
    return BookingTemplate(
        id = this[BookingTemplatesTable.id],
        promoterUserId = this[BookingTemplatesTable.promoterUserId],
        clubId = this[BookingTemplatesTable.clubId],
        tableCapacityMin = this[BookingTemplatesTable.tableCapacityMin],
        notes = this[BookingTemplatesTable.notes],
        isActive = this[BookingTemplatesTable.isActive],
        createdAt = this[BookingTemplatesTable.createdAt].toInstant(),
    )
}
