package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

@Tag("it")
internal class ControlHeadRetryPostgresIT {
    @Test fun `native SQL retry fully rolls back and reauthorizes rather than reusing a holder`() =
        runBlocking {
            for (state in listOf("40001", "40P01")) {
                for (withdraw in listOf(false, true)) {
                    BindingTestFixture(AssignmentTestDatabase.postgres()).use { binding ->
                        val f = ControlHeadFixture.create(binding)
                        f.publish()
                        val before = f.rows()
                        binding.database.execute(
                            "CREATE SEQUENCE control_fault START 1",
                            "CREATE FUNCTION control_fault() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN " +
                                "IF nextval('${f.schema.quoted}.control_fault')=1 THEN " +
                                "RAISE EXCEPTION 'synthetic retry' USING ERRCODE='$state'; END IF; RETURN NEW; END $$",
                            "CREATE TRIGGER zz_control_fault BEFORE UPDATE ON target_maintenance_control_heads " +
                                "FOR EACH ROW EXECUTE FUNCTION control_fault()",
                        )
                        f.onAuthority = {
                            val called =
                                binding.database.scalar(
                                    "SELECT CASE WHEN is_called THEN 1 ELSE 0 END FROM control_fault",
                                ) ==
                                    "1"
                            if (withdraw && called) null else f.identity
                        }
                        if (withdraw) {
                            assertThrows<OnboardingFailure> { f.publisher.fence(f.enabled) }
                            assertEquals(before, f.rows())
                        } else {
                            assertEquals(3, f.publisher.fence(f.enabled).generation)
                            assertEquals(
                                "3",
                                binding.database.scalar("SELECT COUNT(*) FROM target_maintenance_publications"),
                            )
                            assertEquals("2", binding.database.scalar("SELECT last_value FROM control_fault"))
                        }
                    }
                }
            }
        }

    @Test fun `55P03 is not retried and publisher preserves all metadata while holder remains live`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { binding ->
                val f = ControlHeadFixture.create(binding)
                f.publish()
                val before = f.rows()
                f.withConnection { connection ->
                    JdbcControlHeadLease(f.schema, f.enabled).verify(connection)
                    val failure = assertThrows<SQLException> { runBlocking { f.publisher.fence(f.enabled) } }
                    assertEquals("55P03", failure.sqlState)
                }
                assertEquals(before, f.rows())
            }
        }

    @Test fun `coroutine cancellation during head wait rolls back the actual guarded lifecycle`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { binding ->
                val f = ControlHeadFixture.create(binding)
                f.publish()
                val view = binding.repository.read(BindingTestFixture.EVIDENCE, f.scope.rootId)!!
                val intent = CommitMaintenanceFixture(binding).intent(view)
                val authorization =
                    BindingMaintenanceAuthorization {
                        object : BindingMaintenanceLease {
                            override val controlHead = JdbcControlHeadLease(f.schema, f.enabled)
                            override val intentBinding = "synthetic-control-cancellation"

                            override fun identity(connection: java.sql.Connection): MaintenanceIdentity {
                                controlHead.verify(connection)
                                return MaintenanceIdentity(
                                    BindingTestFixture.TELEGRAM,
                                    f.scope.authorityConnectionId,
                                    f.manifest,
                                    BindingTestFixture.START,
                                    BindingTestFixture.START.plusSeconds(240),
                                )
                            }

                            override fun close() = controlHead.close()
                        }
                    }
                val adapter = JdbcCommitTimeBindingMaintenance(binding.source, f.schema, authorization, binding.clock)
                val before = f.rows()
                binding.source.applicationName = "control-cancel-" + java.util.UUID.randomUUID()
                f.withConnection { holder ->
                    holder.createStatement().use { query ->
                        query.executeQuery("SELECT id FROM target_maintenance_control_heads FOR UPDATE").close()
                    }
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) { adapter.execute(intent, "cancelled-head") }
                    val deadline =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.SECONDS
                                .toNanos(2)
                    while (true) {
                        val waiting =
                            binding.database.scalar(
                                "SELECT COUNT(*) FROM pg_stat_activity " +
                                    "WHERE application_name='${binding.source.applicationName}' " +
                                    "AND wait_event_type='Lock'",
                            )
                        if (waiting == "1") break
                        check(System.nanoTime() < deadline)
                        Thread.sleep(10)
                    }
                    pending.cancel()
                    holder.rollback()
                    assertThrows<CancellationException> { runBlocking { pending.await() } }
                }
                assertEquals(before, f.rows())
                assertEquals(MaintenanceMutationStatus.APPLIED, adapter.execute(intent, "fresh-after-cancel").status)
            }
        }
}
