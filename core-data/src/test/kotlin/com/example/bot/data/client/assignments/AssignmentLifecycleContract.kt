package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CALENDAR_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.DELEGATION_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FINANCE_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FOREIGN_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.LEGACY
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OWNER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.PLATFORM
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.REASON
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.SECOND_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.onboarding.digest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** Persistence, revocation and transaction cases; also inherits all permission cases. */
internal abstract class AssignmentLifecycleContract : AssignmentPermissionContract() {
    @Test
    fun `terminal higher role history is preserved when current lower staff is disabled and replayed`() =
        scenario {
            admit(role = StaffRole.CLUB_MANAGER, actor = OWNER)
            grant(issue(StaffRole.CLUB_MANAGER), OWNER)
            revoke(FLOOR_SOURCE, OWNER)
            grant(issue(sourceId = SECOND_SOURCE))
            val command = AssignmentCommand.Disable(targetScope, requireNotNull(fence()), REASON, true)
            val key = commandKey()
            val first = repository.mutate(evidence(MANAGER), command, key)
            assertTrue(first.current.contributions.isEmpty())
            assertEquals(
                setOf("REVOKED"),
                first.current.sources.values
                    .toSet(),
            )
            val before = snapshot()
            val replay = repository.mutate(evidence(MANAGER), command, key)
            assertTrue(replay.replayed)
            assertEquals(before, snapshot())
            assertEquals("2", database.scalar("SELECT version FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"))
        }

