package com.example.bot.data.client

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class CanonicalAudienceH2Test : CanonicalAudienceContract() {
    override fun createDatabase(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
