package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingReadRejection
import com.example.bot.data.client.bindings.BindingState
import com.example.bot.data.client.bindings.CredentialMaterialState
import com.example.bot.data.client.bindings.DurableBindingRead
import com.example.bot.data.client.bindings.DurableCredentialMetadata
import com.example.bot.plugins.TrustedBotConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TargetMaintenanceSelectorTest {
    @Test fun `authentication alone grants neither metadata nor staff authority`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val identity = f.identity()
            val denied = TargetMaintenanceSelector(f.connection, f.source, clock = f.signing.clock)
            assertEquals(MaintenanceSelection.Unavailable, denied.select(identity))
            assertEquals(0, f.reads)
            assertNull(f.verifier.verify(f.signing.headers(), "", f.signing.context))
            assertEquals(MaintenanceSelection.Denied, f.selector().select(null))
        }

    @Test fun `configured exact signed owner obtains only immutable inactive metadata`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val pin = assertIs<MaintenanceSelection.Selected>(f.selector().select(f.identity())).pin
            assertEquals(f.reference.fence, pin.binding.fence)
            assertEquals(ClientAudience.Platform, pin.binding.audience)
            assertEquals(2, f.reads)
            assertEquals(3, f.ownerReads)
            assertFalse(pin.toString().contains(f.reference.rootId.toString()))
            assertTrue(pin.toString().contains("not-mutation-authority"))
            assertEquals(null, pin.binding.credential)
        }

    @Test fun `other signed actor cannot use configured owner or same-key connection`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val other = f.verifier.verifyMaintenance(f.signing.headers(f.signing.signed(user = "{\"id\":8}")), "")
            assertNotNull(other)
            assertEquals(MaintenanceSelection.Denied, f.selector().select(other))
            val twin = TrustedBotConnection(f.connection.connectionKey, ClientAudience.Platform) { f.signing.token }
            f.selection = MaintenanceServerSelection(MaintenanceAuthenticationBinding(twin, 1), f.reference, 1)
            assertEquals(MaintenanceSelection.Denied, f.selector().select(f.identity()))
            val twinAuthentication = MaintenanceAuthenticationBinding(twin, 1)
            val twinIdentity =
                TargetIdentityVerifier(
                    twin,
                    clock = f.signing.clock,
                    maintenance = MaintenanceAuthenticationSource { twinAuthentication },
                ).verifyMaintenance(f.signing.headers(), "")
            assertNotNull(twinIdentity)
            assertEquals(MaintenanceSelection.Denied, f.selector().select(twinIdentity))
            assertEquals(0, f.reads)
        }

    @Test fun `authentication context withdrawal and replacement fence old verified identity`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val old = f.identity()
            f.authentication = null
            assertEquals(MaintenanceSelection.Unavailable, f.selector().select(old))
            assertNull(f.verifier.verifyMaintenance(f.signing.headers(), ""))
            f.authentication = MaintenanceAuthenticationBinding(f.connection, 2)
            f.selection = MaintenanceServerSelection(checkNotNull(f.authentication), f.reference, 2)
            assertEquals(MaintenanceSelection.Denied, f.selector().select(old))
            assertIs<MaintenanceSelection.Selected>(f.selector().select(f.identity()))
        }

    @Test fun `wrong bot stale future and malformed signed credentials fail authentication`() {
        val f = MaintenanceSelectorFixture()
        val valid = f.signing.signed()
        assertNull(
            f.verifier.verifyMaintenance(f.signing.headers(f.signing.signed(key = f.signing.token + "wrong")), ""),
        )
        assertNull(
            f.verifier.verifyMaintenance(
                f.signing.headers(
                    f.signing.signed(
                        date =
                            f.signing.now
                                .minusSeconds(300)
                                .epochSecond
                                .toString(),
                    ),
                ),
                "",
            ),
        )
        assertNotNull(
            f.verifier.verifyMaintenance(
                f.signing.headers(
                    f.signing.signed(
                        date =
                            f.signing.now
                                .minusSeconds(299)
                                .epochSecond
                                .toString(),
                    ),
                ),
                "",
            ),
        )
        assertNull(
            f.verifier.verifyMaintenance(
                f.signing.headers(
                    f.signing.signed(
                        date =
                            f.signing.now
                                .plusSeconds(1)
                                .epochSecond
                                .toString(),
                    ),
                ),
                "",
            ),
        )
        assertNull(f.verifier.verifyMaintenance(f.signing.headers(valid + "&auth_date=1"), ""))
        assertNull(f.verifier.verifyMaintenance(f.signing.headers(valid), "initData=untrusted"))
        val clientAuthentication = MaintenanceAuthenticationBinding(f.signing.connection, 1)
        val clientVerifier =
            TargetIdentityVerifier(
                f.signing.connection,
                clock = f.signing.clock,
                maintenance = MaintenanceAuthenticationSource { clientAuthentication },
            )
        assertNull(clientVerifier.verifyMaintenance(f.signing.headers(), ""))
        for (user in listOf("{\"id\":0}", "{\"id\":-7}")) {
            assertNull(f.verifier.verifyMaintenance(f.signing.headers(f.signing.signed(user = user)), ""))
        }
    }

    @Test fun `authentication rechecks live context and freshness after provider waits`() {
        val f = MaintenanceSelectorFixture()
        val binding = checkNotNull(f.authentication)
        var calls = 0
        val delayed =
            MaintenanceAuthenticationSource {
                if (++calls == 2) f.signing.now = f.signing.now.plusSeconds(300)
                binding
            }
        val raw = f.signing.headers()
        assertNull(
            TargetIdentityVerifier(
                f.connection,
                clock = f.signing.clock,
                maintenance = delayed,
            ).verifyMaintenance(raw, ""),
        )
        f.authentication = MaintenanceAuthenticationBinding(f.connection, 2, enabled = false)
        assertNull(f.verifier.verifyMaintenance(f.signing.headers(), ""))
    }

    @Test fun `identity expiry is checked again after all blocking sources`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val identity = f.identity()
            f.onOwner = { if (f.ownerReads == 3) f.signing.now = f.signing.now.plusSeconds(300) }
            assertEquals(MaintenanceSelection.Denied, f.selector().select(identity))
            f.signing.now = identity.authenticatedAt.plusSeconds(299)
            f.onOwner = {}
            assertIs<MaintenanceSelection.Selected>(f.selector().select(identity))
            f.signing.now = identity.authenticatedAt.minusSeconds(1)
            assertEquals(MaintenanceSelection.Denied, f.selector().select(identity))
        }

    @Test fun `authority and server context are checked again across reads`() =
        runBlocking<Unit> {
            for (withdraw in listOf<(MaintenanceSelectorFixture) -> Unit>(
                { it.owner = null },
                { it.owner = 8 },
                { it.selection = null },
                { it.selection = MaintenanceServerSelection(checkNotNull(it.authentication), it.reference, 2) },
            )) {
                val f = MaintenanceSelectorFixture()
                f.onRead = { count -> if (count == 1) withdraw(f) }
                assertFalse(f.selector().select(f.identity()) is MaintenanceSelection.Selected)
            }
        }

    @Test fun `branded requires independent business proof even for platform owner`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            f.branded()
            assertEquals(MaintenanceSelection.Unavailable, f.selector().select(f.identity()))
            assertEquals(0, f.reads)
            f.business = f.authority()
            val pin = assertIs<MaintenanceSelection.Selected>(f.selector().select(f.identity())).pin
            assertEquals(101L, (pin.binding.audience as ClientAudience.Client).clientId)
            assertEquals(setOf(1001L, 1002L), (pin.binding.audience as ClientAudience.Client).allowedClubIds)
            assertEquals(0, f.ownerReads)
        }

    @Test fun `foreign partial inactive and revoked business authority deny before binding read`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            f.branded()
            val invalid =
                listOf(
                    f.authority(client = 102),
                    f.authority(actor = 8),
                    f.authority(clubs = setOf(2001)),
                    f.authority(clubs = setOf(1001)),
                    f.authority(state = MaintenanceAuthorityState.INACTIVE),
                    f.authority(state = MaintenanceAuthorityState.REVOKED),
                    f.authority(version = 0),
                )
            for (proof in invalid) {
                f.business = proof
                assertEquals(MaintenanceSelection.Denied, f.selector().select(f.identity()))
            }
            assertEquals(0, f.reads)
        }

    @Test fun `changed business source version invalidates an earlier read`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            f.branded()
            f.business = f.authority()
            f.onRead = { f.business = f.authority(version = 2) }
            assertEquals(MaintenanceSelection.Stale, f.selector().select(f.identity()))
        }

    @Test fun `root incarnation generation credential and epoch mismatches never produce a pin`() =
        runBlocking<Unit> {
            val changes =
                listOf<(MaintenanceSelectorFixture) -> Unit>(
                    { it.snapshot = it.snapshot.copy(rootId = UUID.randomUUID()) },
                    { it.snapshot = it.snapshot.copy(fence = it.snapshot.fence.copy(bindingId = UUID.randomUUID())) },
                    { it.snapshot = it.snapshot.copy(fence = it.snapshot.fence.copy(generation = 2)) },
                    { it.snapshot = it.snapshot.copy(mappingEpoch = 3) },
                    { it.snapshot = it.snapshot.copy(status = BindingState.REVOKED) },
                )
            for (change in changes) {
                val f = MaintenanceSelectorFixture()
                change(f)
                assertFalse(f.selector().select(f.identity()) is MaintenanceSelection.Selected)
            }
        }

    @Test fun `binding outcomes remain separate and failure never reveals private metadata`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            val cases =
                mapOf(
                    DurableBindingRead.NotFound to MaintenanceSelection.Unavailable,
                    DurableBindingRead.MissingSelection to MaintenanceSelection.Unavailable,
                    DurableBindingRead.Inconsistent to MaintenanceSelection.Error,
                    DurableBindingRead.ReadFailed to MaintenanceSelection.Error,
                    DurableBindingRead.Rejected(BindingReadRejection.FENCE) to MaintenanceSelection.Stale,
                    DurableBindingRead.Rejected(BindingReadRejection.MAPPING_EPOCH) to MaintenanceSelection.Stale,
                    DurableBindingRead.Rejected(BindingReadRejection.CREDENTIAL_REVISION) to MaintenanceSelection.Stale,
                    DurableBindingRead.Revoked(f.snapshot.copy(status = BindingState.REVOKED)) to
                        MaintenanceSelection.Revoked,
                )
            for ((outcome, expected) in cases) {
                f.result = outcome
                val result = f.selector().select(f.identity())
                assertEquals(expected, result)
                assertFalse(result.toString().contains(f.reference.rootId.toString()))
            }
        }

    @Test fun `retired material is inspectable metadata and never a usable credential`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            f.reference = f.reference.copy(credentialRevision = 1)
            f.selection = MaintenanceServerSelection(checkNotNull(f.authentication), f.reference, 1)
            val credential =
                DurableCredentialMetadata(UUID.randomUUID(), 1, UUID.randomUUID(), CredentialMaterialState.RETIRED)
            f.snapshot = f.snapshot.copy(credential = credential)
            val pin = assertIs<MaintenanceSelection.Selected>(f.selector().select(f.identity())).pin
            assertEquals(CredentialMaterialState.RETIRED, pin.binding.credential?.state)
            assertFalse(pin.toString().contains(credential.referenceId.toString()))
        }

    @Test fun `provider exception and cancellation do not produce fallback authority`() =
        runBlocking<Unit> {
            val f = MaintenanceSelectorFixture()
            f.onRead = { throw IllegalStateException("synthetic private provider detail") }
            assertEquals(MaintenanceSelection.Error, f.selector().select(f.identity()))
            f.onRead = { throw CancellationException("synthetic cancel") }
            assertThrows<CancellationException> { f.selector().select(f.identity()) }
        }
}
