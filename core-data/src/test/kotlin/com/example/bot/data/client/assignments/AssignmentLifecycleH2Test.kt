package com.example.bot.data.client.assignments

internal class AssignmentLifecycleH2Test : AssignmentLifecycleContract() {
    override fun openDatabase(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
