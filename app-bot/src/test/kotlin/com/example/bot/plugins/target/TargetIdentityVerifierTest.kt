package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.plugins.VerifiedClientIdentity
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.server.auth.Principal
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetIdentityVerifierTest {
    @Test
    fun `valid signed identity is minimal exact context metadata not a role principal`() {
        val f = TargetIdentityFixture()
        val identity = assertNotNull(f.verify())
        assertEquals(7L, identity.telegramUserId)
        assertEquals(f.context, identity.context)
        assertEquals(f.connection.connectionKey, identity.connectionKey)
        assertEquals(1L, identity.audienceVersion)
        assertEquals(f.now, identity.authenticatedAt)
        assertEquals(f.now.plusSeconds(300), identity.validUntil)
        assertTrue(identity.matchesContext(f.connection, f.context))
        assertFalse(identity.matchesContext(f.connection, f.otherClient))
        assertFalse(identity.matchesContext(f.connection, f.otherClub))
        assertFalse(Principal::class.java.isAssignableFrom(identity.javaClass))
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(identity.javaClass))
        assertTrue(
            identity.javaClass.methods.none { it.name in setOf("copy", "getRoles", "getPermissions", "permits") },
        )
        assertFalse(identity.toString().contains("synthetic_signature"))
        assertEquals(3, f.reads)
    }

    @Test
    fun `correct HMAC wrong HMAC wrong bot and changed signature are distinct from legacy headers`() {
        val f = TargetIdentityFixture()
        assertNotNull(f.verify())
        val invalid =
            listOf(
                f.signed(key = "222222:OTHER_SYNTHETIC_BOT"),
                f.signed().replace("synthetic_signature", "changed_signature"),
                f.signed().substringBefore("&hash="),
                f.signed().substringBefore("&hash=") + "&hash=" + "00".repeat(32),
            )
        for (raw in invalid) assertNull(f.verify(raw))
    }

    @Test
    fun `unknown inactive and invalid version connections fail before secret lookup`() {
        val f = TargetIdentityFixture()
        for (binding in listOf(
            null,
            TargetAudienceBinding(f.connection, f.audience, 1, false),
            TargetAudienceBinding(f.connection, f.audience, 0, true),
        )) {
            f.binding = binding
            assertNull(f.verify())
        }
        assertEquals(0, f.tokenReads)
        assertNull(TargetIdentityVerifier(f.connection, clock = f.clock).verify(f.headers(), "", f.context))
    }

    @Test
    fun `provider cannot widen client club or rebind a registered connection by matching its name`() {
        val f = TargetIdentityFixture()
        assertNull(f.verifier.verify(f.headers(), "", f.otherClient))
        assertNull(f.verifier.verify(f.headers(), "", f.otherClub))
        for (audience in listOf(
            ClientAudience.Client(102, setOf(2001)),
            ClientAudience.Client(101, setOf(1001, 1002)),
        )) {
            f.binding = TargetAudienceBinding(f.connection, audience, 1, true)
            assertNull(f.verify())
        }
        val clone = TrustedBotConnection(f.connection.connectionKey, f.audience) { TEST_BOT_TOKEN }
        f.binding = TargetAudienceBinding(clone, f.audience, 1, true)
        assertNull(f.verify())
    }

    @Test
    fun `platform audience never implies client binding but explicit canonical narrowing can authenticate`() {
        val f = TargetIdentityFixture()
        val canonical = TrustedBotConnection("synthetic-canonical", ClientAudience.Platform) { TEST_BOT_TOKEN }
        var binding = TargetAudienceBinding(canonical, ClientAudience.Platform, 1, true)
        val verifier = TargetIdentityVerifier(canonical, TargetAudienceProvider { _, _ -> binding }, f.clock)
        assertNull(verifier.verify(f.headers(), "", f.context))
        binding = TargetAudienceBinding(canonical, f.audience, 1, true)
        assertNotNull(verifier.verify(f.headers(), "", f.context))
        assertNull(verifier.verify(f.headers(), "", f.otherClient))
        assertNull(verifier.verify(f.headers(), "", f.otherClub))
        // Authentication of a platform-owner-shaped positive ID grants no platform/staff interface.
        val identity = assertNotNull(verifier.verify(f.headers(f.signed(user = "{\"id\":99}")), "", f.context))
        assertEquals(99L, identity.telegramUserId)
        assertFalse(Principal::class.java.isAssignableFrom(identity.javaClass))
    }

    @Test
    fun `freshness is less than 300 seconds with zero future skew at nanosecond boundaries`() {
        val f = TargetIdentityFixture()
        val at = f.now
        val raw = f.signed()
        for (delta in listOf(0L, 1L, 299L)) {
            f.now = at.plusSeconds(delta)
            assertNotNull(f.verify(raw))
        }
        f.now = at.plusSeconds(300).minusNanos(1)
        assertNotNull(f.verify(raw))
        f.now = at.plusSeconds(300)
        assertNull(f.verify(raw))
        f.now = at.plusSeconds(300).plusNanos(1)
        assertNull(f.verify(raw))
        f.now = at.minusNanos(1)
        assertNull(f.verify(raw))
        f.now = at.minusSeconds(1)
        assertNull(f.verify(raw))
    }

    @Test
    fun `positive integer Telegram user IDs are required without profile or body authority`() {
        val f = TargetIdentityFixture()
        for (id in listOf("0", "-1", "true", "\"7\"", "1.0", "1e3", "9223372036854775808", "null")) {
            assertNull(f.verify(f.signed(user = "{\"id\":$id}")))
        }
        assertNotNull(f.verify(f.signed(user = "{\"id\":9223372036854775807}")))
        assertNotNull(f.verify(f.signed(user = "{\"id\":7,\"first_name\":\"Иван 😀\"}")))
    }

    @Test
    fun `metadata change during verification fails closed and credentials are checked again on each call`() {
        val f = TargetIdentityFixture()
        f.onBinding = { if (f.reads == 2) f.binding = TargetAudienceBinding(f.connection, f.audience, 2, true) }
        assertNull(f.verify())
        f.onBinding = {}
        assertNotNull(f.verify())
        f.binding = TargetAudienceBinding(f.connection, f.audience, 3, false)
        assertNull(f.verify())
    }

    @Test
    fun `credential rotation between checks cannot accept an old key with an unchanged registry revision`() {
        val f = TargetIdentityFixture()
        f.onBinding = { if (f.reads == 2) f.token = "222222:OTHER_SYNTHETIC_BOT" }
        assertNull(f.verify())
        assertEquals(2, f.tokenReads)
    }

    @Test
    fun `same revision cannot conceal a changed finite audience during verification`() {
        val f = TargetIdentityFixture()
        val base = ClientAudience.Client(101, setOf(1001, 1002))
        val connection = TrustedBotConnection("synthetic-bounded", base) { TEST_BOT_TOKEN }
        var reads = 0
        val provider =
            TargetAudienceProvider { _, _ ->
                reads++
                val audience = if (reads == 1) f.audience else base
                TargetAudienceBinding(connection, audience, 1, true)
            }
        assertNull(TargetIdentityVerifier(connection, provider, f.clock).verify(f.headers(), "", f.context))
    }

    @Test
    fun `expiry or backward clock step during token resolution rejects the result`() {
        val f = TargetIdentityFixture()
        val raw =
            f.signed(
                date =
                    f.now
                        .minusSeconds(299)
                        .epochSecond
                        .toString(),
            )
        f.onToken = { f.now = f.now.plusSeconds(2) }
        assertNull(f.verify(raw))
        f.onToken = { f.now = f.now.minusNanos(1) }
        assertNull(
            f.verify(
                f.signed(
                    date =
                        f.now
                            .minusSeconds(10)
                            .epochSecond
                            .toString(),
                ),
            ),
        )
    }

    @Test
    fun `expiry during final HMAC is checked before returning an identity`() {
        val f = TargetIdentityFixture()
        val raw =
            f.signed(
                date =
                    f.now
                        .minusSeconds(299)
                        .epochSecond
                        .toString(),
            )
        f.onToken = { if (f.tokenReads == 2) f.now = f.now.plusSeconds(2) }
        assertNull(f.verify(raw))
    }

    @Test
    fun `provider failures are sanitized and cancellation is never swallowed`() {
        val f = TargetIdentityFixture()
        f.onBinding = { error("synthetic sensitive provider detail") }
        assertNull(f.verify())
        f.onBinding = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { f.verify() }
        f.onBinding = {}
        f.onToken = { error("synthetic sensitive token detail") }
        assertNull(f.verify())
        f.onToken = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { f.verify() }
    }

    @Test
    fun `successful initData remains reusable authentication and has no one time nonce semantics`() {
        val f = TargetIdentityFixture()
        val raw = f.signed()
        val first = assertNotNull(f.verify(raw))
        val second = assertNotNull(f.verify(raw))
        assertEquals(first.telegramUserId, second.telegramUserId)
        assertEquals(first.validUntil, second.validUntil)
    }

    @Test
    fun `legacy trusted identity keeps twelve hour freshness while TARGET opts into five minutes`() {
        val f = TargetIdentityFixture()
        f.now = Instant.now()
        val raw =
            f.signed(
                date =
                    f.now
                        .minusSeconds(12 * 3600L)
                        .epochSecond
                        .toString(),
            )
        assertNotNull(f.connection.verify(raw))
        assertNotNull(VerifiedClientIdentity.validate(f.connection, raw))
        assertNull(f.verify(raw))
    }
}
