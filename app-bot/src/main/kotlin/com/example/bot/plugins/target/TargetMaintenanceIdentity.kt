package com.example.bot.plugins.target

import com.example.bot.plugins.TrustedBotConnection
import java.time.Instant

/** Immutable live authentication channel. Replace before rotation/revoke; never reuse an old revision/object. */
internal class MaintenanceAuthenticationBinding(
    val connection: TrustedBotConnection,
    val revision: Long,
    val enabled: Boolean = true,
)

/** No production lifecycle provider; shared by the verifier and selector, default absent. */
internal fun interface MaintenanceAuthenticationSource {
    fun current(): MaintenanceAuthenticationBinding?
}

/** Authentication only. No client context, ownership, maintenance permission or reusable write proof. */
internal sealed interface TargetMaintenanceIdentity {
    val telegramUserId: Long
    val authenticatedAt: Instant
    val validUntil: Instant

    fun belongsTo(connection: TrustedBotConnection): Boolean

    fun matchesAuthentication(binding: MaintenanceAuthenticationBinding): Boolean
}
