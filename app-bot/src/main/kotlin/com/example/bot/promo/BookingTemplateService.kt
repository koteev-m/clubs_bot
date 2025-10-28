package com.example.bot.promo

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.booking.HoldRequest
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.min

private val DEFAULT_HOLD_TTL: Duration = Duration.ofMinutes(15)

/** Actor resolved from Telegram interaction with assigned roles and club scope. */
data class TemplateActor(
    val userId: Long,
    val telegramUserId: Long,
    val roles: Set<Role>,
    val clubIds: Set<Long>,
)

/** Request for creating a booking template. */
data class TemplateCreateRequest(
    val promoterUserId: Long,
    val clubId: Long,
    val tableCapacityMin: Int,
    val notes: String?,
)

/** Request for updating existing template. */
data class TemplateUpdateRequest(
    val id: Long,
    val tableCapacityMin: Int,
    val notes: String?,
    val isActive: Boolean,
)

/** Parameters required to apply template when creating booking. */
data class TemplateBookingRequest(
    val clubId: Long,
    val tableId: Long,
    val slotStart: Instant,
    val slotEnd: Instant,
    val guestsOverride: Int? = null,
    val holdTtl: Duration = DEFAULT_HOLD_TTL,
)

class TemplateAccessException(message: String) : RuntimeException(message)

class TemplateNotFoundException(message: String) : RuntimeException(message)

/**
 * Service that enforces RBAC rules for booking templates and orchestrates booking flow.
 */
