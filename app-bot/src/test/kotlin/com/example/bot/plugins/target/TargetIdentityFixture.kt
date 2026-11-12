package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.http.Headers
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class TargetIdentityFixture {
    val context = TargetClubContext(101, 1001)
    val otherClub = TargetClubContext(101, 1002)
    val otherClient = TargetClubContext(102, 2001)
    val audience = ClientAudience.Client(101, setOf(1001))
    var now: Instant = Instant.parse("2026-11-11T12:00:00Z")
    var token = TEST_BOT_TOKEN
    var onToken: () -> Unit = {}
    var tokenReads = 0
    val connection =
        TrustedBotConnection("synthetic-target-client", audience) {
            tokenReads++
            onToken()
            token
        }
    var binding: TargetAudienceBinding? = TargetAudienceBinding(connection, audience, 1, true)
    var reads = 0
    var onBinding: () -> Unit = {}
    val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

            override fun instant(): Instant = now
        }
    val provider =
        TargetAudienceProvider { _, _ ->
            reads++
            onBinding()
            binding
        }
    val verifier = TargetIdentityVerifier(connection, provider, clock)

    init {
        tokenReads = 0
    }

    fun signed(
        user: String = "{\"id\":7,\"first_name\":\"Synthetic\"}",
        date: String = now.epochSecond.toString(),
        key: String = TEST_BOT_TOKEN,
        extras: Map<String, String> = emptyMap(),
    ): String =
        WebAppInitDataTestHelper.createInitData(
            key,
            linkedMapOf("auth_date" to date, "user" to user, "signature" to "synthetic_signature") + extras,
        )

    fun headers(raw: String = signed()): Headers = Headers.build { append("X-Telegram-Init-Data", raw) }

    fun verify(raw: String = signed()): TargetVerifiedIdentity? = verifier.verify(headers(raw), "", context)
}
