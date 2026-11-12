package com.example.bot.data.client.assignments

import org.junit.jupiter.api.Tag

@Tag("it")
internal class RoleAssignmentMigrationPostgresIT : RoleAssignmentMigrationContract() {
    override fun database(target: String?): AssignmentTestDatabase = AssignmentTestDatabase.postgres(target)
}
