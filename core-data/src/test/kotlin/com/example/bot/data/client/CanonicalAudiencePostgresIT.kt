package com.example.bot.data.client

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import org.junit.jupiter.api.Tag

/** Only an explicit disposable local database (or the existing CI service) is accepted by the fixture. */
@Tag("it")
internal class CanonicalAudiencePostgresIT : CanonicalAudienceContract() {
    override fun createDatabase(): AssignmentTestDatabase = AssignmentTestDatabase.postgres()
}
