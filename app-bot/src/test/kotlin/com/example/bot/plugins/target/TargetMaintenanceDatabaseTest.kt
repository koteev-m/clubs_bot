package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.CredentialMaterialState
import com.example.bot.data.client.bindings.DurableBindingSource
import com.example.bot.data.client.bindings.JdbcDurableBindingSource
import com.example.bot.routes.ClientOnboardingRoutesTestBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

open class TargetMaintenanceDatabaseTest : ClientOnboardingRoutesTestBase() {
    @Test fun `real platform binding yields only metadata and selection writes nothing`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val before = f.snapshot()
            runBlocking {
                val selector = f.identity.selector(f.jdbc)
                val actor = f.identity.identity()
                repeat(2) {
                    val pin = assertIs<MaintenanceSelection.Selected>(selector.select(actor)).pin
                    assertEquals(f.identity.reference.fence, pin.binding.fence)
                    assertEquals(ClientAudience.Platform, pin.binding.audience)
                }
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `real private branded finite set requires independent business ownership`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source, branded = true)
            runBlocking {
                val selector = f.identity.selector(f.jdbc)
                val actor = f.identity.identity()
                assertEquals(MaintenanceSelection.Unavailable, selector.select(actor))
                f.identity.business = f.identity.authority()
                val pin = assertIs<MaintenanceSelection.Selected>(selector.select(actor)).pin
                assertEquals(setOf(1001L, 1002L), (pin.binding.audience as ClientAudience.Client).allowedClubIds)
                f.identity.business = f.identity.authority(clubs = setOf(1001))
                assertEquals(MaintenanceSelection.Denied, selector.select(actor))
                f.identity.business = f.identity.authority(client = 102, clubs = setOf(2001))
                assertEquals(MaintenanceSelection.Denied, selector.select(actor))
            }
        }

    @Test fun `child mutation fences old server reference and fresh reference sees disabled then revoked`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val old = f.identity.reference.fence
            runBlocking {
                val actor = f.identity.identity()
                val selector = f.identity.selector(f.jdbc)
                val pin = assertIs<MaintenanceSelection.Selected>(selector.select(actor)).pin
                f.disable()
                assertEquals(MaintenanceSelection.Stale, selector.select(actor))
                f.refresh()
                assertTrue(f.identity.reference.fence.generation > old.generation)
                assertIs<MaintenanceSelection.Selected>(selector.select(actor))
                f.revoke()
                f.refresh()
                assertEquals(MaintenanceSelection.Revoked, selector.select(actor))
                assertEquals(old, pin.binding.fence) // Old diagnostic data never becomes a new authorization input.
            }
            assertThrows<SQLException> { f.execute("UPDATE target_bot_bindings SET status='ACTIVE'") }
        }

    @Test fun `mapping ABA and partial binding scope invalidate selection on both engines`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source, branded = true)
            f.identity.business = f.identity.authority()
            runBlocking {
                val selector = f.identity.selector(f.jdbc)
                val actor = f.identity.identity()
                assertIs<MaintenanceSelection.Selected>(selector.select(actor))
                f.execute(
                    "DELETE FROM client_clubs WHERE client_id=101 AND club_id=1003",
                    "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1003,'2026-11-11T00:00:00Z')",
                )
                assertEquals(MaintenanceSelection.Stale, selector.select(actor))
                f.refresh()
                val pin = assertIs<MaintenanceSelection.Selected>(selector.select(actor)).pin
                assertEquals(setOf(1001L, 1002L), (pin.binding.audience as ClientAudience.Client).allowedClubIds)
                f.execute("DELETE FROM target_binding_clubs WHERE club_id=1002")
                assertEquals(MaintenanceSelection.Stale, selector.select(actor))
                f.refresh()
                assertEquals(MaintenanceSelection.Denied, selector.select(actor))
            }
        }

    @Test fun `wrong root incarnation and generation pins cannot select neighboring records`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val original = f.identity.reference
            runBlocking {
                val actor = f.identity.identity()
                for (ref in listOf(
                    original.copy(rootId = UUID.randomUUID()),
                    original.copy(fence = original.fence.copy(bindingId = UUID.randomUUID())),
                    original.copy(fence = original.fence.copy(generation = original.fence.generation + 1)),
                )) {
                    f.identity.reference = ref
                    f.identity.selection = MaintenanceServerSelection(checkNotNull(f.identity.authentication), ref, 10)
                    assertFalse(f.identity.selector(f.jdbc).select(actor) is MaintenanceSelection.Selected)
                }
            }
        }

    @Test fun `retirement metadata never resolves credentials and remains unchanged by read`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            f.retireCredential()
            val before = f.snapshot()
            runBlocking {
                val pin =
                    assertIs<MaintenanceSelection.Selected>(
                        f.identity.selector(f.jdbc).select(f.identity.identity()),
                    ).pin
                assertEquals(CredentialMaterialState.RETIRED, pin.binding.credential?.state)
                assertFalse(
                    pin.toString().contains(
                        pin.binding.credential
                            ?.referenceId
                            .toString(),
                    ),
                )
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `real read followed by concurrent revoke cannot yield a current selected pin`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            runBlocking {
                val queried = CountDownLatch(1)
                val release = CountDownLatch(1)
                var reads = 0
                val fenced =
                    DurableBindingSource { reference ->
                        val result = f.jdbc.read(reference)
                        if (++reads == 1) {
                            queried.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                        result
                    }
                val actor = f.identity.identity()
                val pending = async(start = CoroutineStart.UNDISPATCHED) { f.identity.selector(fenced).select(actor) }
                try {
                    assertTrue(queried.await(15, TimeUnit.SECONDS))
                    f.revoke()
                } finally {
                    release.countDown()
                }
                assertEquals(MaintenanceSelection.Stale, pending.await())
                f.refresh()
                assertEquals(MaintenanceSelection.Revoked, f.identity.selector(f.jdbc).select(actor))
            }
        }

    @Test fun `database failures and cancellation fail closed without any writes`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val before = f.snapshot()
            val schema = source.connection.use { checkNotNull(it.schema) }

            fun broken(failure: Exception): DurableBindingSource {
                val unavailable =
                    object : DataSource by source {
                        override fun getConnection(): Connection = throw failure
                    }
                return JdbcDurableBindingSource(unavailable, BindingStorageSchema.fromServerConfiguration(schema))
            }
            runBlocking {
                val actor = f.identity.identity()
                assertEquals(
                    MaintenanceSelection.Error,
                    f.identity.selector(broken(SQLException("synthetic private detail"))).select(actor),
                )
                assertThrows<CancellationException> {
                    f.identity.selector(broken(CancellationException("synthetic cancel"))).select(actor)
                }
            }
            assertEquals(before, f.snapshot())
        }
}
