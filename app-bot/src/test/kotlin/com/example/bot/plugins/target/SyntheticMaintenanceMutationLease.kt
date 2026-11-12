package com.example.bot.plugins.target

import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Test-only fair shared fence; no production implementation/default injection is supplied. */
internal class SyntheticMaintenanceMutationLease(
    val fixture: MaintenanceSelectorFixture,
) : MaintenanceMutationLeaseSource {
    private val lock = ReentrantReadWriteLock(true)
    private val connectionId: UUID = UUID.randomUUID()
    private val authorityId: UUID = UUID.randomUUID()
    var verifications = 0
    var acquired = 0
    var closed = 0
    var onVerify: () -> Unit = {}
    var onClose: () -> Unit = {}
    val queued: Boolean get() = lock.hasQueuedThreads()

    override fun acquire(request: MaintenanceMutationRequest): MaintenanceMutationStateLease {
        lock.readLock().lock()
        acquired++
        return object : MaintenanceMutationStateLease {
            override fun current(connection: java.sql.Connection): MaintenanceMutationState {
                verifications++
                onVerify()
                return MaintenanceMutationState(
                    checkNotNull(fixture.authentication),
                    checkNotNull(fixture.selection),
                    fixture.owner,
                    connectionId,
                    authorityId,
                )
            }

            override fun close() {
                try {
                    onClose()
                } finally {
                    closed++
                    lock.readLock().unlock()
                }
            }
        }
    }

    fun change(block: () -> Unit) {
        lock.writeLock().lock()
        try {
            block()
        } finally {
            lock.writeLock().unlock()
        }
    }
}
