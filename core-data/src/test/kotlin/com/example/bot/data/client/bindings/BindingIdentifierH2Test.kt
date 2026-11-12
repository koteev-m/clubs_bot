package com.example.bot.data.client.bindings

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Default H2 upper-case identifiers and the project's lower-case mode must enforce identical fences. */
internal class BindingIdentifierH2Test {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `both identifier modes preserve epoch and inactive lifecycle`(upperCase: Boolean) =
        runBlocking {
            val source =
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:binding-identifiers-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=$upperCase",
                    )
                    user = "sa"
                }
            try {
                Flyway
                    .configure()
                    .dataSource(
                        source,
                    ).locations("classpath:db/migration/common", "classpath:db/migration/h2")
                    .load()
                    .also {
                        it.migrate()
                        it.validate()
                    }
                source.connection.use { c ->
                    c.createStatement().use { s ->
                        s.execute("INSERT INTO users(id,telegram_user_id) VALUES (78001,878001)")
                        s.execute("INSERT INTO clients(id,display_name,is_active) VALUES (101,'Identifiers',TRUE)")
                        s.execute("INSERT INTO clubs(id,name,timezone) VALUES (1001,'Identifiers','UTC')")
                        s.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001)")
                        s.execute("DELETE FROM client_clubs WHERE club_id=1001")
                        s.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001)")
                        s.executeQuery("SELECT target_mapping_epoch FROM clients WHERE id=101").use { r ->
                            r.next()
                            assertEquals(4L, r.getLong(1))
                        }
                    }
                }
                val repository = repository(source)
                val create =
                    BindingCommand.Create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BindingTestFixture.BOT,
                        UUID.randomUUID(),
                    )
                val first = repository.mutate(BindingTestFixture.EVIDENCE, create, "create").current
                val reference =
                    BindingCommand.AttachReference(
                        first.rootId,
                        first.fence,
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                val attached = repository.mutate(BindingTestFixture.EVIDENCE, reference, "reference").current
                assertEquals(reference.referenceId, attached.credentialReference)
                val revoked =
                    repository
                        .mutate(
                            BindingTestFixture.EVIDENCE,
                            BindingCommand.State(first.rootId, attached.fence, BindingState.REVOKED),
                            "revoke",
                        ).current
                val next =
                    repository
                        .mutate(
                            BindingTestFixture.EVIDENCE,
                            BindingCommand.Replace(first.rootId, revoked.fence, UUID.randomUUID(), UUID.randomUUID()),
                            "replace",
                        ).current
                assertEquals(BindingState.PENDING, next.status)
                assertEquals(10L, next.fence.generation)
                assertEquals(next, repository.read(BindingTestFixture.EVIDENCE, first.rootId))
            } finally {
                source.connection.use { c -> c.createStatement().use { it.execute("SHUTDOWN") } }
            }
        }

    private fun repository(source: JdbcDataSource): JdbcInactiveBindingFoundation {
        val now = BindingTestFixture.START
        val connection: UUID = UUID.randomUUID()
        val authority: UUID = UUID.randomUUID()
        val provider =
            object : BindingEvidenceProvider {
                override fun maintenance(
                    evidence: String,
                    now: Instant,
                ) = MaintenanceIdentity(BindingTestFixture.TELEGRAM, connection, authority, now, now.plusSeconds(240))

                override fun bot(
                    command: BindingCommand.Create,
                    now: Instant,
                ) = BotIdentityEvidence(command.rootId, command.botId, command.provenanceId, now, now.plusSeconds(240))

                override fun credential(
                    command: BindingCommand.AttachReference,
                    now: Instant,
                ) = CredentialReferenceEvidence(
                    command.rootId,
                    BindingTestFixture.BOT,
                    command.referenceId,
                    command.revision,
                    command.materialId,
                    command.attestationId,
                    now,
                    now.plusSeconds(240),
                )
            }
        return JdbcInactiveBindingFoundation(
            source,
            BindingTestFixture.TELEGRAM,
            provider,
            Clock.fixed(now, ZoneOffset.UTC),
            storageSchema = BindingStorageSchema.fromServerConfiguration(source.connection.use { it.schema }),
        )
    }
}
