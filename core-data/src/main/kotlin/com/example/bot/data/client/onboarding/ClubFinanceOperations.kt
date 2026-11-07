package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Canonical legacy template tables, adapted to the client's single authority/command transaction. */
internal class ClubFinanceOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val clientId = authorize(clubId, write)
        return view(clubId, clientId)
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = authorize(clubId, write = true)
        val version = version(clubId)
        if (body.id("expectedVersion") != version) fail("version_conflict")
        val entity = FinanceEntity.entries.singleOrNull { it.key == body.string("entity") } ?: fail("validation_error")
        val action = body.string("action") ?: fail("validation_error")
        val before = view(clubId, clientId)
        when (action) {
            "create", "update" -> save(clubId, entity, body, action == "create")
            "disable" -> disable(clubId, entity, body)
            "reorder" -> reorder(clubId, entity, body)
            else -> fail("validation_error")
        }
        if (sql.row("SELECT club_id FROM club_report_templates WHERE club_id=?", clubId) == null) {
            sql.update(
                "INSERT INTO club_report_templates(club_id,created_at,updated_at,version) VALUES (?,?,?,?)",
                clubId,
                session.now,
                session.now,
                version + 1,
            )
        } else {
            check(
                sql.update(
                    "UPDATE club_report_templates SET version=?,updated_at=? WHERE club_id=? AND version=?",
                    version + 1,
                    session.now,
                    clubId,
                    version,
                ) == 1,
            )
        }
        val after = view(clubId, clientId)
        sql.audit(
            checkNotNull(session.userId),
            "client.club.finance.$action",
            clientId,
            clubId,
            version + 1,
            session.now,
            session.correlation,
            buildJsonObject {
                put("entity", entity.key)
                put("before", before)
                put("after", after)
            },
        )
        return MutationResult("club_finance", clubId, clientId, after)
    }

    private fun authorize(
        clubId: Long,
        write: Boolean,
    ): Long {
        val clientId = session.requireClubGrant(clubId, READ_FINANCE)
        if (write && !session.hasClubGrant(clientId, clubId, WRITE_FINANCE)) fail("not_found")
        // Configuring a private club does not authorize guest activation or changes to a published club.
        val club = sql.row("SELECT is_active FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        if (club.active()) fail("not_found")
        return clientId
    }

    private fun version(clubId: Long): Long =
        sql.row("SELECT version FROM club_report_templates WHERE club_id=?", clubId)?.long("version") ?: 1L

    private fun view(
        clubId: Long,
        clientId: Long,
    ): JsonObject =
        buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("version", version(clubId).toString())
            put("canWrite", session.hasClubGrant(clientId, clubId, WRITE_FINANCE))
            FinanceEntity.entries.forEach { entity ->
                put(
                    entity.response,
                    JsonArray(
                        rows(clubId, entity).map { row ->
                            buildJsonObject {
                                put("id", row.getValue("id"))
                                put("name", row.getValue("name"))
                                put("enabled", row.active("enabled"))
                                put("orderIndex", row.long("order_index"))
                                if (entity == FinanceEntity.ARTICLE) {
                                    put("groupId", row.getValue("group_id"))
                                    put("includeInTotal", row.active("include_in_total"))
                                    put("showSeparately", row.active("show_separately"))
                                }
                            }
                        },
                    ),
                )
            }
        }

    private fun rows(
        clubId: Long,
        entity: FinanceEntity,
    ): List<DbRow> = sql.rows("SELECT * FROM ${entity.table} WHERE club_id=? ORDER BY order_index,id", clubId)

    private fun save(
        clubId: Long,
        entity: FinanceEntity,
        body: JsonObject,
        create: Boolean,
    ) {
        val article = entity == FinanceEntity.ARTICLE
        body.only(
            COMMAND_FIELDS + listOf("name", "orderIndex") + (if (create) emptyList() else listOf("id")) +
                if (article) listOf("groupId", "includeInTotal", "showSeparately") else emptyList(),
        )
        val name = body.text("name", NAME_LIMIT, required = true)
        val order =
            (body["orderIndex"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.intOrNull
                ?.takeIf { it in 0..MAX_ORDER } ?: fail("validation_error")
        val id = if (create) null else body.id("id").also { requireItem(clubId, entity, it) }
        val fields = mutableListOf("name", "order_index", "updated_at")
        val values = mutableListOf<Any?>(name, order, session.now)
        if (article) {
            val group = requireItem(clubId, FinanceEntity.GROUP, body.id("groupId"))
            if (!group.active("enabled")) fail("validation_error")
            fields += listOf("group_id", "include_in_total", "show_separately")
            values.addAll(listOf(group.long("id"), body.flag("includeInTotal"), body.flag("showSeparately")))
        }
        if (create) {
            if (rows(clubId, entity).size >= MAX_ITEMS) fail("validation_error")
            fields += listOf("club_id", "created_at", "enabled")
            values.addAll(listOf(clubId, session.now, true))
            sql.insertWithArguments(
                "INSERT INTO ${entity.table} (${fields.joinToString(",")}) " +
                    "VALUES (${fields.joinToString(",") { "?" }})",
                values,
            )
        } else {
            check(
                sql.updateWithArguments(
                    "UPDATE ${entity.table} SET ${fields.joinToString(",") { "$it=?" }} " +
                        "WHERE club_id=? AND id=?",
                    values + listOf(clubId, id),
                ) == 1,
            )
        }
    }

    private fun disable(
        clubId: Long,
        entity: FinanceEntity,
        body: JsonObject,
    ) {
        body.only(COMMAND_FIELDS + "id")
        val id = body.id("id")
        requireItem(clubId, entity, id)
        if (entity == FinanceEntity.GROUP &&
            sql
                .rows(
                    "SELECT id FROM club_revenue_articles WHERE club_id=? AND group_id=? AND enabled=TRUE",
                    clubId,
                    id,
                ).isNotEmpty()
        ) {
            fail("finance_group_in_use")
        }
        check(
            sql.update(
                "UPDATE ${entity.table} SET enabled=FALSE,updated_at=? WHERE club_id=? AND id=?",
                session.now,
                clubId,
                id,
            ) == 1,
        )
    }

    private fun reorder(
        clubId: Long,
        entity: FinanceEntity,
        body: JsonObject,
    ) {
        body.only(COMMAND_FIELDS + "ids")
        val ids =
            (body["ids"] as? JsonArray)?.map { value ->
                JsonObject(mapOf("id" to value)).id("id")
            } ?: fail("validation_error")
        if (ids.isEmpty() ||
            ids.size > MAX_ITEMS ||
            ids.distinct().size != ids.size
        ) {
            fail("validation_error")
        }
        if (ids.toSet() != rows(clubId, entity).map { it.long("id") }.toSet()) fail("validation_error")
        ids.forEachIndexed { index, id ->
            check(
                sql.update(
                    "UPDATE ${entity.table} SET order_index=?,updated_at=? WHERE club_id=? AND id=?",
                    index,
                    session.now,
                    clubId,
                    id,
                ) == 1,
            )
        }
    }

    private fun requireItem(
        clubId: Long,
        entity: FinanceEntity,
        id: Long,
    ): DbRow = sql.row("SELECT * FROM ${entity.table} WHERE club_id=? AND id=?", clubId, id) ?: fail("not_found")
}

private enum class FinanceEntity(
    val key: String,
    val table: String,
    val response: String,
) {
    BRACELET("bracelet", "club_bracelet_types", "bracelets"),
    GROUP("group", "club_revenue_groups", "revenueGroups"),
    ARTICLE("article", "club_revenue_articles", "revenueArticles"),
}

private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fail("validation_error")

private const val MAX_ITEMS = 200
private const val NAME_LIMIT = 120
private const val MAX_ORDER = 10000
private val COMMAND_FIELDS = listOf("expectedVersion", "entity", "action")
