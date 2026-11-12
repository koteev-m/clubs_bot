package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class BindingMigrationH2Test : BindingMigrationContract() {
    override fun database(target: String?): AssignmentTestDatabase = AssignmentTestDatabase.h2(target)
}
