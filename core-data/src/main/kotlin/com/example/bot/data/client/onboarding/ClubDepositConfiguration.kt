package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val WRITE_DEPOSIT_CONFIGURATION = "client.club.operations.deposit_categories.write"
private const val PRIMARY = "primary"
private const val MAX_CATEGORIES = 20
private const val MAX_CATEGORY_NAME = 80
private const val MAX_DEPOSIT_MINOR = 999_999_999_999L

internal class ClubDepositConfiguration(
    private val session: OnboardingSession,
    private val clubId: Long,
) {
    private val sql get() = session.sql

    private fun config() = sql.row("SELECT * FROM club_deposit_configuration WHERE club_id=?", clubId)

    private fun extras() = sql.rows("SELECT * FROM club_deposit_categories WHERE club_id=? ORDER BY id", clubId)

    fun get(includeInactive: Boolean): JsonObject {
        val client = session.requireClubGrant(clubId, WRITE_DEPOSIT_CONFIGURATION)
        return editorView(client, includeInactive)
    }

    private fun editorView(
        client: Long,
        includeInactive: Boolean,
    ): JsonObject =
        JsonObject(
            view(includeInactive) +
                mapOf("clientId" to JsonPrimitive(client.toString()), "canWrite" to JsonPrimitive(true)),
        )

    fun view(includeInactive: Boolean = false): JsonObject {
        val config = config()
        val enabled = config?.active("distribution") == true
        val categories = if (enabled || includeInactive) extras() else emptyList()
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("version", config?.get("version") ?: "1")
            put("distribution", enabled)
            put("primary", category(PRIMARY, config?.get("primary_name") ?: "Основной депозит", true))
            put(
                "extras",
                JsonArray(
                    categories.filter { includeInactive || it.active("active") }.map {
                        category(checkNotNull(it["id"]), checkNotNull(it["name"]), it.active("active"))
                    },
                ),
            )
        }
    }

    fun write(body: JsonObject): MutationResult {
        val clientId = session.requireClubGrant(clubId, WRITE_DEPOSIT_CONFIGURATION)
        body.only(listOf("expectedVersion", "primaryName", "distribution", "extras"))
        val old = config()?.long("version") ?: 1L
        if (body.id("expectedVersion") != old) fail("version_conflict")
        val primaryName = body.text("primaryName", MAX_CATEGORY_NAME, required = true)
        val enabled =
            when (body["distribution"]) {
                JsonPrimitive(true) -> true
                JsonPrimitive(false) -> false
                else -> fail("validation_error")
            }
        val rows = extras().associateBy { it["id"] }
        val edits = (body["extras"] as? JsonArray)?.takeIf { it.size <= MAX_CATEGORIES } ?: fail("validation_error")
        val categories =
            edits.map { element ->
                val edit = element as? JsonObject ?: fail("validation_error")
                edit.only(listOf("id", "name", "active"))
                val id = edit.string("id")
                if (id != null && id !in rows) fail("validation_error")
                val active =
                    when (edit["active"]) {
                        JsonPrimitive(true) -> true
                        JsonPrimitive(false) -> false
                        else -> fail("validation_error")
                    }
                CategoryEdit(id, edit.text("name", MAX_CATEGORY_NAME, required = true), active)
            }
        val ids = categories.mapNotNull { it.id }
        if (ids.size != ids.distinct().size || ids.toSet() != rows.keys || !validNames(primaryName, categories)) {
            fail("validation_error")
        }
        if (old == 1L) {
            sql.update(
                "INSERT INTO club_deposit_configuration(club_id,version,distribution,primary_name) VALUES (?,?,?,?)",
                clubId,
                old + 1,
                enabled,
                primaryName,
            )
        } else {
            sql.update(
                "UPDATE club_deposit_configuration SET version=?,distribution=?,primary_name=? WHERE club_id=?",
                old + 1,
                enabled,
                primaryName,
                clubId,
            )
        }
        categories.forEach { choice ->
            if (choice.id == null) {
                sql.update(
                    "INSERT INTO club_deposit_categories(club_id,name,active) VALUES (?,?,?)",
                    clubId,
                    choice.name,
                    choice.active,
                )
            } else {
                sql.update(
                    "UPDATE club_deposit_categories SET name=?,active=? WHERE club_id=? AND id=?",
                    choice.name,
                    choice.active,
                    clubId,
                    choice.id.toLong(),
                )
            }
        }
        session.audit("client.club.deposit_configuration.update", clientId, clubId, old + 1)
        return MutationResult("club_deposit_config", clubId, clientId, editorView(clientId, includeInactive = true))
    }

    fun payment(body: JsonObject): RecordedPayment {
        val amount =
            body.string("amountMinor")?.toLongOrNull()?.takeIf { it in 1..MAX_DEPOSIT_MINOR }
                ?: fail("validation_error")
        val method = body.string("method")?.takeIf { it in PAYMENT_METHODS } ?: fail("validation_error")
        val cash =
            if (method == "MIXED") {
                body.id("cashMinor").takeIf { it < amount } ?: fail("validation_error")
            } else {
                if ("cashMinor" in body) fail("validation_error")
                null
            }
        val config = config()
        val version = config?.long("version") ?: 1L
        if (body.id("categoryVersion") != version) fail("version_conflict")
        val distribution = body["extras"] as? JsonObject ?: fail("validation_error")
        val choices =
            if (config?.active("distribution") ==
                true
            ) {
                extras().filter { it.active("active") }
            } else {
                emptyList()
            }
        val names = choices.associate { checkNotNull(it["id"]) to checkNotNull(it["name"]) }
        var remaining = amount
        val parts =
            distribution.toSortedMap().mapNotNull { (id, value) ->
                val name = names[id] ?: fail("validation_error")
                val minor =
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.toLongOrNull()
                        ?.takeIf { it in 0..remaining } ?: fail("validation_error")
                remaining -= minor
                DepositPart("category_$id", name, minor).takeIf { minor > 0 }
            }
        return RecordedPayment(
            amount,
            method,
            cash,
            cash?.let { amount - it },
            parts + DepositPart(PRIMARY, config?.get("primary_name") ?: "Основной депозит", remaining),
        )
    }

    private fun category(
        id: String,
        name: String,
        active: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("active", active)
        }

    private fun validNames(
        primary: String,
        categories: List<CategoryEdit>,
    ): Boolean {
        val active = categories.filter { it.active }.map { it.name.lowercase() } + primary.lowercase()
        return active.size == active.distinct().size
    }

    private data class CategoryEdit(
        val id: String?,
        val name: String,
        val active: Boolean,
    )
}

internal data class RecordedPayment(
    val amount: Long,
    val method: String,
    val cashMinor: Long?,
    val cardMinor: Long?,
    val parts: List<DepositPart>,
)

internal data class DepositPart(
    val code: String,
    val name: String,
    val amount: Long,
)

private val PAYMENT_METHODS = setOf("CASH", "CARD", "MIXED")
