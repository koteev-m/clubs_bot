package com.example.bot.data.client

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

/** One READ COMMITTED statement snapshot; private/unpublished clubs remain eligible resources. */
class JdbcCanonicalClientAudienceRepository(
    private val dataSource: DataSource,
) : CanonicalClientAudienceRepository {
    override fun read(audience: ClientAudience.Client): CanonicalClientAudienceSnapshot? {
        val clubs = audience.allowedClubIds.sorted()
        if (clubs.isEmpty()) return null
        val sql =
            """
            SELECT c.id, c.lifecycle_version, m.club_id, m.created_at
            FROM clients c
            JOIN client_clubs m ON m.client_id = c.id
            JOIN clubs k ON k.id = m.club_id
            WHERE c.id = ? AND c.is_active = TRUE AND c.archived_at IS NULL
              AND m.club_id IN (${clubs.joinToString { "?" }})
            ORDER BY m.club_id
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            // use preserves the original cancellation/failure even when rollback also fails.
            AutoCloseable { connection.rollback() }.use {
                query(connection, sql, audience, clubs)
            }
        }
    }

    private fun query(
        connection: Connection,
        sql: String,
        audience: ClientAudience.Client,
        clubs: List<Long>,
    ): CanonicalClientAudienceSnapshot? =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, audience.clientId)
            clubs.forEachIndexed { index, id -> statement.setLong(index + 2, id) }
            statement.executeQuery().use { rows -> snapshot(rows, audience) }
        }

    private fun snapshot(
        rows: ResultSet,
        audience: ClientAudience.Client,
    ): CanonicalClientAudienceSnapshot? {
        val mappings = mutableMapOf<Long, Instant>()
        var lifecycle: Long? = null
        while (rows.next()) {
            val current = rows.getLong("lifecycle_version")
            val club = rows.getLong("club_id")
            val created = rows.getObject("created_at", OffsetDateTime::class.java)?.toInstant()
            val invalid = current <= 0 || (lifecycle != null && lifecycle != current)
            require(!invalid && rows.getLong("id") == audience.clientId && created != null)
            require(mappings.put(club, created) == null)
            lifecycle = current
        }
        return if (mappings.keys == audience.allowedClubIds) {
            CanonicalClientAudienceSnapshot(audience.clientId, checkNotNull(lifecycle), mappings)
        } else {
            null
        }
    }
}
