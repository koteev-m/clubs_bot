package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class CommitMaintenanceH2Test : CommitMaintenanceContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
