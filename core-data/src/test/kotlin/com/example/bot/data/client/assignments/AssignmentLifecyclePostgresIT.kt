package com.example.bot.data.client.assignments

import org.junit.jupiter.api.Tag

@Tag("it")
internal class AssignmentLifecyclePostgresIT : AssignmentLifecycleContract() {
    override fun openDatabase(): AssignmentTestDatabase = AssignmentTestDatabase.postgres()
}
