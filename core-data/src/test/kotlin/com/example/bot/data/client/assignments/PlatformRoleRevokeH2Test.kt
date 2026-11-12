package com.example.bot.data.client.assignments

internal class PlatformRoleRevokeH2Test : PlatformRoleRevokeContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
