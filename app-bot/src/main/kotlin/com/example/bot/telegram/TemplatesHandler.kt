package com.example.bot.telegram

import com.example.bot.promo.BookingTemplate
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.TemplateActor
import com.example.bot.promo.TemplateBookingRequest
import com.example.bot.telegram.ott.CallbackTokenService
import com.example.bot.telegram.ott.TemplateOttPayload
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import java.time.Instant

private const val ACTION_LIST = "tpl:list"
private const val ACTION_CREATE = "tpl:new"
private const val ACTION_BOOK = "tpl:book"

/**
 * Handler responsible for booking template bot interactions.
 */
class BookingTemplateBotHandler(
    private val service: BookingTemplateService,
    private val tokenService: CallbackTokenService,
) {
    /** Builds a menu keyboard with template actions. */
    fun buildMenu(): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            arrayOf(InlineKeyboardButton("Мои шаблоны").callbackData(ACTION_LIST)),
            arrayOf(InlineKeyboardButton("Создать шаблон").callbackData(ACTION_CREATE)),
            arrayOf(InlineKeyboardButton("Забронировать по шаблону").callbackData(ACTION_BOOK)),
        )
    }

    /**
     * Issues OTT tokens for templates and produces keyboard for selection.
     */
    fun buildTemplateSelection(templates: List<BookingTemplate>): InlineKeyboardMarkup {
        if (templates.isEmpty()) {
            return InlineKeyboardMarkup(arrayOf(InlineKeyboardButton("Нет шаблонов").callbackData("tpl:none")))
        }
        val rows =
            templates.map { template ->
                val payload = TemplateOttPayload.Selection(template.id)
                val token = tokenService.issueToken(payload)
                arrayOf(
                    InlineKeyboardButton(
                        "Шаблон #${template.id} · ${template.tableCapacityMin} гостей",
                    ).callbackData(token),
                )
            }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /** Decodes OTT payload stored in callback token. */
    fun decode(token: String): TemplateOttPayload? = tokenService.consume(token) as? TemplateOttPayload

    /** Issues OTT payload for booking command to stay within callback limits. */
    fun issueBookingToken(payload: TemplateBookingCommandPayload): String {
        val ottPayload =
            TemplateOttPayload.Booking(
                templateId = payload.templateId,
                clubId = payload.clubId,
                tableId = payload.tableId,
                slotStart = payload.slotStart,
                slotEnd = payload.slotEnd,
                guests = payload.guests,
            )
        return tokenService.issueToken(ottPayload)
    }

    suspend fun listTemplates(
        actor: TemplateActor,
        clubId: Long? = null,
    ): List<BookingTemplate> {
        return service.listTemplates(actor, clubId, onlyActive = false)
    }

    suspend fun applyBooking(
        actor: TemplateActor,
        payload: TemplateOttPayload.Booking,
    ) = service.applyTemplate(
        actor = actor,
        templateId = payload.templateId,
        request =
            TemplateBookingRequest(
                clubId = payload.clubId,
                tableId = payload.tableId,
                slotStart = payload.slotStart,
                slotEnd = payload.slotEnd,
                guestsOverride = payload.guests,
            ),
    )
}

/** Command button descriptors kept for convenience. */
object TemplateMenuActions {
    const val LIST = ACTION_LIST
    const val CREATE = ACTION_CREATE
    const val BOOK = ACTION_BOOK
}

/** Parameters for issuing booking OTT tokens. */
data class TemplateBookingCommandPayload(
    val templateId: Long,
    val clubId: Long,
    val tableId: Long,
    val slotStart: Instant,
    val slotEnd: Instant,
    val guests: Int? = null,
)
