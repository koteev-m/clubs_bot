package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.server.auth.Principal
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CanonicalTargetAudienceProviderTest {
    @Test
    fun `explicit canonical pin joins current metadata without granting staff authority`() {
        val f = CanonicalTargetAudienceFixture()
        val binding = assertNotNull(f.read())
        assertEquals(setOf(1001L, 1002L), binding.canonicalSnapshot?.mappingCreatedAt?.keys)
        assertEquals(4L, binding.version)
        val verified = assertNotNull(f.verify())
        assertEquals(7L, verified.telegramUserId)
        assertFalse(Principal::class.java.isAssignableFrom(verified.javaClass))
        assertEquals(4, f.reads)
    }

    @Test
    fun `no live explicit binding means deny even with a pinned canonical connection`() {
        val f = CanonicalTargetAudienceFixture()
        assertNull(CanonicalTargetAudienceProvider(f.pinned, f.repository).current(f.connection, f.context))
        f.current = null
        assertNull(f.read())
        assertEquals(0, f.reads)
    }

    @Test
    fun `same connection key name or token is not trusted connection identity`() {
        val f = CanonicalTargetAudienceFixture()
        val clone = TrustedBotConnection(f.connection.connectionKey, ClientAudience.Platform) { TEST_BOT_TOKEN }
        assertNull(f.provider.current(clone, f.context))
        f.current = TargetAudienceBinding(clone, f.audience, 4, true)
        assertNull(f.read())
        assertEquals(0, f.reads)
    }

    @Test
    fun `foreign client neighbor and newly created club do not inherit finite scope`() {
        val f = CanonicalTargetAudienceFixture()
        for (context in listOf(
            TargetClubContext(102, 1001),
            TargetClubContext(102, 2001),
            TargetClubContext(101, 1003),
        )) {
            assertNull(f.provider.current(f.connection, context))
        }
        assertEquals(0, f.reads)
        assertNotNull(f.provider.current(f.connection, TargetClubContext(101, 1002)))
    }

    @Test
    fun `platform alone empty or invalid binding never obtains a canonical audience`() {
        val f = CanonicalTargetAudienceFixture()
        for (pinned in listOf(
            TargetAudienceBinding(f.connection, ClientAudience.Platform, 4, true),
            TargetAudienceBinding(f.connection, ClientAudience.Client(101, emptySet()), 4, true),
            TargetAudienceBinding(f.connection, f.audience, 0, true),
            TargetAudienceBinding(f.connection, f.audience, 4, false),
        )) {
            assertNull(
                CanonicalTargetAudienceProvider(pinned, f.repository, f.bindings).current(f.connection, f.context),
            )
        }
        assertEquals(0, f.reads)
    }

    @Test
    fun `branded client restriction is not a persisted branded binding`() {
        val f = CanonicalTargetAudienceFixture()
        val branded = TrustedBotConnection("synthetic-brand", f.audience) { TEST_BOT_TOKEN }
        val pin = TargetAudienceBinding(branded, f.audience, 4, true)
        val provider = CanonicalTargetAudienceProvider(pin, f.repository, CanonicalTargetBindingSource { pin })
        assertNull(provider.current(branded, f.context))
        assertEquals(0, f.reads)
    }

    @Test
    fun `revoked changed or stale generations fail before a database read`() {
        val f = CanonicalTargetAudienceFixture()
        for (current in listOf(
            TargetAudienceBinding(f.connection, f.audience, 4, false),
            TargetAudienceBinding(f.connection, f.audience, 3, true),
            TargetAudienceBinding(f.connection, f.audience, 5, true),
            TargetAudienceBinding(f.connection, ClientAudience.Client(101, setOf(1001)), 4, true),
        )) {
            f.current = current
            assertNull(f.read())
        }
        assertEquals(0, f.reads)
    }

    @Test
    fun `revocation or revision change while database statement executes rejects its result`() {
        val f = CanonicalTargetAudienceFixture()
        f.onRead = { f.current = TargetAudienceBinding(f.connection, f.audience, 4, false) }
        assertNull(f.read())
        f.current = f.pinned
        f.onRead = { f.current = TargetAudienceBinding(f.connection, f.audience, 5, true) }
        assertNull(f.read())
    }

    @Test
    fun `incomplete foreign or unavailable canonical mapping cannot produce a binding`() {
        val f = CanonicalTargetAudienceFixture()
        for (canonical in listOf(
            null,
            f.snapshot(client = 102),
            f.snapshot(clubs = setOf(1001)),
            f.snapshot(clubs = setOf(1001, 1002, 1003)),
        )) {
            f.canonical = canonical
            assertNull(f.read())
        }
    }

    @Test
    fun `db failure is sanitized but cancellation propagates through provider and verifier`() {
        val f = CanonicalTargetAudienceFixture()
        f.onRead = { throw SQLException("synthetic sensitive DB detail") }
        assertNull(f.read())
        assertNull(f.verify())
        f.onRead = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { f.read() }
        assertFailsWith<CancellationException> { f.verify() }
    }

    @Test
    fun `server binding failure has no fallback and cancellation is not swallowed`() {
        val f = CanonicalTargetAudienceFixture()
        f.onBinding = { error("synthetic sensitive binding detail") }
        assertNull(f.read())
        f.onBinding = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { f.read() }
        assertEquals(0, f.reads)
    }

    @Test
    fun `verifier rejects client lifecycle changes between its fresh mapping checks`() {
        val f = CanonicalTargetAudienceFixture()
        f.onRead = { f.canonical = f.snapshot(lifecycle = f.reads.toLong()) }
        assertNull(f.verify())
        assertEquals(2, f.reads)
    }

    @Test
    fun `verifier rejects changed mapping metadata between its checks`() {
        val f = CanonicalTargetAudienceFixture()
        f.onRead = { f.canonical = f.snapshot(createdAt = Instant.EPOCH.plusSeconds(f.reads.toLong())) }
        assertNull(f.verify())
    }

    @Test
    fun `canonical snapshots cannot be replayed as pinned connection configuration`() {
        val f = CanonicalTargetAudienceFixture()
        val prior = assertNotNull(f.read())
        assertNull(CanonicalTargetAudienceProvider(prior, f.repository, f.bindings).current(f.connection, f.context))
    }
}
