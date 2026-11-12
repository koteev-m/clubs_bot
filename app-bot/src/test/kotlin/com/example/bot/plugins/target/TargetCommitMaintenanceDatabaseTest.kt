package com.example.bot.plugins.target

import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.MaintenanceMutationStatus
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.routes.ClientOnboardingRoutesTestBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

open class TargetCommitMaintenanceDatabaseTest : ClientOnboardingRoutesTestBase() {
    @Test fun `signed platform owner authorizes existing writer and replay has no second mutation`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            runBlocking {
                val pin = readPin(f)
                val adapter = commitAdapter(f, gate)
                val headers = f.identity.signing.headers()
                val applied = adapter.execute(pin, InactiveMaintenanceAction.DISABLE, "signed-command", headers)
                assertEquals(MaintenanceMutationStatus.APPLIED, applied.status)
                val beforeReplay = f.snapshot()
                assertEquals(
                    MaintenanceMutationStatus.REPLAY_CURRENT,
                    adapter.execute(pin, InactiveMaintenanceAction.DISABLE, "signed-command", headers).status,
                )
                assertEquals(beforeReplay, f.snapshot())
                f.revoke() // Another legitimate guarded lifecycle path changes the same aggregate.
                assertEquals(
                    MaintenanceMutationStatus.REPLAY_STALE,
                    adapter.execute(pin, InactiveMaintenanceAction.DISABLE, "signed-command", headers).status,
                )
                assertThrows<OnboardingFailure> {
                    adapter.execute(
                        pin,
                        InactiveMaintenanceAction.DISABLE,
                        "old-pin-new-key",
                        headers,
                    )
                }
            }
            assertEquals(gate.acquired, gate.closed)
        }

    @Test fun `a valid pin and signed identity cannot write without a shared authority lease provider`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            runBlocking {
                val pin = readPin(f)
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                    ).execute(pin, InactiveMaintenanceAction.REVOKE, "missing-lease", f.identity.signing.headers())
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `wrong actor wrong bot and stale initData are checked inside transaction`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            runBlocking {
                val pin = readPin(f)
                val before = f.snapshot()
                val wrong =
                    listOf(
                        f.identity.signing.signed(user = "{\"id\":8}"),
                        f.identity.signing.signed(key = f.identity.signing.token + "wrong"),
                        f.identity.signing.signed(
                            date =
                                f.identity.signing.now
                                    .minusSeconds(300)
                                    .epochSecond
                                    .toString(),
                        ),
                    )
                for (raw in wrong) {
                    assertThrows<OnboardingFailure> {
                        commitAdapter(
                            f,
                            gate,
                        ).execute(
                            pin,
                            InactiveMaintenanceAction.REVOKE,
                            "wrong-identity",
                            f.identity.signing.headers(raw),
                        )
                    }
                }
                assertEquals(before, f.snapshot())
                assertTrue(gate.verifications >= 3)
            }
        }

    @Test fun `authority or selected context changed after metadata read prevents all writes`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            runBlocking {
                val pin = readPin(f)
                val before = f.snapshot()
                gate.change { f.identity.owner = null }
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                        gate,
                    ).execute(pin, InactiveMaintenanceAction.DISABLE, "owner-revoked", f.identity.signing.headers())
                }
                gate.change {
                    f.identity.owner = 7
                    f.identity.selection =
                        MaintenanceServerSelection(
                            checkNotNull(f.identity.authentication),
                            f.identity.reference,
                            pin.contextRevision + 1,
                        )
                }
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                        gate,
                    ).execute(pin, InactiveMaintenanceAction.DISABLE, "context-changed", f.identity.signing.headers())
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `post-write authority recheck and cancellation roll back audit receipt and fences`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            runBlocking {
                val pin = readPin(f)
                for (cancel in listOf(false, true)) {
                    f.identity.owner = 7
                    val gate = SyntheticMaintenanceMutationLease(f.identity)
                    gate.onVerify = {
                        if (gate.verifications >= 4) {
                            if (cancel) throw CancellationException("synthetic cancel") else f.identity.owner = null
                        }
                    }
                    val before = f.snapshot()
                    if (cancel) {
                        assertThrows<CancellationException> {
                            commitAdapter(
                                f,
                                gate,
                            ).execute(pin, InactiveMaintenanceAction.REVOKE, "post-check", f.identity.signing.headers())
                        }
                    } else {
                        assertThrows<OnboardingFailure> {
                            commitAdapter(
                                f,
                                gate,
                            ).execute(pin, InactiveMaintenanceAction.REVOKE, "post-check", f.identity.signing.headers())
                        }
                    }
                    assertEquals(before, f.snapshot())
                    assertEquals(gate.acquired, gate.closed)
                }
            }
        }

    @Test fun `branded provider cannot override unsupported writer even before and after mapping ABA`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source, branded = true)
            f.identity.business = f.identity.authority()
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            runBlocking {
                val pin = readPin(f)
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                        gate,
                    ).execute(pin, InactiveMaintenanceAction.DISABLE, "brand", f.identity.signing.headers())
                }
                assertEquals(before, f.snapshot())
                f.execute(
                    "DELETE FROM client_clubs WHERE club_id=1003",
                    "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1003,'2026-11-11T00:00:00Z')",
                )
                val afterMapping = f.snapshot()
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                        gate,
                    ).execute(pin, InactiveMaintenanceAction.DISABLE, "stale-brand", f.identity.signing.headers())
                }
                assertEquals(afterMapping, f.snapshot())
                assertEquals(0, gate.acquired)
            }
        }

    @Test fun `shared authority revoke waits for commit and a committed revoke denies subsequent mutation`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            runBlocking {
                val pin = readPin(f)
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                var sawCommitted = false
                gate.onVerify = {
                    if (gate.verifications == 4) {
                        locked.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                gate.onClose = { sawCommitted = f.snapshot()["target_binding_commands"]?.size == 1 }
                val pending =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        commitAdapter(
                            f,
                            gate,
                        ).execute(pin, InactiveMaintenanceAction.DISABLE, "lease-commit", f.identity.signing.headers())
                    }
                awaitMutation(locked)
                val revoke =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        changeMutationAuthority(gate) { f.identity.owner = null }
                    }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (!gate.queued && System.nanoTime() < deadline) Thread.yield()
                    assertTrue(gate.queued)
                    assertEquals(7L, f.identity.owner)
                } finally {
                    release.countDown()
                }
                assertEquals(MaintenanceMutationStatus.APPLIED, pending.await().status)
                revoke.await()
                assertTrue(sawCommitted)
                assertThrows<OnboardingFailure> {
                    commitAdapter(
                        f,
                        gate,
                    ).execute(pin, InactiveMaintenanceAction.REVOKE, "after-revoke", f.identity.signing.headers())
                }
            }
        }
}

private suspend fun readPin(f: MaintenanceDatabaseFixture): MaintenanceSnapshotPin =
    assertIs<MaintenanceSelection.Selected>(f.identity.selector(f.jdbc).select(f.identity.identity())).pin

private fun commitAdapter(
    f: MaintenanceDatabaseFixture,
    gate: MaintenanceMutationLeaseSource =
        MaintenanceMutationLeaseSource {
            null
        },
): TargetCommitTimeMaintenanceAdapter =
    TargetCommitTimeMaintenanceAdapter(
        f.dataSource,
        BindingStorageSchema
            .fromServerConfiguration(
                f.dataSource.connection.use {
                    checkNotNull(it.schema)
                },
            ),
        f.identity.connection,
        f.identity.verifier,
        gate,
        f.identity.signing.clock,
    )

private suspend fun awaitMutation(
    latch: CountDownLatch,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) = withContext(dispatcher) { check(latch.await(15, TimeUnit.SECONDS)) }

private suspend fun changeMutationAuthority(
    gate: SyntheticMaintenanceMutationLease,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: () -> Unit,
) = withContext(dispatcher) { gate.change(block) }
