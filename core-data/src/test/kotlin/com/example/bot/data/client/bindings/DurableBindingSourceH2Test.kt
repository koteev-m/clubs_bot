package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class DurableBindingSourceH2Test : DurableBindingSourceContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
