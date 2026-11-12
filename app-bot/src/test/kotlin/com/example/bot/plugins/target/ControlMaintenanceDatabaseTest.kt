package com.example.bot.plugins.target

import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.MaintenanceMutationStatus
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.routes.ClientOnboardingRoutesTestBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real signed verifier + actual control lease + existing lifecycle transaction, never runtime DI. */
open class ControlMaintenanceDatabaseTest : ClientOnboardingRoutesTestBase() {
    @Test fun `signed owner under DB head commits and replays exactly once without activating anything`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                val result =
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.DISABLE,
                        "control-command",
                        f.identity.signing.headers(),
                    )
                assertEquals(MaintenanceMutationStatus.APPLIED, result.status)
                val before = f.snapshot()
                assertEquals(
                    MaintenanceMutationStatus.REPLAY_CURRENT,
                    gate
                        .adapter()
                        .execute(
                            pin,
                            InactiveMaintenanceAction.DISABLE,
                            "control-command",
                            f.identity.signing.headers(),
                        ).status,
                )
                assertEquals(before, f.snapshot())
                assertTrue(gate.checks > 2)
                assertTrue(gate.delivered!!.admissible())
            }
        }

    @Test fun `a row or pin alone and default missing bundle provider cannot authorize`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter(DbControlMaintenanceLeaseSource(gate.schema)).execute(
                        pin,
                        InactiveMaintenanceAction.REVOKE,
                        "no-bundle",
                        f.identity.signing.headers(),
                    )
                }
                gate.delivered = null
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.REVOKE,
                        "row-only",
                        f.identity.signing.headers(),
                    )
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `wrong signed actor bot and expired authentication are denied after head admission`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                val before = f.snapshot()
                for (raw in listOf(
                    f.identity.signing.signed(user = "{\"id\":8}"),
                    f.identity.signing.signed(key = f.identity.signing.token + "wrong"),
                    f.identity.signing.signed(
                        date =
                            f.identity.signing.now
                                .minusSeconds(300)
                                .epochSecond
                                .toString(),
                    ),
                )) {
                    assertThrows<OnboardingFailure> {
                        gate.adapter().execute(
                            pin,
                            InactiveMaintenanceAction.DISABLE,
                            "wrong-proof",
                            f.identity.signing.headers(raw),
                        )
                    }
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `committed publication fences old replica and fresh bundle can perform a new permitted command`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                val updated = gate.publish()
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.DISABLE,
                        "old-replica",
                        f.identity.signing.headers(),
                    )
                }
                assertEquals(before, f.snapshot())
                gate.delivered = updated
                assertEquals(
                    MaintenanceMutationStatus.APPLIED,
                    gate
                        .adapter()
                        .execute(
                            pin,
                            InactiveMaintenanceAction.REVOKE,
                            "new-replica",
                            f.identity.signing.headers(),
                        ).status,
                )
            }
        }

    @Test fun `old receipt cannot alias a new publication domain`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                gate.adapter().execute(pin, InactiveMaintenanceAction.DISABLE, "same-key", f.identity.signing.headers())
                gate.delivered = gate.publish()
                val before = f.snapshot()
                val failure =
                    assertThrows<OnboardingFailure> {
                        gate.adapter().execute(
                            pin,
                            InactiveMaintenanceAction.DISABLE,
                            "same-key",
                            f.identity.signing.headers(),
                        )
                    }
                assertEquals("idempotency_conflict", failure.code)
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `finite operations and exact incarnation deny untrusted substitutions`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f, setOf(InactiveMaintenanceAction.DISABLE))
            runBlocking {
                val pin = gate.pin()
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.REVOKE,
                        "unapproved-op",
                        f.identity.signing.headers(),
                    )
                }
                val changed = pin.binding.copy(rootId = java.util.UUID.randomUUID())
                val forged =
                    MaintenanceSnapshotPin(
                        changed,
                        pin.actorTelegramId,
                        pin.authenticatedAt,
                        pin.validUntil,
                        pin.selectedAt,
                        pin.contextRevision,
                        null,
                        null,
                    )
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        forged,
                        InactiveMaintenanceAction.DISABLE,
                        "foreign-root",
                        f.identity.signing.headers(),
                    )
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `post-write current authority loss and cancellation roll back actual lifecycle`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                for (cancel in listOf(false, true)) {
                    f.identity.owner = 7
                    gate.checks = 0
                    gate.onCurrent = {
                        if (gate.checks >= 5) {
                            if (cancel) throw CancellationException("synthetic cancel")
                            f.identity.owner = null
                        }
                    }
                    val before = f.snapshot()
                    if (cancel) {
                        assertThrows<CancellationException> {
                            gate.adapter().execute(
                                pin,
                                InactiveMaintenanceAction.REVOKE,
                                "cancelled",
                                f.identity.signing.headers(),
                            )
                        }
                    } else {
                        assertThrows<OnboardingFailure> {
                            gate.adapter().execute(
                                pin,
                                InactiveMaintenanceAction.REVOKE,
                                "lost-authority",
                                f.identity.signing.headers(),
                            )
                        }
                    }
                    assertEquals(before, f.snapshot())
                }
            }
        }

    @Test fun `child root fence and revoked binding defeat old pins without touching head`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                f.disable()
                val changed = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.REVOKE,
                        "child-changed",
                        f.identity.signing.headers(),
                    )
                }
                assertEquals(changed, f.snapshot())
                f.revoke()
                val revoked = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.DISABLE,
                        "terminal",
                        f.identity.signing.headers(),
                    )
                }
                assertEquals(revoked, f.snapshot())
            }
        }

    @Test fun `selected material retirement uses signed finite intent and never resolves a token`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val root = f.identity.reference.rootId
            val binding = f.identity.reference.fence.bindingId
            val material = java.util.UUID.randomUUID()
            val revision = java.util.UUID.randomUUID()
            f.execute(
                "INSERT INTO target_credential_materials(id,root_id," +
                    "created_audit_id) VALUES('$material','$root',978001)",
                "INSERT INTO target_credential_revisions(id,root_id,material_id," +
                    "revision,verified_bot_id,attestation_id,created_audit_id) " +
                    "VALUES('$revision','$root','$material',1,780001,'${java.util.UUID.randomUUID()}',978001)",
                "UPDATE target_bot_bindings SET credential_ref='$revision' WHERE id='$binding'",
            )
            f.identity.reference = f.identity.reference.copy(credentialRevision = 1)
            f.refresh()
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val pin = gate.pin()
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    gate.adapter().execute(
                        pin,
                        InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL,
                        "wrong-material",
                        f.identity.signing.headers(),
                        materialId = java.util.UUID.randomUUID(),
                    )
                }
                assertEquals(before, f.snapshot())
                assertEquals(
                    MaintenanceMutationStatus.APPLIED,
                    gate
                        .adapter()
                        .execute(
                            pin,
                            InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL,
                            "retire-current",
                            f.identity.signing.headers(),
                            materialId = material,
                        ).status,
                )
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it
                            .executeQuery(
                                "SELECT status FROM target_credential_materials WHERE " +
                                    "id='$material'",
                            ).use { rows ->
                                assertTrue(rows.next())
                                assertEquals("RETIRED", rows.getString(1))
                            }
                        it
                            .executeQuery(
                                "SELECT status,credential_ref FROM target_bot_bindings WHERE id='$binding'",
                            ).use { rows ->
                                assertTrue(rows.next())
                                assertEquals("DISABLED", rows.getString(1))
                                assertEquals(null, rows.getObject(2))
                            }
                    }
                }
            }
        }

    @Test fun `fresh canonical pin permits the next operation without rebasing protected configuration`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            runBlocking {
                val first = gate.pin()
                gate.adapter().execute(
                    first,
                    InactiveMaintenanceAction.DISABLE,
                    "first-disable",
                    f.identity.signing.headers(),
                )
                val protectedBundle = gate.delivered
                f.refresh()
                f.identity.selection =
                    MaintenanceServerSelection(
                        checkNotNull(f.identity.authentication),
                        f.identity.reference,
                        first.contextRevision,
                    )
                val fresh = gate.pin()
                assertEquals(first.contextRevision, fresh.contextRevision)
                assertTrue(fresh.binding.fence.version > first.binding.fence.version)
                assertEquals(
                    MaintenanceMutationStatus.APPLIED,
                    gate
                        .adapter()
                        .execute(
                            fresh,
                            InactiveMaintenanceAction.REVOKE,
                            "then-revoke",
                            f.identity.signing.headers(),
                        ).status,
                )
                assertTrue(gate.delivered === protectedBundle)
            }
        }
}
