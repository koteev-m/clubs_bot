package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

@Tag("it")
internal class BindingSqlPostgresIT {
    @Test fun `truncate cascade cannot silently erase mapping fences`() {
        BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
            val legacy = f.legacy()
            val mappings = f.database.rows("SELECT * FROM client_clubs ORDER BY club_id")
            val heads = f.database.rows("SELECT * FROM target_client_mapping_epochs ORDER BY client_id")
            for (table in listOf("clients", "clubs")) {
                assertThrows<SQLException> { f.database.execute("TRUNCATE TABLE $table CASCADE") }
                assertEquals(legacy, f.legacy())
                assertEquals(mappings, f.database.rows("SELECT * FROM client_clubs ORDER BY club_id"))
                assertEquals(heads, f.database.rows("SELECT * FROM target_client_mapping_epochs ORDER BY client_id"))
            }
        }
    }
}
