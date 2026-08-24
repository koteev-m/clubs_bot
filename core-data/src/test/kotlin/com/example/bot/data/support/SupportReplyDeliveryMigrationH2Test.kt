package com.example.bot.data.support

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SupportReplyDeliveryMigrationH2Test {
    @Test
    fun `V060 follows V059 and preserves existing support ticket message and audit without backfill`() {
        val jdbcUrl =
            "jdbc:h2:mem:support-delivery-upgrade-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/h2")
        val v059 =
            Flyway
                .configure()
                .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                .locations(*locations)
                .target(PREVIOUS_VERSION)
                .cleanDisabled(false)
                .load()
        v059.clean()
        v059.migrate()
        assertEquals(
            PREVIOUS_VERSION,
            v059
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        DriverManager.getConnection(jdbcUrl, H2_USER, H2_PASSWORD).use { connection ->
            val fixture = insertV059Fixture(connection)
            val before = readFixture(connection, fixture)

            val v060 =
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                    .locations(*locations)
                    .target(DELIVERY_VERSION)
                    .load()
            assertEquals(1, v060.migrate().migrationsExecuted)
            assertEquals(
                DELIVERY_VERSION,
                v060
                    .info()
                    .current()
                    ?.version
                    ?.toString(),
            )
            assertEquals(before, readFixture(connection, fixture))
            assertEquals(0L, scalarLong(connection, "SELECT COUNT(*) FROM support_reply_deliveries"))
            assertEquals(
                1L,
                scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                        "WHERE version = '$DELIVERY_VERSION' AND success = TRUE",
                ),
            )
            assertTrue(tableExists(connection, "support_reply_deliveries"))
        }
    }

    private fun insertV059Fixture(connection: Connection): MigrationFixture {
        val clubId =
            insertReturningId(
                connection,
                """
                INSERT INTO clubs (
                    name, description, timezone, admin_channel_id,
                    bookings_topic_id, checkin_topic_id, qa_topic_id
                ) VALUES (?, NULL, 'Europe/Moscow', NULL, NULL, NULL, NULL)
                """.trimIndent(),
            ) { statement -> statement.setString(1, "V059 delivery migration club") }
        val ownerUserId = insertUser(connection, 8_800_600_001L, "v059-owner")
        val actorUserId = insertUser(connection, 8_800_600_002L, "v059-actor")
        val ticketId =
            insertReturningId(
                connection,
                """
                INSERT INTO tickets (
                    club_id, user_id, booking_id, list_entry_id, topic, status,
                    created_at, updated_at, last_agent_id, resolution_rating
                ) VALUES (?, ?, NULL, NULL, 'other', 'in_progress', ?, ?, ?, NULL)
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, clubId)
                statement.setLong(2, ownerUserId)
                statement.setObject(3, FIXED_TIME)
                statement.setObject(4, FIXED_TIME)
                statement.setLong(5, actorUserId)
            }
        val messageId =
            insertReturningId(
                connection,
                """
                INSERT INTO ticket_messages (ticket_id, sender_type, text, attachments, created_at)
                VALUES (?, 'agent', ?, ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, ticketId)
                statement.setString(2, LEGACY_REPLY)
                statement.setString(3, LEGACY_ATTACHMENT)
                statement.setObject(4, FIXED_TIME)
            }
        val auditId =
            insertReturningId(
                connection,
                """
                INSERT INTO audit_log (
                    created_at, club_id, night_id, actor_user_id, actor_role, subject_user_id,
                    entity_type, entity_id, action, fingerprint, metadata_json
                ) VALUES (?, ?, NULL, ?, 'MANAGER', NULL, 'SUPPORT_TICKET', ?,
                    'SUPPORT_REPLY', ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setObject(1, FIXED_TIME)
                statement.setLong(2, clubId)
                statement.setLong(3, actorUserId)
                statement.setLong(4, ticketId)
                statement.setString(5, "v059-support-reply-$ticketId")
                statement.setString(6, "{\"message_id\":$messageId}")
            }
        return MigrationFixture(ticketId, messageId, auditId)
    }

    private fun insertUser(
        connection: Connection,
        telegramUserId: Long,
        username: String,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO users (telegram_user_id, username, display_name, phone_e164)
            VALUES (?, ?, ?, NULL)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, telegramUserId)
            statement.setString(2, username)
            statement.setString(3, username)
        }

    private fun readFixture(
        connection: Connection,
        fixture: MigrationFixture,
    ): MigrationSnapshot {
        val ticket =
            connection
                .prepareStatement(
                    "SELECT status, last_agent_id, updated_at FROM tickets WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.ticketId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        Triple(
                            rows.getString("status"),
                            rows.getLong("last_agent_id"),
                            rows.getObject("updated_at", OffsetDateTime::class.java),
                        )
                    }
                }
        val message =
            connection
                .prepareStatement(
                    "SELECT ticket_id, sender_type, text, attachments, created_at " +
                        "FROM ticket_messages WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.messageId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        listOf(
                            rows.getLong("ticket_id").toString(),
                            rows.getString("sender_type"),
                            rows.getString("text"),
                            rows.getString("attachments"),
                            rows.getObject("created_at", OffsetDateTime::class.java).toString(),
                        )
                    }
                }
        val audit =
            connection
                .prepareStatement(
                    "SELECT entity_id, action, fingerprint, metadata_json FROM audit_log WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.auditId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        listOf(
                            rows.getLong("entity_id").toString(),
                            rows.getString("action"),
                            rows.getString("fingerprint"),
                            rows.getString("metadata_json"),
                        )
                    }
                }
        return MigrationSnapshot(ticket, message, audit)
    }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            assertEquals(1, statement.executeUpdate())
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong(1)
            }
        }

    private fun scalarLong(
        connection: Connection,
        sql: String,
    ): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }

    private fun tableExists(
        connection: Connection,
        tableName: String,
    ): Boolean =
        connection.metaData
            .getTables(null, null, tableName, arrayOf("TABLE"))
            .use { tables -> tables.next() }

    private data class MigrationFixture(
        val ticketId: Long,
        val messageId: Long,
        val auditId: Long,
    )

    private data class MigrationSnapshot(
        val ticket: Triple<String, Long, OffsetDateTime>,
        val message: List<String>,
        val audit: List<String>,
    )

    private companion object {
        const val H2_USER = "sa"
        const val H2_PASSWORD = ""
        const val PREVIOUS_VERSION = "059"
        const val DELIVERY_VERSION = "060"
        const val LEGACY_REPLY = "legacy private reply remains without delivery result"
        const val LEGACY_ATTACHMENT = "legacy-private-attachment"
        val FIXED_TIME: OffsetDateTime = Instant.parse("2026-08-23T10:00:00Z").atOffset(ZoneOffset.UTC)
    }
}
