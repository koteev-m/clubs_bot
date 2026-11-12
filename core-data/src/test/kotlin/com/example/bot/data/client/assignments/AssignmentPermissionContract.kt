package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CALENDAR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CALENDAR_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.DELEGATION_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FIFTH_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FINANCE_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FOREIGN_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FOURTH_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.LEGACY
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.NIGHT_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OWNER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.PLATFORM
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.REASON
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.SECOND_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.THIRD_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.telegram
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingRead
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Permission and hierarchy cases inherited by both database implementations. */
internal abstract class AssignmentPermissionContract : AssignmentContractBase() {
    @Test
    fun `admission creates an empty shadow scope and never an operational grant`() =
        scenario {
            val legacy = legacySnapshot()
            val admitted = admit()
            assertTrue(admitted.current.contributions.isEmpty())
            assertEquals("SHADOW", database.scalar("SELECT mode FROM client_club_access_state WHERE user_id=$TARGET"))
            assertEquals("ACTIVE", database.scalar("SELECT status FROM client_club_access_state WHERE user_id=$TARGET"))
            assertEquals(legacy, legacySnapshot())
        }

    @Test
    fun `current onboarding authorization ignores isolated roles and configuration delegations`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            assertTrue(view().permits(CALENDAR_READ))
            assertTrue(view().permits(CALENDAR_WRITE))
            val before = snapshot()
            val current = JdbcClientOnboardingRepository(database.dataSource, telegram(PLATFORM), clock)
            val actor = OnboardingActor(telegram(TARGET), "platform")
            rejected { current.read(actor, OnboardingRead.CLUB_CALENDAR_GET, CLUB) }
            rejected { current.read(actor, OnboardingRead.CLUB_PROFILE_GET, CLUB) }
            assertEquals(before, snapshot())
        }

    @Test
    fun `promoter has exactly three explicit club bindings out of five in one business`() =
        scenario {
            prepareManager(OTHER_CLUB)
            prepareManager(THIRD_CLUB)
            val legacy = legacySnapshot()
            val selected = listOf(CLUB, OTHER_CLUB, THIRD_CLUB)
            for (club in selected) {
                val scope = StaffScope(CLIENT, club, TARGET)
                admit(scope, StaffRole.PROMOTER)
                val source = UUID.nameUUIDFromBytes("synthetic-promoter:$club".toByteArray())
                grant(issue(StaffRole.PROMOTER, source, scope))
                val own = view(scope)
                assertTrue(own.permits("booking.promoter.create"))
                assertTrue(own.permits("booking.promoter.own.read"))
                assertFalse(own.permits("booking.all.read"))
                assertFalse(own.permits(FINANCE_READ))
                assertEquals(setOf(source), own.contributions.map { it.sourceId }.toSet())
            }
            val before = snapshot()
            for (club in listOf(FOURTH_CLUB, FIFTH_CLUB)) {
                val scope = StaffScope(CLIENT, club, TARGET)
                assertTrue(view(scope).contributions.isEmpty())
                rejected { admit(scope, StaffRole.PROMOTER) }
                rejected {
                    grant(
                        AssignmentCommand.IssueRole(
                            scope,
                            AdmissionVersion(1, 1),
                            SECOND_SOURCE,
                            StaffRole.PROMOTER,
                            promoterPolicy.reference,
                            reason = REASON,
                            confirmed = true,
                        ),
                    )
                }
            }
            assertTrue(view(StaffScope(OTHER_CLIENT, FOREIGN_CLUB, TARGET)).contributions.isEmpty())
            assertEquals(before, snapshot())
            assertEquals(legacy, legacySnapshot())
        }

    @Test
    fun `network manager can appoint and revoke a club manager only in its exact assigned club`() =
        scenario {
            val networkPolicy =
                RolePolicy(
                    StaffRole.NETWORK_MANAGER,
                    1,
                    setOf(
                        "client.staff.assign.club_manager",
                        "client.staff.revoke.club_manager",
                        // Even a server-owned code cannot bypass hierarchy or self protection.
                        "client.staff.assign.network_manager",
                        "client.staff.revoke.network_manager",
                    ),
                )
            networkPolicy.permissions
                .filter { database.scalar("SELECT COUNT(*) FROM permissions WHERE code='$it'") == "0" }
                .forEach { database.execute("INSERT INTO permissions(code) VALUES ('$it')") }
            val trusted =
                AssignmentPolicyCatalog(
                    listOf(floorPolicy, entryPolicy, financePolicy, managerPolicy, promoterPolicy, networkPolicy),
                    listOf(delegationPolicy),
                )
            val current = repository(policies = trusted)
            val networkScope = StaffScope(CLIENT, CLUB, LEGACY)
            val networkAdmission = admit(networkScope, StaffRole.NETWORK_MANAGER, OWNER)
            current.mutate(
                evidence(OWNER),
                AssignmentCommand.IssueRole(
                    networkScope,
                    requireNotNull(networkAdmission.current.admission),
                    SECOND_SOURCE,
                    StaffRole.NETWORK_MANAGER,
                    networkPolicy.reference,
                    reason = REASON,
                    confirmed = true,
                ),
                commandKey(),
            )
            val networkManagement = current.effective(evidence(LEGACY), CLIENT, CLUB)
            assertTrue(networkManagement.permits("client.staff.assign.club_manager"))
            assertFalse(networkManagement.permits(CALENDAR_WRITE))
            val admissionConfirmation = confirmation(targetScope, StaffRole.CLUB_MANAGER, LEGACY)
            val admitted =
                current.mutate(
                    evidence(LEGACY),
                    AssignmentCommand.Admit(
                        targetScope,
                        null,
                        StaffRole.CLUB_MANAGER,
                        admissionConfirmation,
                        REASON,
                        true,
                    ),
                    commandKey(),
                )
            current.mutate(
                evidence(LEGACY),
                AssignmentCommand.IssueRole(
                    targetScope,
                    requireNotNull(admitted.current.admission),
                    FLOOR_SOURCE,
                    StaffRole.CLUB_MANAGER,
                    managerPolicy.reference,
                    reason = REASON,
                    confirmed = true,
                ),
                commandKey(),
            )
            assertTrue(current.effective(evidence(TARGET), CLIENT, CLUB).permits(CALENDAR_WRITE))
            val beforeDenials = snapshot()
            rejected {
                current.mutate(
                    evidence(LEGACY),
                    issue(StaffRole.CLUB_MANAGER, ENTRY_SOURCE, networkScope),
                    commandKey(),
                )
            }
            rejected {
                current.mutate(
                    evidence(LEGACY),
                    issue(StaffRole.CLUB_MANAGER, ENTRY_SOURCE).copy(
                        role = StaffRole.NETWORK_MANAGER,
                        policy = networkPolicy.reference,
                    ),
                    commandKey(),
                )
            }
            val otherScope = StaffScope(CLIENT, OTHER_CLUB, TARGET)
            rejected {
                current.mutate(
                    evidence(LEGACY),
                    AssignmentCommand.Admit(
                        otherScope,
                        null,
                        StaffRole.CLUB_MANAGER,
                        confirmation(otherScope, StaffRole.CLUB_MANAGER, LEGACY),
                        REASON,
                        true,
                    ),
                    commandKey(),
                )
            }
            assertEquals(beforeDenials, snapshot())
            assertTrue(current.effective(evidence(TARGET), CLIENT, OTHER_CLUB).contributions.isEmpty())
            current.mutate(
                evidence(LEGACY),
                AssignmentCommand.RevokeRole(
                    targetScope,
                    requireNotNull(fence()),
                    FLOOR_SOURCE,
                    sourceVersion(FLOOR_SOURCE),
                    REASON,
                    true,
                ),
                commandKey(),
            )
            assertTrue(current.effective(evidence(TARGET), CLIENT, CLUB).contributions.isEmpty())
            assertEquals(
                "REVOKED",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
        }

    @Test
    fun `one issued role persists exact provenance across repository instances`() =
        scenario {
            admit()
            val result = grant()
            assertTrue(result.current.permits(FLOOR_READ))
            assertEquals(
                listOf(FLOOR_SOURCE),
                result.current.contributions
                    .filter {
                        it.permission == FLOOR_READ
                    }.map { it.sourceId },
            )
            assertTrue(result.current.contributions.all { it.kind == ContributionKind.ROLE })
            assertEquals(result.current, repository().effective(evidence(TARGET), CLIENT, CLUB))
            assertEquals(
                floorPolicy.reference.hash,
                database.scalar("SELECT policy_hash FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
        }

    @Test
    fun `revoking one of two roles removes only that source and its exclusive permissions`() =
        scenario {
            admit()
            grant()
            grant(issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE))
            val before = view()
            assertEquals(
                setOf(FLOOR_SOURCE, ENTRY_SOURCE),
                before.contributions
                    .filter {
                        it.permission == NIGHT_READ
                    }.map { it.sourceId }
                    .toSet(),
            )
            revoke(FLOOR_SOURCE)
            val after = view()
            assertTrue(after.permits(NIGHT_READ))
            assertTrue(after.permits(ENTRY_WRITE))
            assertFalse(after.permits(FLOOR_READ))
            assertEquals(
                listOf(ENTRY_SOURCE),
                after.contributions.filter { it.permission == NIGHT_READ }.map { it.sourceId },
            )
            assertEquals(
                "REVOKED",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
            revoke(ENTRY_SOURCE)
            assertTrue(view().contributions.isEmpty())
        }

    @Test
    fun `two assignments of the same role retain independent source identities`() =
        scenario {
            admit()
            grant()
            grant(issue(sourceId = SECOND_SOURCE))
            revoke(FLOOR_SOURCE)
            val result = view()
            assertTrue(result.permits(FLOOR_READ))
            assertEquals(
                listOf(SECOND_SOURCE),
                result.contributions.filter { it.permission == FLOOR_READ }.map { it.sourceId },
            )
            assertEquals(
                "ACTIVE",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$SECOND_SOURCE'"),
            )
        }

    @Test
    fun `delegation adds only section read and write and its revoke preserves the role`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            val before = view()
            assertTrue(before.permits(CALENDAR_READ))
            assertTrue(before.permits(CALENDAR_WRITE))
            assertFalse(before.permits("client.club.read"))
            assertFalse(before.permits("client.club.team.manage"))
            repository.mutate(
                evidence(MANAGER),
                AssignmentCommand.RevokeDelegation(
                    targetScope,
                    requireNotNull(fence()),
                    DELEGATION_SOURCE,
                    1,
                    REASON,
                    true,
                ),
                commandKey(),
            )
            val after = view()
            assertFalse(after.permits(CALENDAR_READ))
            assertFalse(after.permits(CALENDAR_WRITE))
            assertTrue(after.permits(FLOOR_READ))
            assertEquals(
                "ACTIVE",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
        }

    @Test
    fun `two delegation sources do not collapse into one mutable permission`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            repository.mutate(evidence(MANAGER), delegation(SECOND_SOURCE), commandKey())
            repository.mutate(
                evidence(MANAGER),
                AssignmentCommand.RevokeDelegation(
                    targetScope,
                    requireNotNull(fence()),
                    DELEGATION_SOURCE,
                    1,
                    REASON,
                    true,
                ),
                commandKey(),
            )
            val after = view()
            assertTrue(after.permits(CALENDAR_WRITE))
            assertEquals(
                listOf(SECOND_SOURCE),
                after.contributions.filter { it.permission == CALENDAR_WRITE }.map { it.sourceId },
            )
        }

    @Test
    fun `business ownership alone does not authorize revoking a configuration delegation`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            val before = snapshot()
            rejected {
                repository.mutate(
                    evidence(OWNER),
                    AssignmentCommand.RevokeDelegation(
                        targetScope,
                        requireNotNull(fence()),
                        DELEGATION_SOURCE,
                        1,
                        REASON,
                        true,
                    ),
                    commandKey(),
                )
            }
            assertEquals(before, snapshot())
            assertTrue(view().permits(CALENDAR_WRITE))
        }

    @Test
    fun `issuer revoke invalidates delegation without removing recipient role`() =
        scenario {
            admit()
            grant()
            repository.mutate(evidence(MANAGER), delegation(), commandKey())
            val before = requireNotNull(view().admission)
            revoke(MANAGER_SOURCE, OWNER, managerScope)
            val after = view()
            assertTrue(after.permits(FLOOR_READ))
            assertFalse(after.permits(CALENDAR_WRITE))
            assertNotEquals(before, after.admission)
            val audit =
                database.scalar(
                    "SELECT metadata_json FROM audit_log WHERE id=(SELECT last_audit_id " +
                        "FROM client_permission_delegations WHERE id='$DELEGATION_SOURCE')",
                )
            assertTrue(requireNotNull(audit).contains(DELEGATION_SOURCE.toString()))
            assertTrue(audit.contains(MANAGER_SOURCE.toString()))
            assertEquals(
                "ACTIVE",
                database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
            )
        }
}
