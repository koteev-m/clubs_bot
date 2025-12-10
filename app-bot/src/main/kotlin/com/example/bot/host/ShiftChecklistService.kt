package com.example.bot.host

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, ephemeral service for managing host shift checklists.
 *
 * State is keyed by shift ([clubId], [eventId]) and item id, and is not persisted across restarts.
 * Template changes are tolerant: new tasks appear with `done=false`/`updatedAt=null`/`actorId=null`,
 * and removed tasks disappear from responses while their state remains in memory and will be reused
 * if a task with the same id returns to the template.
 */
class ShiftChecklistService(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val template: List<ChecklistTemplateItem> =
        listOf(
            ChecklistTemplateItem(
                id = "doors_open",
                section = "entrance",
                text = "Открыть вход и проверить очередь",
            ),
            ChecklistTemplateItem(
                id = "qr_scanners_ready",
                section = "entrance",
                text = "Проверить работу QR-сканеров",
            ),
            ChecklistTemplateItem(
                id = "staff_briefing",
                section = "floor",
                text = "Провести брифинг персонала",
            ),
            ChecklistTemplateItem(
                id = "soundcheck_host",
                section = "sound",
                text = "Проверить звук и микрофоны",
            ),
            ChecklistTemplateItem(
                id = "reserve_tables_ready",
                section = "misc",
                text = "Проверить столы под резервы",
            ),
        )

    private val state: MutableMap<ShiftKey, MutableMap<String, ChecklistItemState>> =
        ConcurrentHashMap()

    /**
     * Returns the checklist for the given event with state merged against the current template,
     * sorted by section and id for stable ordering.
     */
    fun getChecklist(clubId: Long, eventId: Long): List<ShiftChecklistItem> {
        val key = ShiftKey(clubId, eventId)
        val currentState = state[key].orEmpty()

        return template
            .map { item ->
                val itemState = currentState[item.id]
                ShiftChecklistItem(
                    id = item.id,
                    section = item.section,
                    text = item.text,
                    done = itemState?.done ?: false,
                    updatedAt = itemState?.updatedAt,
                    actorId = itemState?.actorId,
                )
            }
            .sortedWith(compareBy<ShiftChecklistItem> { it.section }.thenBy { it.id })
    }

    /**
     * Updates the completion flag for a checklist item, recording [updatedAt] and [actorId], and
     * returns the latest checklist snapshot via [getChecklist].
     *
     * @throws IllegalArgumentException when [itemId] is not present in the template
     */
    fun updateItemDone(clubId: Long, eventId: Long, itemId: String, done: Boolean, actorId: Long): List<ShiftChecklistItem> {
        if (template.none { it.id == itemId }) {
            throw IllegalArgumentException("Unknown checklist item: $itemId")
        }

        val now = Instant.now(clock)
        val key = ShiftKey(clubId, eventId)
        val stateForShift = state.computeIfAbsent(key) { ConcurrentHashMap() }
        stateForShift[itemId] =
            ChecklistItemState(
                done = done,
                updatedAt = now,
                actorId = actorId,
            )

        return getChecklist(clubId, eventId)
    }
}

/** Domain model for host shift checklist item (not serialized directly). */
data class ShiftChecklistItem(
    val id: String,
    val section: String,
    val text: String,
    val done: Boolean,
    val updatedAt: Instant?,
    val actorId: Long?,
)

/**
 * Serializable view for checklist item used in API responses.
 *
 * [updatedAt] is an ISO string in UTC or null; [actorId] is the Telegram id of the last actor or
 * null.
 */
@kotlinx.serialization.Serializable
data class ShiftChecklistItemView(
    val id: String,
    val section: String,
    val text: String,
    val done: Boolean,
    val updatedAt: String?,
    val actorId: Long?,
)

private data class ChecklistTemplateItem(
    val id: String,
    val section: String,
    val text: String,
)

private data class ChecklistItemState(
    val done: Boolean,
    val updatedAt: Instant,
    val actorId: Long,
)

private data class ShiftKey(val clubId: Long, val eventId: Long)
