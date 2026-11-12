package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import org.junit.jupiter.api.Tag

@Tag("it")
internal class MappingEpochPostgresIT : MappingEpochContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.postgres()
}
