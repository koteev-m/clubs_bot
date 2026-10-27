package com.example.bot.data.client

import com.example.bot.data.security.ExposedUserRolePermissionRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException

internal abstract class ClientContextContract {
    protected lateinit var fixture: ClientContextFixture
    protected val platform = ClientAudience.Platform

    protected abstract fun createFixture(): ClientContextFixture

    @BeforeEach
    fun setupClientFixture() {
        fixture = createFixture()
        fixture.seed()
    }

    @AfterEach
    fun closeClientFixture() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun `additive migration preserves every legacy row and inserts no automatic authority`() {
        assertEquals(1, fixture.migrationCount)
        assertEquals(fixture.legacyBefore, fixture.legacySnapshot())
        assertEquals(
            listOf(listOf("1")),
            fixture.rows("SELECT COUNT(*) FROM permissions WHERE code='client.club.read'"),
        )
        assertEquals(
            listOf(listOf("0")),
            fixture.rows("SELECT COUNT(*) FROM user_role_permissions WHERE permission_code='client.club.read'"),
        )
        assertEquals(0, fixture.flyway().migrate().migrationsExecuted)
        fixture.flyway().validate()
    }

    @Test
    fun `owners staff unknown user and membership only receive exactly their own read context`() =
        runBlocking<Unit> {
            assertEquals(
                listOf(ClientContext(101, "Client A", listOf(ClientClub(1001, "A1"), ClientClub(1002, "A2")))),
                context(880061001),
            )
            assertEquals(listOf(ClientContext(102, "Client B", listOf(ClientClub(2001, "B1")))), context(880061003))
            assertEquals(listOf(ClientContext(101, "Client A", listOf(ClientClub(1001, "A1")))), context(880061002))
            assertEquals(listOf(ClientContext(101, "Client A", emptyList())), context(880061004))
            assertEquals(emptyList<ClientContext>(), context(880061099))
            assertEquals(emptyList<ClientContext>(), context(880061005))
            assertEquals(ClientClubDetail(101, 1001, "A1"), fixture.repository.club(880061001, platform, 1001))
            for (club in listOf(1002L, 2001L, 3001L, 9999L)) {
                assertNull(fixture.repository.club(880061002, platform, club))
            }
        }

    @Test
    fun `client audience restricts memberships and clubs without granting anything`() =
        runBlocking<Unit> {
            val source = mutableSetOf(1001L)
            val audience = ClientAudience.Client(101, source)
            source.add(1002L)
            assertEquals(
                listOf(ClientContext(101, "Client A", listOf(ClientClub(1001, "A1")))),
                fixture.repository.context(880061001, audience),
            )
            assertNull(fixture.repository.club(880061001, audience, 1002))
            assertNull(fixture.repository.club(880061003, audience, 2001))
            assertEquals(emptyList<ClientContext>(), fixture.repository.context(880061003, audience))
            assertEquals(
                listOf(ClientContext(101, "Client A", emptyList())),
                fixture.repository.context(880061001, ClientAudience.Client(101, emptySet())),
            )
            assertEquals(emptyList<ClientContext>(), fixture.repository.context(880061005, audience))
            assertThrows(IllegalArgumentException::class.java) { ClientAudience.Client(0, setOf(1001)) }
            assertThrows(IllegalArgumentException::class.java) { ClientAudience.Client(101, setOf(-1)) }
            assertThrows(
                UnsupportedOperationException::class.java,
            ) { (audience.allowedClubIds as MutableSet).add(1002) }
        }

    @Test
    fun `revocation is fresh at each read and one membership cannot revoke another`() =
        runBlocking<Unit> {
            assertEquals(listOf(101L, 102L), context(880061006).map { it.id })
            fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE client_id=101 AND user_id=5006")
            assertEquals(
                listOf(
                    ClientContext(101, "Client A", emptyList()),
                    ClientContext(102, "Client B", listOf(ClientClub(2001, "B1"))),
                ),
                context(880061006),
            )
            assertNull(fixture.repository.club(880061006, platform, 1001))
            fixture.execute("UPDATE client_memberships SET is_active=FALSE WHERE client_id=101 AND user_id=5006")
            assertEquals(listOf(102L), context(880061006).map { it.id })
            fixture.execute("UPDATE clients SET is_active=FALSE WHERE id=102")
            assertEquals(emptyList<ClientContext>(), context(880061006))
            assertNull(fixture.repository.club(880061003, platform, 2001))
            fixture.execute("UPDATE client_memberships SET is_active=FALSE WHERE user_id=5001")
            assertEquals(emptyList<ClientContext>(), context(880061001))
            assertNull(fixture.repository.club(880061001, platform, 1001))
        }

    @Test
    fun `default inactive states never grant access and legacy club active flag is not authority`() =
        runBlocking<Unit> {
            fixture.execute(
                "INSERT INTO clients(id,display_name) VALUES (103,'Inactive client')",
                "INSERT INTO client_memberships(client_id,user_id) VALUES (102,5001)",
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                    "(102,5001,2001,'client.club.read')",
                "UPDATE clubs SET is_active=FALSE WHERE id=1001",
            )
            assertEquals(
                listOf(listOf("inactive")),
                fixture.rows("SELECT CASE WHEN is_active THEN 'active' ELSE 'inactive' END FROM clients WHERE id=103"),
            )
            assertNull(fixture.repository.club(880061001, platform, 2001))
            fixture.execute("UPDATE client_memberships SET is_active=TRUE WHERE client_id=102 AND user_id=5001")
            assertNull(fixture.repository.club(880061001, platform, 2001))
            assertEquals(ClientClubDetail(101, 1001, "A1"), fixture.repository.club(880061001, platform, 1001))
        }

