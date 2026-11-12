package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

internal abstract class MappingEpochContract {
    private lateinit var fixture: BindingTestFixture

    protected abstract fun database(): AssignmentTestDatabase

    @BeforeEach fun prepare() {
        fixture = BindingTestFixture(database())
    }

    @AfterEach fun close() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun `first and second mappings change only the owning client epoch`() {
        val f = fixture
        assertEquals(3L, f.epoch())
        assertEquals(2L, f.epoch(102))
        f.database.execute(
            "INSERT INTO clients(id,display_name,is_active) VALUES (103,'New',TRUE)",
            "INSERT INTO clubs(id,name,timezone) VALUES (3001,'New A','UTC'),(3002,'New B','UTC')",
        )
        assertEquals(1L, f.epoch(103))
        f.database.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (103,3001)")
        assertEquals(2L, f.epoch(103))
        f.database.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (103,3002)")
        assertEquals(3L, f.epoch(103))
        assertEquals(3L, f.epoch())
        assertEquals(2L, f.epoch(102))
    }

    @Test fun `delete reinsert with identical timestamp cannot reuse mapping epoch`() {
        val f = fixture
        val before = f.database.rows("SELECT * FROM client_clubs WHERE club_id=1001")
        val epoch = f.epoch()
        f.database.execute(
            "DELETE FROM client_clubs WHERE club_id=1001",
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1001,'2026-11-11T00:00:00Z')",
        )
        assertEquals(before, f.database.rows("SELECT * FROM client_clubs WHERE club_id=1001"))
        assertEquals(epoch + 2, f.epoch())
        assertEquals(2L, f.epoch(102))
    }

    @Test fun `mapping update fences both clients and failed foreign update rolls back`() {
        val f = fixture
        f.database.execute("UPDATE client_clubs SET client_id=102 WHERE club_id=1001")
        assertEquals(4L, f.epoch())
        assertEquals(3L, f.epoch(102))
        val before = listOf(f.epoch(), f.epoch(102))
        assertThrows<SQLException> { f.database.execute("UPDATE client_clubs SET client_id=999 WHERE club_id=1001") }
        assertEquals(before, listOf(f.epoch(), f.epoch(102)))
    }

    @Test fun `client activation archive and lifecycle changes advance the same epoch`() {
        val f = fixture
        f.database.execute("UPDATE clients SET is_active=FALSE WHERE id=101")
        assertEquals(4L, f.epoch())
        f.database.execute("UPDATE clients SET is_active=FALSE WHERE id=101")
        assertEquals(4L, f.epoch())
        f.database.execute(
            "UPDATE clients SET is_active=TRUE WHERE id=101",
            "UPDATE clients SET archived_at=CURRENT_TIMESTAMP,lifecycle_version=lifecycle_version+1 WHERE id=101",
        )
        assertEquals(6L, f.epoch())
        f.database.execute("UPDATE clients SET archived_at=NULL,lifecycle_version=lifecycle_version+1 WHERE id=101")
        assertEquals(7L, f.epoch())
    }

    @Test fun `publication names limits membership and grants are not audience topology`() {
        val f = fixture
        val before = f.epoch()
        f.database.execute(
            "UPDATE clubs SET name='Renamed',is_active=TRUE WHERE id=1001",
            "UPDATE clients SET display_name='Renamed',limit_version=limit_version+1 WHERE id=101",
            "UPDATE client_clubs SET created_at='2026-11-12T00:00:00Z' WHERE club_id=1001",
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (101,78001,TRUE)",
            "UPDATE client_memberships SET is_active=FALSE,version=version+1 WHERE client_id=101",
        )
        assertEquals(before, f.epoch())
    }

    @Test fun `SQL club delete cascade bumps epoch atomically`() {
        val f = fixture
        f.database.execute("DELETE FROM clubs WHERE id=1001")
        assertEquals("0", f.database.scalar("SELECT COUNT(*) FROM client_clubs WHERE club_id=1001"))
        assertEquals(4L, f.epoch())
        assertEquals(2L, f.epoch(102))
    }

    @Test fun `rollback restores mappings status and epoch together`() {
        val f = fixture
        val before = f.epoch()
        f.database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute("DELETE FROM client_clubs WHERE club_id=1001")
                it.execute("UPDATE clients SET is_active=FALSE WHERE id=101")
            }
            connection.rollback()
        }
        assertEquals(before, f.epoch())
        assertEquals("1", f.database.scalar("SELECT COUNT(*) FROM client_clubs WHERE club_id=1001"))
        assertEquals("1", f.database.scalar("SELECT COUNT(*) FROM clients WHERE id=101 AND is_active=TRUE"))
    }

    @Test fun `direct counter rewind and ordinary mapping truncate fail before publication`() {
        val f = fixture
        val before = f.epoch()
        assertThrows<SQLException> { f.database.execute("UPDATE clients SET target_mapping_epoch=1 WHERE id=101") }
        assertThrows<SQLException> { f.database.execute("TRUNCATE TABLE client_clubs") }
        assertEquals(before, f.epoch())
        assertEquals("3", f.database.scalar("SELECT COUNT(*) FROM client_clubs"))
    }

    @Test fun `physical client ID reuse retains durable epoch highwater without blocking deletion`() {
        val f = fixture
        val before =
            f.database.rows(
                "SELECT id,is_active,archived_at,lifecycle_version,target_mapping_epoch FROM clients WHERE id=101",
            )
        val mappings = f.database.rows("SELECT * FROM client_clubs WHERE client_id=101 ORDER BY club_id")
        f.database.execute(
            "DELETE FROM client_clubs WHERE client_id=101",
            "DELETE FROM clients WHERE id=101",
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Recreated',TRUE)",
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1001,'2026-11-11T00:00:00Z'),(101,1002,'2026-11-11T00:00:00Z')",
        )
        val after =
            f.database.rows(
                "SELECT id,is_active,archived_at,lifecycle_version,target_mapping_epoch FROM clients WHERE id=101",
            )
        assertEquals(before.single().dropLast(1), after.single().dropLast(1))
        assertTrue(checkNotNull(after.single().last()).toLong() > checkNotNull(before.single().last()).toLong())
        assertEquals(
            f.epoch().toString(),
            f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=101"),
        )
        assertEquals(mappings, f.database.rows("SELECT * FROM client_clubs WHERE client_id=101 ORDER BY club_id"))
        assertEquals("0", f.database.scalar("SELECT COUNT(*) FROM target_bot_connections"))
    }

    @Test fun `unreferenced client key changes fence both old and recreated identities`() {
        val f = fixture
        f.database.execute("INSERT INTO clients(id,display_name,is_active) VALUES (103,'Renamable',TRUE)")
        assertEquals(1L, f.epoch(103))
        f.database.execute("UPDATE clients SET id=104 WHERE id=103")
        assertEquals("2", f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=103"))
        f.database.execute("UPDATE clients SET id=103 WHERE id=104")
        assertEquals(3L, f.epoch(103))
        assertEquals("2", f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=104"))
        assertEquals(3L, f.epoch())
    }

    @Test fun `epoch head cannot be deleted rewound or ordinarily truncated`() {
        val f = fixture
        for (sql in listOf(
            "DELETE FROM target_client_mapping_epochs",
            "UPDATE target_client_mapping_epochs SET epoch=1",
            "TRUNCATE TABLE target_client_mapping_epochs",
        )) {
            assertThrows<SQLException> { f.database.execute(sql) }
        }
        assertEquals(
            f.epoch().toString(),
            f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=101"),
        )
    }
}
