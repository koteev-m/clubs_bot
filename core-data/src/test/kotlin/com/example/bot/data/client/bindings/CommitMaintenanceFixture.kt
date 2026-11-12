package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingFailure
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/** Explicit synthetic gate only. Production has no authority lease implementation. */
internal class CommitMaintenanceFixture(
    val f: BindingTestFixture,
) {
    @Volatile var allowed = true
    private val acquiredCount = AtomicInteger()
    private val closedCount = AtomicInteger()
    private val checkedCount = AtomicInteger()
    val acquires: Int get() = acquiredCount.get()
    val closes: Int get() = closedCount.get()
    val checks: Int get() = checkedCount.get()
    var onAcquire: () -> Unit = {}
    var onCheck: (Connection) -> Unit = {}
    val authorization =
        BindingMaintenanceAuthorization {
            acquiredCount.incrementAndGet()
            onAcquire()
            object : BindingMaintenanceLease {
                override fun identity(connection: Connection): MaintenanceIdentity {
                    checkedCount.incrementAndGet()
                    check(!connection.autoCommit)
                    onCheck(connection)
                    if (!allowed) throw OnboardingFailure("forbidden")
                    return MaintenanceIdentity(
                        BindingTestFixture.TELEGRAM,
                        f.authorityConnection,
                        f.authorityRef,
                        BindingTestFixture.START,
                        BindingTestFixture.START.plusSeconds(240),
                    )
                }

                override fun close() {
                    closedCount.incrementAndGet()
                }
            }
        }
    val schema =
        BindingStorageSchema.fromServerConfiguration(
            f.database.dataSource.connection.use {
                checkNotNull(it.schema)
            },
        )
    val adapter = JdbcCommitTimeBindingMaintenance(f.source, schema, authorization, f.clock)

    suspend fun intent(
        view: BindingView,
        action: InactiveMaintenanceAction = InactiveMaintenanceAction.DISABLE,
        revision: Long? = null,
    ): InactiveMaintenanceIntent {
        val result = f.durableSource().read(f.reference(view, revision = revision)) as DurableBindingRead.Inactive
        val material =
            if (action ==
                InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL
            ) {
                result.snapshot.credential?.materialId
            } else {
                null
            }
        return InactiveMaintenanceIntent(result.snapshot, action, material)
    }
}
