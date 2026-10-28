package com.example.bot.data.support

import org.junit.jupiter.api.condition.EnabledIf

/** Same contract as Docker IT, in a fresh cluster launched and owned by this test instance. */
@EnabledIf("com.example.bot.data.support.NativeSupportPostgres#requested")
internal class SupportTicketCreationNativePostgresIT : SupportTicketCreationContract() {
    private val postgres = NativeSupportPostgres.configured()
    private lateinit var isolatedUrl: String
    override val jdbcUrl: String get() = isolatedUrl
    override val username: String get() = postgres.username
    override val password: String get() = postgres.password

    override fun startPostgres() {
        isolatedUrl = createDatabase("support_native")
    }

    override fun createDatabase(prefix: String): String = postgres.createDatabase(prefix)

    override fun stopPostgres() = postgres.close()
}
