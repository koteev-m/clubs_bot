package com.example.bot.data.client

internal class ClientContextRepositoryTest : ClientContextContract() {
    override fun createFixture(): ClientContextFixture = ClientContextFixture.h2()
}