    @Test
    fun `full disable fences all staff sources and preserves unrelated current authority`() =
        scenario {
            val legacy = legacySnapshot()
            admit()
            grant()
            grant(issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE))
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            val before = requireNotNull(fence())
            repository.mutate(
                evidence(MANAGER),
                AssignmentCommand.Disable(targetScope, before, REASON, true),
                commandKey(),
            )
            val after = requireNotNull(fence())
            assertTrue(after.epoch > before.epoch)
            assertTrue(after.version > before.version)
            assertTrue(view().contributions.isEmpty())
            assertEquals(
                "0",
                database.scalar(
                    "SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET AND status='ACTIVE'",
                ),
            )
            assertEquals(
                "0",
                database.scalar(
                    "SELECT COUNT(*) FROM client_permission_delegations WHERE user_id=$TARGET AND status='ACTIVE'",
                ),
            )
            assertEquals(legacy, legacySnapshot())
        }

    @Test
    fun `readmission uses new epochs and source ids and never revives tombstones`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            repository.mutate(
                evidence(MANAGER),
                AssignmentCommand.Disable(targetScope, requireNotNull(fence()), REASON, true),
                commandKey(),
            )
            val disabled = requireNotNull(fence())
            admit(expected = disabled)
            assertTrue(requireNotNull(fence()).epoch > disabled.epoch)
            assertTrue(view().contributions.isEmpty())
            val beforeReuse = snapshot()
            rejected { grant(issue().copy(regrantAcknowledgement = floorPolicy.permissions)) }
            assertEquals(beforeReuse, snapshot())
            grant(issue(sourceId = SECOND_SOURCE).copy(regrantAcknowledgement = floorPolicy.permissions))
            assertTrue(view().permits(FLOOR_READ))
            assertFalse(view().permits(CALENDAR_WRITE))
            assertEquals(
                "REVOKED",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
            assertEquals(
                "REVOKED",
                database.scalar("SELECT status FROM client_permission_delegations WHERE id='$DELEGATION_SOURCE'"),
            )
        }

    @Test
    fun `old unclaimed target invitation cannot reenter after disable even with fresh claim epoch`() =
        scenario {
            admit()
            grant()
            val beforeDisable = requireNotNull(fence())
            // A future verifier could bind a later claim to the current generation;
            // the independently retained issuance watermark must still reject this old link.
            val oldLink = confirmation(expectedEpoch = beforeDisable.epoch + 1)
            repository.mutate(
                evidence(MANAGER),
                AssignmentCommand.Disable(targetScope, beforeDisable, REASON, true),
                commandKey(),
            )
            val disabled = requireNotNull(fence())
            val beforeReplay = snapshot()
            rejected {
                repository.mutate(
                    evidence(MANAGER),
                    AssignmentCommand.Admit(targetScope, disabled, StaffRole.FLOOR_MANAGER, oldLink, REASON, true),
                    commandKey(),
                )
            }
            assertEquals(beforeReplay, snapshot())
            admit(expected = disabled)
            assertTrue(requireNotNull(fence()).epoch > disabled.epoch)
            assertTrue(view().contributions.isEmpty())
        }

    @Test
    fun `regrant requires exact acknowledgement and retains revoked rows`() =
        scenario {
            admit()
            grant()
            revoke(FLOOR_SOURCE)
            val before = snapshot()
            rejected { grant(issue(sourceId = SECOND_SOURCE)) }
            rejected {
                grant(
                    issue(sourceId = SECOND_SOURCE).copy(
                        regrantAcknowledgement =
                            floorPolicy.permissions + "unrelated.permission",
                    ),
                )
            }
            assertEquals(before, snapshot())
            grant(issue(sourceId = SECOND_SOURCE).copy(regrantAcknowledgement = floorPolicy.permissions))
            assertEquals("2", database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET"))
            assertEquals(
                "REVOKED",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
        }

    @Test
    fun `membership revoke restore cycle cannot reactivate existing sources`() =
        scenario {
            admit()
            grant()
            database.execute(
                "UPDATE client_memberships SET is_active=FALSE,version=version+1 " +
                    "WHERE client_id=$CLIENT AND user_id=$TARGET",
            )
            rejected { view() }
            database.execute(
                "UPDATE client_memberships SET is_active=TRUE,version=version+1 " +
                    "WHERE client_id=$CLIENT AND user_id=$TARGET",
            )
            assertTrue(view().contributions.isEmpty())
            val before = snapshot()
            rejected { grant(issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)) }
            assertEquals(before, snapshot())
        }

    @Test
    fun `fresh admission can create absent membership but never restores an inactive one`() =
        scenario {
            database.execute("DELETE FROM client_memberships WHERE client_id=$CLIENT AND user_id=$TARGET")
            admit()
            assertEquals(
                "1",
                database.scalar(
                    "SELECT COUNT(*) FROM client_memberships WHERE client_id=$CLIENT " +
                        "AND user_id=$TARGET AND is_active=TRUE",
                ),
            )
            assertEquals(
                "0",
                database.scalar(
                    "SELECT COUNT(*) FROM client_membership_grants WHERE client_id=$CLIENT AND user_id=$TARGET",
                ),
            )
            val other = StaffScope(CLIENT, CLUB, AssignmentLifecycleFixture.LEGACY)
            database.execute(
                "UPDATE client_memberships SET is_active=FALSE,version=version+1 " +
                    "WHERE client_id=$CLIENT AND user_id=${other.userId}",
            )
            val before = snapshot()
            rejected { admit(scope = other) }
            assertEquals(before, snapshot())
        }

    @Test
    fun `foreign source and neighbouring club cannot be substituted in a command`() =
        scenario {
            admit()
            grant()
            val before = snapshot()
            for (scope in listOf(
                StaffScope(CLIENT, OTHER_CLUB, TARGET),
                StaffScope(OTHER_CLIENT, FOREIGN_CLUB, TARGET),
            )) {
                rejected {
                    repository.mutate(
                        evidence(MANAGER),
                        AssignmentCommand.RevokeRole(scope, AdmissionVersion(1, 1), FLOOR_SOURCE, 1, REASON, true),
                        commandKey(),
                    )
                }
            }
            rejected { repository.effective(evidence(TARGET), CLIENT, FOREIGN_CLUB) }
            assertEquals(before, snapshot())
        }

    @Test
    fun `owner platform and self subjects cannot be granted ordinary staff authority`() =
        scenario {
            val before = snapshot()
            for (subject in listOf(OWNER, PLATFORM, MANAGER)) {
                rejected { admit(scope = StaffScope(CLIENT, CLUB, subject), role = StaffRole.FLOOR_MANAGER) }
            }
            assertEquals(before, snapshot())
        }

    @Test
    fun `receipt replay after lost response is durable and cannot execute a second grant`() =
        scenario {
            admit()
            val command = issue()
            val key = commandKey()
            val first = repository.mutate(evidence(MANAGER), command, key)
            val beforeReplay = snapshot()
            val repeated = repository().mutate(evidence(MANAGER), command, key)
            assertFalse(first.replayed)
            assertTrue(repeated.replayed)
            assertEquals(first.current, repeated.current)
            assertEquals(beforeReplay, snapshot())
            rejected { repository.mutate(evidence(MANAGER), command.copy(sourceId = SECOND_SOURCE), key) }
            assertEquals(beforeReplay, snapshot())
        }

    @Test
    fun `read only resolution never reserves a key and returns current revoked state with actor fence`() =
        scenario {
            admit()
            val actorExpiry = clock.instant().plusSeconds(45)
            database.execute("UPDATE client_role_assignments SET expires_at='$actorExpiry' WHERE id='$MANAGER_SOURCE'")
            val command = issue()
            val key = commandKey()
            val beforeResolve = snapshot()
            val unknown = repository.resolve(evidence(MANAGER), command, key)
            assertFalse(unknown.recorded)
            assertTrue(unknown.current.contributions.isEmpty())
            assertEquals(beforeResolve, snapshot())
            val actorFence = unknown.actorFence
            assertEquals(managerScope, actorFence.scope)
            assertNotEquals(unknown.current.scope, actorFence.scope)
            assertEquals(requireNotNull(fence(managerScope)), actorFence.admission)
            assertNotEquals(unknown.current.admission, actorFence.admission)
            assertEquals(
                requireNotNull(
                    database.scalar(
                        "SELECT version FROM client_memberships WHERE client_id=$CLIENT AND user_id=$MANAGER",
                    ),
                ).toLong(),
                actorFence.membershipVersion,
            )
            assertEquals(
                "ASSIGNMENT:$MANAGER_SOURCE:${sourceVersion(
                    MANAGER_SOURCE,
                )}:${requireNotNull(fence(managerScope)).epoch}",
                actorFence.authorityReference,
            )
            assertEquals(digest("platform"), actorFence.connectionKeyHash)
            assertEquals(actorExpiry, actorFence.validUntil)
            assertEquals(actorExpiry, unknown.current.validUntil)
            val issued = repository.mutate(evidence(MANAGER), command, key)
            assertFalse(issued.replayed)
            assertTrue(issued.current.permits(FLOOR_READ))
            assertEquals(actorFence, issued.actorFence)
            assertEquals(actorExpiry, issued.current.validUntil)
            revoke(FLOOR_SOURCE)
            val beforeRecordedResolve = snapshot()
            val recorded = repository().resolve(evidence(MANAGER), command, key)
            assertTrue(recorded.recorded)
            assertEquals("REVOKED", recorded.current.sources[FLOOR_SOURCE])
            assertFalse(recorded.current.permits(FLOOR_READ))
            assertEquals(managerScope, recorded.actorFence.scope)
            assertEquals(actorExpiry, recorded.current.validUntil)
            assertEquals(beforeRecordedResolve, snapshot())
        }

    @Test
    fun `revoked actor cannot resolve or replay an old receipt or create new command history`() =
        scenario {
            admit()
            val command = issue()
            val key = commandKey()
            repository.mutate(evidence(MANAGER), command, key)
            assertTrue(repository.resolve(evidence(MANAGER), command, key).recorded)
            revoke(MANAGER_SOURCE, OWNER, managerScope)
            val afterActorRevoke = snapshot()
            rejected { repository().resolve(evidence(MANAGER), command, key) }
            assertEquals(afterActorRevoke, snapshot())
            rejected { repository().mutate(evidence(MANAGER), command, key) }
            assertEquals(afterActorRevoke, snapshot())
            rejected { repository().resolve(evidence(MANAGER), command, commandKey()) }
            assertEquals(afterActorRevoke, snapshot())
        }

    @Test
    fun `old command replay reads revoked state and never returns its previous access`() =
        scenario {
            admit()
            val command = issue()
            val key = commandKey()
            repository.mutate(evidence(MANAGER), command, key)
            revoke(FLOOR_SOURCE)
            val beforeReplay = snapshot()
            val replay = repository().mutate(evidence(MANAGER), command, key)
            assertTrue(replay.replayed)
            assertFalse(replay.current.permits(FLOOR_READ))
            assertEquals(beforeReplay, snapshot())
            revoke(MANAGER_SOURCE, OWNER, managerScope)
            val revokedIssuer = snapshot()
            rejected { repository().mutate(evidence(MANAGER), command, key) }
            assertEquals(revokedIssuer, snapshot())
        }

    @Test
    fun `stale admission marker cannot append a role after another committed change`() =
        scenario {
            admit()
            val stale = issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
            grant()
            val before = snapshot()
            rejected { grant(stale) }
            assertEquals(before, snapshot())
            assertFalse(view().permits(ENTRY_WRITE))
        }

    @Test
    fun `audit and durable receipt failures roll back every source fence and history change`() =
        scenario {
            admit()
            for (table in listOf("audit_log", "client_commands")) {
                val before = snapshot()
                val failing = repository(source = failingAssignmentInsert(database.dataSource, table))
                try {
                    failing.mutate(evidence(MANAGER), issue(), commandKey())
                    throw AssertionError("Synthetic $table failure was not observed")
                } catch (failure: SQLException) {
                    assertEquals("HY000", failure.sqlState)
                }
                assertEquals(before, snapshot())
            }
        }

    @Test
    fun `audit completion failure after source write rolls back the source fence receipt and audit`() =
        scenario {
            admit()
            val before = snapshot()
            var sourceWasWritten = false
            var admissionWasBumped = false
            val admissionBefore = requireNotNull(fence())
            val failing =
                repository(
                    source =
                        failingAssignmentStatement(
                            database.dataSource,
                            "UPDATE audit_log SET metadata_json",
                        ) { connection ->
                            connection.createStatement().use { statement ->
                                statement
                                    .executeQuery(
                                        "SELECT COUNT(*) FROM client_role_assignments WHERE id='$FLOOR_SOURCE'",
                                    ).use { rows ->
                                        check(rows.next())
                                        sourceWasWritten = rows.getLong(1) == 1L
                                    }
                                statement
                                    .executeQuery(
                                        "SELECT version FROM client_club_access_state " +
                                            "WHERE client_id=$CLIENT AND club_id=$CLUB AND user_id=$TARGET",
                                    ).use { rows ->
                                        check(rows.next())
                                        admissionWasBumped = rows.getLong(1) > admissionBefore.version
                                    }
                            }
                        },
                )
            try {
                failing.mutate(evidence(MANAGER), issue(), commandKey())
                throw AssertionError("Synthetic audit completion failure was not observed")
            } catch (failure: SQLException) {
                assertEquals("HY000", failure.sqlState)
            }
            assertTrue(sourceWasWritten)
            assertTrue(admissionWasBumped)
            assertEquals(before, snapshot())
        }

    @Test
    fun `trusted role policy cannot issue an unregistered permission code`() =
        scenario {
            admit()
            val unregistered = floorPolicy.copy(permissions = floorPolicy.permissions + "synthetic.unregistered")
            val trusted = AssignmentPolicyCatalog(listOf(unregistered, managerPolicy), listOf(delegationPolicy))
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM permissions WHERE code='synthetic.unregistered'"))
            val before = snapshot()
            rejected {
                repository(policies = trusted).mutate(
                    evidence(MANAGER),
                    issue().copy(policy = unregistered.reference),
                    commandKey(),
                )
            }
            assertEquals(before, snapshot())
            assertTrue(view().contributions.isEmpty())
        }

    @Test
    fun `policy drift and expiry cannot expand a stored assignment`() =
        scenario {
            admit()
            grant(issue().copy(expiresAt = clock.instant().plusSeconds(60)))
            val before = snapshot()
            val drifted = floorPolicy.copy(permissions = floorPolicy.permissions + FINANCE_READ)
            val changed = AssignmentPolicyCatalog(listOf(drifted, managerPolicy), listOf(delegationPolicy))
            assertTrue(repository(policies = changed).effective(evidence(TARGET), CLIENT, CLUB).contributions.isEmpty())
            assertEquals(before, snapshot())
            clock.current = clock.current.plusSeconds(60)
            assertTrue(view().contributions.isEmpty())
            assertEquals(before, snapshot())
        }

    @Test
    fun `financial selector cannot leak into a neighbouring shift or club`() =
        scenario {
            admit(role = StaffRole.FINANCE_MANAGER)
            grant(
                issue(
                    StaffRole.FINANCE_MANAGER,
                ).copy(selector = PermissionSelector(SelectorKind.SHIFT_SET, listOf(7001))),
            )
            val own = view()
            assertTrue(own.permits(FINANCE_READ, 7001))
            assertFalse(own.permits(FINANCE_READ, 7002))
            assertFalse(own.permits(FINANCE_READ))
            assertTrue(repository.effective(evidence(TARGET), CLIENT, OTHER_CLUB).contributions.isEmpty())
            assertTrue(repository.effective(evidence(TARGET), OTHER_CLIENT, FOREIGN_CLUB).contributions.isEmpty())
        }

    @Test
    fun `uninstalled providers and unknown identity deny without provisioning or grants`() =
        scenario {
            val before = snapshot()
            rejected { JdbcRoleAssignmentFoundation(database.dataSource).effective(evidence(TARGET), CLIENT, CLUB) }
            rejected { repository.effective("unsigned:$TARGET", CLIENT, CLUB) }
            assertEquals(before, snapshot())
        }
}
