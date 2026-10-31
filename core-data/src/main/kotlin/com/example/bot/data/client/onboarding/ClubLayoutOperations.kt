package com.example.bot.data.client.onboarding

import com.example.bot.data.layout.LayoutDbRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Every read and command shares the client lock with revocation, then checks exact current grants. */
internal class ClubLayoutOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val clientId = authorize(clubId, write)
        val writable = hasGrant(clientId, clubId, WRITE_LAYOUT)
        return buildJsonObject {
            put("id", clubId.toString())
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put(
                "permissions",
                JsonArray(
                    (listOf(READ_CLUB) + if (writable) listOf(WRITE_LAYOUT) else emptyList()).map(::JsonPrimitive),
                ),
            )
            put(
                "halls",
                JsonArray(
                    sql.rows("SELECT * FROM halls WHERE club_id=? ORDER BY id", clubId).map(ClubLayoutViews(sql)::hall),
                ),
            )
        }
    }

    fun plan(
        clubId: Long,
        body: JsonObject,
    ): JsonObject {
        body.only(listOf("hallId"))
        authorize(clubId, write = false)
        val hallId = body.id("hallId")
        hall(clubId, hallId)
        return sql.connection
            .prepareStatement(
                "SELECT bytes,content_type,sha256,size_bytes FROM hall_plans WHERE hall_id=?",
            ).use { statement ->
                statement.setLong(1, hallId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) fail("not_found")
                    buildJsonObject {
                        put("bytesBase64", Base64.getEncoder().encodeToString(rows.getBytes("bytes")))
                        put("contentType", rows.getString("content_type"))
                        put("sha256", rows.getString("sha256"))
                        put("sizeBytes", rows.getLong("size_bytes"))
                    }
                }
            }
    }

    fun mutate(
        clubId: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): MutationResult {
        val clientId = authorize(clubId, write = true)
        var tableId: Long? = null
        val hallId =
            if (operation == OnboardingMutation.CLUB_HALL_CREATE) {
                createHall(clubId, body)
            } else {
                val hallId = body.id("hallId")
                val hall = hall(clubId, hallId)
                if (body.id("expectedVersion") != hall.long("layout_revision")) fail("version_conflict")
                when (operation) {
                    OnboardingMutation.CLUB_HALL_UPDATE -> updateHall(clubId, hallId, body)
                    OnboardingMutation.CLUB_TABLE_CREATE -> tableId = ClubLayoutTables(session).create(hallId, body)
                    OnboardingMutation.CLUB_TABLE_UPDATE -> tableId = ClubLayoutTables(session).update(hallId, body)
                    OnboardingMutation.CLUB_PLAN_UPLOAD -> ClubLayoutPlans(session).upload(hallId, body)
                    else -> fail("validation_error")
                }
                sql.update(
                    "UPDATE halls SET layout_revision=layout_revision+1,updated_at=? WHERE id=?",
                    session.now,
                    hallId,
                )
                hallId
            }
        val version = hall(clubId, hallId).long("layout_revision")
        val action =
            when (operation) {
                OnboardingMutation.CLUB_HALL_CREATE -> "client.club.layout.hall.create"
                OnboardingMutation.CLUB_HALL_UPDATE -> "client.club.layout.hall.update"
                OnboardingMutation.CLUB_TABLE_CREATE -> "client.club.layout.table.create"
                OnboardingMutation.CLUB_TABLE_UPDATE -> "client.club.layout.table.update"
                else -> "client.club.layout.plan.upload"
            }
        sql.audit(
            checkNotNull(session.userId),
            action,
            clientId,
            clubId,
            version,
            session.now,
            session.correlation,
            buildJsonObject {
                put("hallId", hallId.toString())
                tableId?.let { put("tableId", it.toString()) }
            },
        )
        return MutationResult("club_layout", clubId, clientId, get(clubId, write = true))
    }

    private fun authorize(
        clubId: Long,
        write: Boolean,
    ): Long {
        val mapping = sql.row("SELECT client_id FROM client_clubs WHERE club_id=?", clubId) ?: fail("not_found")
        val clientId = mapping.long("client_id")
        session.client(clientId, lock = true)
        session.member(clientId, manage = false)
        // Re-read the mapping and all authority after acquiring the shared lifecycle lock.
        if (!hasGrant(clientId, clubId, READ_CLUB) ||
            (write && !hasGrant(clientId, clubId, WRITE_LAYOUT))
        ) {
            fail("not_found")
        }
        return clientId
    }

    private fun hasGrant(
        clientId: Long,
        clubId: Long,
        permission: String,
    ): Boolean =
        sql.row(
            "SELECT g.club_id FROM client_club_grants g JOIN client_clubs m ON m.client_id=g.client_id " +
                "AND m.club_id=g.club_id WHERE g.client_id=? AND g.club_id=? AND g.user_id=? " +
                "AND g.permission_code=? AND g.is_active=TRUE",
            clientId,
            clubId,
            session.userId,
            permission,
        ) != null

    private fun hall(
        clubId: Long,
        hallId: Long,
    ): DbRow = sql.row("SELECT * FROM halls WHERE id=? AND club_id=?", hallId, clubId) ?: fail("not_found")

    private fun createHall(
        clubId: Long,
        body: JsonObject,
    ): Long {
        body.only(listOf("name"))
        val name = body.text("name", HALL_NAME_LIMIT, required = true)
        requireHallName(clubId, name)
        val active = sql.rows("SELECT id FROM halls WHERE club_id=? AND is_active=TRUE", clubId).isEmpty()
        val hallId =
            sql.insert(
                "INSERT INTO halls(club_id,name,is_active,layout_revision,geometry_json," +
                    "geometry_fingerprint,created_at,updated_at) VALUES (?,?,?,1,?,?,?,?)",
                clubId,
                name,
                active,
                BASE_GEOMETRY,
                LayoutDbRepository.fingerprintFor(BASE_GEOMETRY),
                session.now,
                session.now,
            )
        sql.update(
            "INSERT INTO hall_zones(hall_id,zone_id,name,sort_order,created_at,updated_at) " +
                "VALUES (?,'main','Основная зона',1,?,?)",
            hallId,
            session.now,
            session.now,
        )
        return hallId
    }

    private fun updateHall(
        clubId: Long,
        hallId: Long,
        body: JsonObject,
    ) {
        body.only(listOf("hallId", "expectedVersion", "name"))
        val name = body.text("name", HALL_NAME_LIMIT, required = true)
        requireHallName(clubId, name, hallId)
        sql.update("UPDATE halls SET name=? WHERE id=? AND club_id=?", name, hallId, clubId)
    }

    private fun requireHallName(
        clubId: Long,
        name: String,
        excluded: Long = 0,
    ) {
        if (sql.row(
                "SELECT id FROM halls WHERE club_id=? AND LOWER(name)=LOWER(?) AND id<>?",
                clubId,
                name,
                excluded,
            ) !=
            null
        ) {
            fail("hall_name_conflict")
        }
    }

    private companion object {
        const val HALL_NAME_LIMIT = 255
        const val BASE_GEOMETRY = "{\"zones\":[{\"id\":\"main\",\"name\":\"Основная зона\"}]}"
    }
}