class BookingTemplateService(
    private val repository: BookingTemplateRepository,
    private val bookingService: BookingService,
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val notificationsOutbox: NotificationsOutboxRepository,
) {
    private val logger = LoggerFactory.getLogger(BookingTemplateService::class.java)

    suspend fun resolveActor(telegramUserId: Long): TemplateActor? {
        val user = userRepository.getByTelegramId(telegramUserId) ?: return null
        val roles = userRoleRepository.listRoles(user.id)
        val clubIds = userRoleRepository.listClubIdsFor(user.id)
        return TemplateActor(
            userId = user.id,
            telegramUserId = user.telegramId,
            roles = roles,
            clubIds = clubIds,
        )
    }

    suspend fun listMine(
        promoterId: Long,
        page: Int,
        size: Int,
    ): List<BookingTemplate> {
        require(page > 0) { "page must be positive" }
        require(size > 0) { "size must be positive" }
        val templates = repository.listByOwner(promoterId)
        if (templates.isEmpty()) return emptyList()
        val fromIndex = min((page - 1) * size, templates.size)
        val toIndex = min(fromIndex + size, templates.size)
        return if (fromIndex >= toIndex) emptyList() else templates.subList(fromIndex, toIndex)
    }

    suspend fun create(
        promoterId: Long,
        clubId: Long,
        tableCapacityMin: Int,
        notes: String?,
    ): BookingTemplate {
        return repository.create(promoterId, clubId, tableCapacityMin, notes)
    }

    suspend fun toggleActive(
        id: Long,
        active: Boolean,
    ) {
        val template = repository.get(id) ?: throw TemplateNotFoundException("template $id not found")
        val result = repository.update(id, template.tableCapacityMin, template.notes, active)
        if (result !is BookingTemplateResult.Success<*>) {
            throw TemplateNotFoundException("template $id not found")
        }
    }

    suspend fun delete(id: Long) {
        when (repository.deactivate(id)) {
            is BookingTemplateResult.Success<*> -> Unit
            is BookingTemplateResult.Failure -> throw TemplateNotFoundException("template $id not found")
        }
    }

    suspend fun createTemplate(
        actor: TemplateActor,
        request: TemplateCreateRequest,
    ): BookingTemplate {
        ensureCanCreate(actor, request.promoterUserId, request.clubId)
        return repository.create(
            promoterUserId = request.promoterUserId,
            clubId = request.clubId,
            tableCapacityMin = request.tableCapacityMin,
            notes = request.notes,
        )
    }

    suspend fun listTemplates(
        actor: TemplateActor,
        clubId: Long? = null,
        onlyActive: Boolean = true,
    ): List<BookingTemplate> {
        return when {
            Role.PROMOTER in actor.roles ->
                repository
                    .listByOwner(actor.userId)
                    .filter { clubId == null || it.clubId == clubId }

            else -> {
                val clubs = determineClubScope(actor, clubId)
                val seen = LinkedHashMap<Long, BookingTemplate>()
                for (id in clubs) {
                    val templates = repository.listByClub(id, onlyActive)
                    for (template in templates) {
                        if (actor.canAccess(template)) {
                            seen.putIfAbsent(template.id, template)
                        }
                    }
                }
                seen.values.toList()
            }
        }
    }

    suspend fun updateTemplate(
        actor: TemplateActor,
        request: TemplateUpdateRequest,
    ): BookingTemplate {
        val template =
            repository.get(request.id) ?: throw TemplateNotFoundException("template ${request.id} not found")
        ensureCanAccess(actor, template)
        return when (
            val result =
                repository.update(
                    request.id,
                    request.tableCapacityMin,
                    request.notes,
                    request.isActive,
                )
        ) {
            is BookingTemplateResult.Success<*> -> result.value as BookingTemplate
            is BookingTemplateResult.Failure -> throw TemplateNotFoundException("template ${request.id} not found")
        }
    }

    suspend fun deactivateTemplate(
        actor: TemplateActor,
        id: Long,
    ) {
        val template = repository.get(id) ?: throw TemplateNotFoundException("template $id not found")
        ensureCanAccess(actor, template)
        when (repository.deactivate(id)) {
            is BookingTemplateResult.Success<*> -> Unit
            is BookingTemplateResult.Failure -> throw TemplateNotFoundException("template $id not found")
        }
    }

    suspend fun applyTemplate(
        actor: TemplateActor,
        templateId: Long,
        request: TemplateBookingRequest,
    ): BookingCmdResult {
        val template = repository.get(templateId) ?: throw TemplateNotFoundException("template $templateId not found")
        ensureCanAccess(actor, template)
        if (!template.isActive) {
            throw TemplateAccessException("template $templateId is inactive")
        }
        if (request.clubId != template.clubId) {
            throw TemplateAccessException("template $templateId cannot be applied to club ${request.clubId}")
        }

        val idempotency =
            templateIdempotencyKey(
                template.id,
                template.promoterUserId,
                request.slotStart,
                request.tableId,
            )

        MDC.put(MDC_IDEMPOTENCY_KEY, idempotency)
        MDC.put(MDC_TEMPLATE_ID, templateId.toString())

        return try {
            val guests = request.guestsOverride ?: template.tableCapacityMin
            val holdResult =
                bookingService.hold(
                    HoldRequest(
                        clubId = template.clubId,
                        tableId = request.tableId,
                        slotStart = request.slotStart,
                        slotEnd = request.slotEnd,
                        guestsCount = guests,
                        ttl = request.holdTtl,
                    ),
                    "$idempotency:hold",
                )
            if (holdResult !is BookingCmdResult.HoldCreated) {
                logger.debug("applyTemplate hold result {}", holdResult)
                return holdResult
            }
            when (val confirmed = bookingService.confirm(holdResult.holdId, "$idempotency:confirm")) {
                is BookingCmdResult.Booked ->
                    finalizeAndNotify(
                        confirmed.bookingId,
                        actor,
                        template,
                        request,
                        idempotency,
                    )

                else -> confirmed
            }
        } finally {
            MDC.remove(MDC_IDEMPOTENCY_KEY)
            MDC.remove(MDC_TEMPLATE_ID)
        }
    }

    private suspend fun finalizeAndNotify(
        bookingId: UUID,
        actor: TemplateActor,
        template: BookingTemplate,
        request: TemplateBookingRequest,
        idempotency: String,
    ): BookingCmdResult {
        val finalized = bookingService.finalize(bookingId, actor.telegramUserId)
        if (finalized is BookingCmdResult.Booked) {
            val payload =
                buildJsonObject {
                    put("bookingId", bookingId.toString())
                    put("templateId", template.id)
                    put("clubId", template.clubId)
                    put("tableId", request.tableId)
                    put("slotStart", request.slotStart.toString())
                    put("slotEnd", request.slotEnd.toString())
                }
            notificationsOutbox.enqueue(
                kind = PROMO_TEMPLATE_BOOKED_TOPIC,
                payload = payload,
                recipientType = "PROMOTER",
                recipientId = template.promoterUserId,
                clubId = template.clubId,
                targetChatId = 0,
                method = "EVENT",
                dedupKey = idempotency,
            )
            logger.info("promo template {} booked booking {}", template.id, bookingId)
        }
        return finalized
    }

    private fun ensureCanCreate(
        actor: TemplateActor,
        promoterId: Long,
        clubId: Long,
    ) {
        val allowed =
            when {
                actor.hasRole(Role.OWNER, Role.HEAD_MANAGER) -> true
                actor.hasRole(Role.CLUB_ADMIN, Role.MANAGER) -> clubId in actor.clubIds
                actor.hasRole(Role.PROMOTER) -> promoterId == actor.userId && clubId in actor.clubIds
                else -> false
            }
        if (!allowed) {
            throw TemplateAccessException("actor ${actor.userId} cannot create template for club $clubId")
        }
    }

    private fun ensureCanAccess(
        actor: TemplateActor,
        template: BookingTemplate,
    ) {
        if (!actor.canAccess(template)) {
            throw TemplateAccessException("actor ${actor.userId} cannot access template ${template.id}")
        }
    }

    private fun TemplateActor.canAccess(template: BookingTemplate): Boolean {
        return when {
            hasRole(Role.OWNER, Role.HEAD_MANAGER) -> true
            hasRole(Role.CLUB_ADMIN, Role.MANAGER) -> template.clubId in clubIds
            hasRole(Role.PROMOTER) -> template.promoterUserId == userId
            else -> false
        }
    }

    private fun determineClubScope(
        actor: TemplateActor,
        requestedClub: Long?,
    ): Set<Long> {
        val privileged = actor.hasRole(Role.OWNER, Role.HEAD_MANAGER)
        val scopedRoles = actor.hasRole(Role.CLUB_ADMIN, Role.MANAGER)
        return when {
            privileged ->
                when {
                    requestedClub != null -> setOf(requestedClub)
                    actor.clubIds.isNotEmpty() -> actor.clubIds
                    else -> emptySet()
                }

            scopedRoles -> {
                val clubs = if (requestedClub != null) setOf(requestedClub) else actor.clubIds
                if (clubs.isEmpty() || (requestedClub != null && requestedClub !in actor.clubIds)) {
                    throw TemplateAccessException("club scope missing for actor ${actor.userId}")
                }
                clubs
            }

            else -> emptySet()
        }
    }

    private fun TemplateActor.hasRole(vararg target: Role): Boolean = roles.any { it in target }

    companion object {
        private const val PROMO_TEMPLATE_BOOKED_TOPIC = "promo.template.booked"
        private const val MDC_IDEMPOTENCY_KEY = "Idempotency-Key"
        private const val MDC_TEMPLATE_ID = "templateId"
    }
}

internal fun templateIdempotencyKey(
    templateId: Long,
    promoterId: Long,
    slotStart: Instant,
    tableId: Long,
): String = "tpl:$templateId:$promoterId:$slotStart:$tableId"
