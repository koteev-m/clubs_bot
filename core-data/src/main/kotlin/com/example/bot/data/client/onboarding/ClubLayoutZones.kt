package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Mutations run inside the existing authorized client/club lock and hall revision transaction. */
internal class ClubLayoutZones(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun mutate(
        hallId: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): Long {
        val creating = operation == OnboardingMutation.CLUB_ZONE_CREATE
        val removing = operation == OnboardingMutation.CLUB_ZONE_REMOVE
        val identity = if (creating) emptyList() else listOf("zoneId")
        val fields = if (removing) emptyList() else listOf("name")
        body.only(listOf("hallId", "expectedVersion") + identity + fields)
        val zoneId = if (creating) 0L else body.id("zoneId")
        val existing = if (creating) null else zone(hallId, zoneId)
        if (removing) {
            remove(hallId, zoneId, checkNotNull(existing))
            return zoneId
        }
        val name = body.text("name", ZONE_NAME_LIMIT, required = true)
        if (sql.row(
                "SELECT id FROM hall_zones WHERE hall_id=? AND LOWER(name)=LOWER(?) AND id<>?",
                hallId,
                name,
                zoneId,
            ) != null
        ) {
            fail("zone_name_conflict")
        }
        return if (creating) {
            create(hallId, name)
        } else {
            sql.update(
                "UPDATE hall_zones SET name=?,updated_at=? WHERE id=? AND hall_id=?",
                name,
                session.now,
                zoneId,
                hallId,
            )
            zoneId
        }
    }

    fun key(
        hallId: Long,
        zoneId: Long,
    ): String = checkNotNull(zone(hallId, zoneId)["zone_id"])

    private fun create(
        hallId: Long,
        name: String,
    ): Long {
        val order =
            sql
                .row(
                    "SELECT COALESCE(MAX(sort_order),0) AS position FROM hall_zones WHERE hall_id=?",
                    hallId,
                )?.long("position") ?: 0
        return sql.insert(
            "INSERT INTO hall_zones(hall_id,zone_id,name,sort_order,created_at,updated_at) VALUES (?,?,?,?,?,?)",
            hallId,
            UUID.randomUUID().toString(),
            name,
            order + 1,
            session.now,
            session.now,
        )
    }

    private fun remove(
        hallId: Long,
        zoneId: Long,
        existing: DbRow,
    ) {
        // Include inactive tables: their historical zone and booking links must survive.
        if (sql.row(
                "SELECT id FROM hall_tables WHERE hall_id=? AND zone_id=? LIMIT 1",
                hallId,
                existing["zone_id"],
            ) != null
        ) {
            fail("zone_in_use")
        }
        if (sql.rows("SELECT id FROM hall_zones WHERE hall_id=?", hallId).size <= 1) fail("last_zone")
        sql.update("DELETE FROM hall_zones WHERE id=? AND hall_id=?", zoneId, hallId)
    }

    private fun zone(
        hallId: Long,
        zoneId: Long,
    ): DbRow = sql.row("SELECT * FROM hall_zones WHERE id=? AND hall_id=?", zoneId, hallId) ?: fail("not_found")

    private companion object {
        const val ZONE_NAME_LIMIT = 100
    }
}
