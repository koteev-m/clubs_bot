package com.example.bot.data.client

import com.example.bot.data.security.PermissionCodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/** No legacy RBAC queries, provisioning, or cross-request authority cache. */
class JdbcClientContextRepository(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClientContextRepository {
    override suspend fun context(
        telegramUserId: Long,
        audience: ClientAudience,
    ): List<ClientContext> =
        select(telegramUserId, audience, null)
            .groupBy { it.clientId }
            .map { (clientId, rows) ->
                ClientContext(clientId, rows.first().clientName, rows.mapNotNull { it.club })
            }

    override suspend fun club(
        telegramUserId: Long,
        audience: ClientAudience,
        clubId: Long,
    ): ClientClubDetail? {
        require(clubId > 0) { "Invalid club ID" }
        val row = select(telegramUserId, audience, clubId).singleOrNull() ?: return null
        val club = checkNotNull(row.club)
        return ClientClubDetail(row.clientId, club.id, club.name)
    }

    private suspend fun select(
        telegramUserId: Long,
        audience: ClientAudience,
        clubId: Long?,
    ): List<ContextRow> =
        withContext(dispatcher) {
            require(telegramUserId > 0) { "Invalid Telegram user ID" }
            val restriction = audience as? ClientAudience.Client
            val allowedClubs = restriction?.allowedClubIds?.sorted()
            // Only placeholder count changes; all identities and restrictions are JDBC values.
            val clubRestriction =
                when {
                    allowedClubs == null -> ""
                    allowedClubs.isEmpty() -> " AND FALSE"
                    else -> " AND k.id IN (${allowedClubs.joinToString { "?" }})"
                }
            val clientRestriction = if (restriction == null) "" else " AND c.id = ?"
            val detailRestriction = if (clubId == null) "" else " AND authorized.club_id = ?"
            val sql =
                """
                SELECT c.id AS client_id, c.display_name AS client_name,
                       authorized.club_id, authorized.club_name
                FROM users u
                JOIN client_memberships m ON m.user_id = u.id
                JOIN clients c ON c.id = m.client_id
                LEFT JOIN (
                    SELECT g.client_id, g.user_id, k.id AS club_id, k.name AS club_name
                    FROM client_club_grants g
                    JOIN client_clubs mapping ON mapping.client_id = g.client_id AND mapping.club_id = g.club_id
                    JOIN clubs k ON k.id = mapping.club_id
                    WHERE g.is_active = TRUE AND g.permission_code = ?$clubRestriction
                ) authorized ON authorized.client_id = c.id AND authorized.user_id = u.id
                WHERE u.telegram_user_id = ? AND c.is_active = TRUE AND m.is_active = TRUE
                $clientRestriction$detailRestriction
                ORDER BY c.id, authorized.club_id
                """.trimIndent()
            dataSource.connection.use { connection ->
                connection.isReadOnly = true
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                connection.autoCommit = false
                try {
                    connection.prepareStatement(sql).use { statement ->
                        var index = 1
                        statement.setString(index++, PermissionCodes.CLIENT_CLUB_READ.value)
                        allowedClubs?.forEach { statement.setLong(index++, it) }
                        statement.setLong(index++, telegramUserId)
                        restriction?.let { statement.setLong(index++, it.clientId) }
                        clubId?.let { statement.setLong(index, it) }
                        statement.executeQuery().use { result ->
                            buildList {
                                while (result.next()) {
                                    val id = result.getLong("club_id")
                                    val club =
                                        if (result.wasNull()) {
                                            null
                                        } else {
                                            ClientClub(
                                                id,
                                                result.getString("club_name"),
                                            )
                                        }
                                    add(ContextRow(result.getLong("client_id"), result.getString("client_name"), club))
                                }
                            }
                        }
                    }
                } finally {
                    connection.rollback()
                }
            }
        }

    private data class ContextRow(
        val clientId: Long,
        val clientName: String,
        val club: ClientClub?,
    )
}
