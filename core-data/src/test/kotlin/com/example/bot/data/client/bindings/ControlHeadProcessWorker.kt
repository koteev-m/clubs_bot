package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** Independent JVM, actual JDBC lease/publisher. Explicit disposable DB + synthetic metadata file only. */
internal object ControlHeadProcessWorker {
    @JvmStatic fun main(args: Array<String>) {
        val values = Properties().apply { Files.newInputStream(Path.of(args[0])).use { load(it) } }
        val url = values.getProperty("url")
        val uri = URI(url.removePrefix("jdbc:"))
        require(uri.host == "127.0.0.1" && uri.path in setOf("/clb_client_test", "/botdb"))
        require(uri.query == null && uri.userInfo == null)
        val source =
            PGSimpleDataSource().apply {
                setURL(url)
                user = values.getProperty("user")
                password = values.getProperty("password")
                currentSchema = values.getProperty("schema")
                applicationName = values.getProperty("application")
            }
        val schema = BindingStorageSchema.fromServerConfiguration(values.getProperty("schema"))
        val scope = MaintenanceControlScope(values.uuid("id"), values.uuid("root"), values.uuid("realm"))
        val head =
            MaintenanceControlHead(
                scope,
                2,
                2,
                ControlPublicationStatus.ENABLED,
                values.uuid("manifest"),
                "a".repeat(64),
            )
        println("STARTED:${ProcessHandle.current().pid()}")
        System.out.flush()
        try {
            if (args[1] == "lease") {
                holdLease(source, schema, head)
            } else {
                check(readln() == "GO")
                if (args[1] == "raw") {
                    rawFence(source, schema, head, values.getProperty("actor").toLong())
                    println("FENCED:3")
                    return
                }
                val authority =
                    SyntheticControlPublisherAuthority {
                        SyntheticControlPublisherIdentity(
                            values.getProperty("actor").toLong(),
                            values.uuid("provenance"),
                        )
                    }
                val publisher = SyntheticControlHeadPublisher(source, schema, authority)
                val changed =
                    runBlocking {
                        if (args[1] == "create") publisher.create(scope) else publisher.fence(head)
                    }
                println("FENCED:${changed.generation}")
            }
        } catch (_: OnboardingFailure) {
            println("DENIED")
        } finally {
            System.out.flush()
        }
    }

    private fun Properties.uuid(name: String): UUID = UUID.fromString(getProperty(name))

    private fun holdLease(
        source: PGSimpleDataSource,
        schema: BindingStorageSchema,
        head: MaintenanceControlHead,
    ) {
        source.connection.use { connection ->
            connection.autoCommit = false
            JdbcControlHeadLease(schema, head).use { lease ->
                lease.verify(connection)
                println("READY")
                System.out.flush()
                when (readln()) {
                    "COMMIT" -> connection.commit()
                    "ROLLBACK" -> connection.rollback()
                    "CRASH" -> Runtime.getRuntime().halt(17)
                    else -> error("Invalid synthetic worker command")
                }
                try {
                    lease.verify(connection)
                    error("Ended transaction reused its lease")
                } catch (_: OnboardingFailure) {
                    println("STALE_HANDLE")
                } finally {
                    connection.rollback()
                }
            }
        }
    }

    private fun rawFence(
        source: PGSimpleDataSource,
        schema: BindingStorageSchema,
        head: MaintenanceControlHead,
        actor: Long,
    ) {
        source.connection.use { connection ->
            connection.autoCommit = false
            val audit =
                com.example.bot.data.client.onboarding.OnboardingSql(connection).insert(
                    "INSERT INTO ${schema.quoted}.audit_log " +
                        "(created_at,actor_user_id,entity_type,entity_id,action,fingerprint,metadata_json) " +
                        "VALUES(CURRENT_TIMESTAMP,?,'CLIENT_ONBOARDING',NULL,'synthetic.raw',?,'{}')",
                    actor,
                    UUID.randomUUID().toString(),
                )
            connection
                .prepareStatement(
                    "UPDATE ${schema.quoted}.target_maintenance_control_heads SET " +
                        "version=version+1,generation=generation+1," +
                        "status='DISABLED',manifest_id=NULL,manifest_digest=NULL,last_audit_id=? WHERE id=? AND version=2",
                ).use { update ->
                    update.setLong(1, audit)
                    update.setObject(2, head.scope.id)
                    check(update.executeUpdate() == 1)
                }
            connection.commit()
        }
    }
}
