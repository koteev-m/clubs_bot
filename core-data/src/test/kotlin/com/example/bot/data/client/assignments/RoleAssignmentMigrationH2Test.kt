package com.example.bot.data.client.assignments

internal class RoleAssignmentMigrationH2Test : RoleAssignmentMigrationContract() {
    override fun database(target: String?): AssignmentTestDatabase = AssignmentTestDatabase.h2(target)
}
