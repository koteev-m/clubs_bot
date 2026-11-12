package com.example.bot.data.client.bindings

import java.sql.Connection
import java.util.UUID

/** Existing platform-only inactive lifecycle subset. No create/replacement/credential admission/ACTIVE. */
enum class InactiveMaintenanceAction { DISABLE, REVOKE, RETIRE_SELECTED_MATERIAL }

data class InactiveMaintenanceIntent(
    val expected: DurableBindingSnapshot,
    val action: InactiveMaintenanceAction,
    val materialId: UUID? = null,
)

enum class MaintenanceMutationStatus { APPLIED, REPLAY_CURRENT, REPLAY_STALE }

data class MaintenanceMutationOutcome(
    val status: MaintenanceMutationStatus,
    val currentFence: BindingFence,
)

/** Acquire before DB locks; auth/context updaters serialize with the lease until commit/rollback closes it. */
fun interface BindingMaintenanceAuthorization {
    fun acquire(intent: InactiveMaintenanceIntent): BindingMaintenanceLease?
}

/** No production implementation. Reverify signed identity/current authority in the supplied transaction. */
interface BindingMaintenanceLease : AutoCloseable {
    val controlHead: JdbcControlHeadLease? get() = null

    /** Optional new control domain; absent only on the prior isolated evidence path. */
    val intentBinding: String? get() = null

    fun identity(connection: Connection): MaintenanceIdentity
}

internal interface BindingCommitGuard : AutoCloseable {
    val requestHash: String

    fun configure(connection: Connection)

    fun identity(connection: Connection): MaintenanceIdentity

    fun before(
        connection: Connection,
        current: BindingView?,
        receiptCurrent: Boolean?,
    )

    fun after(
        connection: Connection,
        current: BindingView,
    )
}

internal class StaleMaintenanceReceipt(
    val fence: BindingFence,
) : RuntimeException()
