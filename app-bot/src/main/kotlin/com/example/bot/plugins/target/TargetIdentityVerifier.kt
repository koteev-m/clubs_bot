package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import io.ktor.http.Headers
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Unwired read-only verifier. Successful Telegram authentication never issues staff authority. */
internal class TargetIdentityVerifier(
    private val connection: TrustedBotConnection,
    private val audiences: TargetAudienceProvider = TargetAudienceProvider { _, _ -> null },
    private val clock: Clock = Clock.systemUTC(),
    private val maintenance: MaintenanceAuthenticationSource = MaintenanceAuthenticationSource { null },
) {
    fun verify(
        headers: Headers,
        rawQuery: String,
        context: TargetClubContext,
    ): TargetVerifiedIdentity? =
        try {
            verifyCurrent(headers, rawQuery, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No raw credentials, provider exception or parser detail escapes this boundary.
            null
        }

    /** Independent live platform channel; no client audience is invented for inactive metadata. */
    fun verifyMaintenance(
        headers: Headers,
        rawQuery: String,
    ): TargetMaintenanceIdentity? =
        try {
            require(connection.audience is ClientAudience.Platform)
            val authentication = requireNotNull(maintenance.current())
            require(authentication.connection === connection && authentication.enabled && authentication.revision > 0)
            val raw = requireNotNull(TargetInitDataCredentials.header(headers, rawQuery))
            val facts = TargetInitDataCredentials.parse(raw)
            val started = clock.instant()
            require(facts.fresh(started))
            verifySignature(raw, facts, started)
            val checked = clock.instant()
            require(checked >= started && facts.fresh(checked))
            verifySignature(raw, facts, checked)
            require(maintenance.current() === authentication)
            val finished = clock.instant()
            require(finished >= checked && facts.fresh(finished))
            VerifiedMaintenance(authentication, facts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun verifyCurrent(
        headers: Headers,
        rawQuery: String,
        context: TargetClubContext,
    ): TargetVerifiedIdentity? {
        val raw = requireNotNull(TargetInitDataCredentials.header(headers, rawQuery))
        val facts = TargetInitDataCredentials.parse(raw)
        val startedAt = clock.instant()
        val initial = requireNotNull(audiences.current(connection, context))
        require(initial.permits(connection, context) && facts.fresh(startedAt))
        verifySignature(raw, facts, startedAt)
        val current = requireNotNull(audiences.current(connection, context))
        val checkedAt = clock.instant()
        require(current.permits(connection, context) && initial.sameSnapshot(current))
        require(checkedAt >= startedAt && facts.fresh(checkedAt))
        // The opaque credential provider can rotate independently; do not accept only an old key read.
        verifySignature(raw, facts, checkedAt)
        val finalBinding = requireNotNull(audiences.current(connection, context))
        val finishedAt = clock.instant()
        require(finalBinding.permits(connection, context) && current.sameSnapshot(finalBinding))
        require(finishedAt >= checkedAt && facts.fresh(finishedAt))
        return Verified(connection, context, finalBinding.version, facts)
    }

    private fun verifySignature(
        raw: String,
        facts: TargetInitDataFacts,
        now: Instant,
    ) {
        val user =
            requireNotNull(
                connection.verifyWithPolicy(
                    raw,
                    Clock.fixed(now, ZoneOffset.UTC),
                    Duration.ofSeconds(TargetInitDataCredentials.MAX_AGE_SECONDS),
                    Duration.ZERO,
                ),
            )
        require(user.id == facts.userId)
    }

    private class VerifiedMaintenance(
        private val authentication: MaintenanceAuthenticationBinding,
        facts: TargetInitDataFacts,
    ) : TargetMaintenanceIdentity {
        override val telegramUserId = facts.userId
        override val authenticatedAt = facts.authenticatedAt
        override val validUntil = facts.validUntil

        override fun belongsTo(connection: TrustedBotConnection): Boolean = authentication.connection === connection

        override fun matchesAuthentication(binding: MaintenanceAuthenticationBinding): Boolean =
            authentication === binding
    }

    private class Verified(
        private val connection: TrustedBotConnection,
        override val context: TargetClubContext,
        override val audienceVersion: Long,
        facts: TargetInitDataFacts,
    ) : TargetVerifiedIdentity {
        override val telegramUserId = facts.userId
        override val connectionKey = connection.connectionKey
        override val authenticatedAt = facts.authenticatedAt
        override val validUntil = facts.validUntil

        override fun matchesContext(
            connection: TrustedBotConnection,
            context: TargetClubContext,
        ): Boolean = this.connection === connection && this.context == context
    }
}

internal class TargetInitDataFacts(
    val userId: Long,
    val authenticatedAt: Instant,
    val validUntil: Instant,
) {
    fun fresh(now: Instant): Boolean = authenticatedAt <= now && now < validUntil
}