    @Test
    fun `composite foreign keys forbid cross-client membership and club combinations`() {
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(101,5003,1001,'client.club.read',TRUE)",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(102,5006,1001,'client.club.read',TRUE)",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(101,5006,2001,'client.club.read',TRUE)",
        )
        rejected("INSERT INTO client_clubs(client_id,club_id) VALUES (102,1001)")
        rejected("UPDATE client_clubs SET client_id=102 WHERE club_id=1001")
        rejected("UPDATE client_memberships SET client_id=102 WHERE user_id=5001 AND client_id=101")
        rejected("INSERT INTO client_memberships(client_id,user_id) VALUES (999,5001)")
        rejected("INSERT INTO client_memberships(client_id,user_id) VALUES (101,99999)")
        rejected("INSERT INTO client_clubs(client_id,club_id) VALUES (101,99999)")
    }

    @Test
    fun `keys positive identifiers names and exact permission allowlist are enforced`() {
        rejected("INSERT INTO clients(id,display_name) VALUES (0,'bad')")
        rejected("INSERT INTO clients(id,display_name) VALUES (103,'   ')")
        rejected("INSERT INTO clients(id,display_name) VALUES (103,NULL)")
        rejected("INSERT INTO clients(id,display_name) VALUES (101,'duplicate')")
        rejected("INSERT INTO client_memberships(client_id,user_id) VALUES (101,5001)")
        rejected("INSERT INTO client_memberships(client_id,user_id) VALUES (101,NULL)")
        rejected("INSERT INTO client_memberships(client_id,user_id) VALUES (-1,5001)")
        rejected("INSERT INTO client_clubs(client_id,club_id) VALUES (101,-1)")
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                "(101,5001,1001,'client.club.read')",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                "(101,5004,1001,'support.view')",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES (101,5004,1001,NULL)",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                "(101,5004,NULL,'client.club.read')",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                "(0,5004,1001,'client.club.read')",
        )
        rejected(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code) VALUES " +
                "(101,-1,1001,'client.club.read')",
        )
    }

    @Test
    fun `read is immutable and supports IDs above JS safe integer without narrowing`() =
        runBlocking<Unit> {
            val before = fixture.allSnapshot()
            repeat(2) {
                assertEquals(
                    listOf(
                        ClientContext(
                            9007199254740993,
                            "Large client",
                            listOf(ClientClub(9007199254740993, "Large club")),
                        ),
                    ),
                    context(880061007),
                )
                assertEquals(
                    ClientClubDetail(9007199254740993, 9007199254740993, "Large club"),
                    fixture.repository.club(880061007, platform, 9007199254740993),
                )
                context(880061099)
                fixture.repository.club(880061001, platform, 99999)
            }
            assertEquals(before, fixture.allSnapshot())
        }

    @Test
    fun `legacy roles and support permissions stay separate in both directions`() =
        runBlocking<Unit> {
            val database = Database.connect(fixture.dataSource)
            val roles = ExposedUserRoleRepository(database)
            val permissions = ExposedUserRolePermissionRepository(database)
            assertEquals(emptySet<Role>(), roles.listRoles(5001))
            assertEquals(setOf(Role.OWNER, Role.GLOBAL_ADMIN), roles.listRoles(5005))
            assertEquals(setOf(1002L), roles.listClubIdsFor(5002))
            assertTrue(permissions.hasClubPermission(5002, 1002, setOf(Role.MANAGER), PermissionCodes.SUPPORT_VIEW))
            assertFalse(permissions.hasClubPermission(5002, 1001, setOf(Role.MANAGER), PermissionCodes.SUPPORT_VIEW))
            assertFalse(
                permissions.hasClubPermission(
                    5001,
                    1001,
                    setOf(Role.MANAGER, Role.OWNER),
                    PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertNull(fixture.repository.club(880061002, platform, 1002))
            assertEquals(emptyList<ClientContext>(), context(880061005))
            assertEquals(fixture.legacyBefore, fixture.legacySnapshot())
        }

    @Test
    fun `parent deletes cascade only derived access while revoke preserves records`() {
        rejected("DELETE FROM clients WHERE id=101")
        fixture.execute("UPDATE client_club_grants SET is_active=FALSE WHERE user_id=5001")
        assertEquals(listOf(listOf("2")), fixture.rows("SELECT COUNT(*) FROM client_club_grants WHERE user_id=5001"))
        fixture.execute("DELETE FROM users WHERE id=5001")
        assertEquals(listOf(listOf("0")), fixture.rows("SELECT COUNT(*) FROM client_memberships WHERE user_id=5001"))
        assertEquals(listOf(listOf("0")), fixture.rows("SELECT COUNT(*) FROM client_club_grants WHERE user_id=5001"))
        fixture.execute("DELETE FROM clubs WHERE id=2001")
        assertEquals(listOf(listOf("0")), fixture.rows("SELECT COUNT(*) FROM client_clubs WHERE club_id=2001"))
        assertEquals(listOf(listOf("0")), fixture.rows("SELECT COUNT(*) FROM client_club_grants WHERE club_id=2001"))
        assertEquals(
            listOf(listOf("1")),
            fixture.rows("SELECT COUNT(*) FROM client_memberships WHERE client_id=102 AND user_id=5003"),
        )
    }

    private suspend fun context(telegramId: Long): List<ClientContext> =
        fixture.repository.context(telegramId, platform)

    private fun rejected(sql: String) {
        assertThrows(SQLException::class.java, { fixture.execute(sql) }, sql)
    }
}
