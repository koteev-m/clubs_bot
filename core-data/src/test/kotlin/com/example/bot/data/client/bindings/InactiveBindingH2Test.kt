package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class InactiveBindingH2Test : InactiveBindingContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
