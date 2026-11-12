package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class MappingEpochH2Test : MappingEpochContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
