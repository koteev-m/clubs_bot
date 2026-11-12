package com.example.bot.data.client.assignments

import org.junit.jupiter.api.Tag

@Tag("it")
internal class PlatformRoleRevokePostgresIT : PlatformRoleRevokeContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.postgres()
}
