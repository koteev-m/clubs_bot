package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase

internal class BindingChildFenceH2Test : BindingChildFenceContract() {
    override fun database(): AssignmentTestDatabase = AssignmentTestDatabase.h2()
}
