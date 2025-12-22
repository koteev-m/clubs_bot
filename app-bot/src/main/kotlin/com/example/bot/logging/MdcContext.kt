package com.example.bot.logging

import org.slf4j.MDC

object MdcContext {
    /**
     * Executes [block] with business identifiers stored in MDC.
     *
     * This helper intentionally manages only business IDs (club, list, entry, booking) and does
     * not touch tracing attributes such as traceId or spanId. It is safe to nest: each invocation
     * captures the current MDC snapshot and restores it afterwards, even when inner scopes add or
     * override IDs.
     */
    inline fun <T> withIds(
        clubId: Long? = null,
        listId: Long? = null,
        entryId: Long? = null,
        bookingId: Long? = null,
        block: () -> T,
    ): T {
        val snapshot = captureCurrent()
        try {
            clubId?.let { MDC.put(MdcKeys.CLUB_ID, it.toString()) }
            listId?.let { MDC.put(MdcKeys.LIST_ID, it.toString()) }
            entryId?.let { MDC.put(MdcKeys.ENTRY_ID, it.toString()) }
            bookingId?.let { MDC.put(MdcKeys.BOOKING_ID, it.toString()) }
            return block()
        } finally {
            restore(snapshot)
        }
    }

    @PublishedApi
    internal fun captureCurrent(): MdcSnapshot =
        MdcSnapshot(
            clubId = MDC.get(MdcKeys.CLUB_ID),
            listId = MDC.get(MdcKeys.LIST_ID),
            entryId = MDC.get(MdcKeys.ENTRY_ID),
            bookingId = MDC.get(MdcKeys.BOOKING_ID),
        )

    @PublishedApi
    internal fun restore(snapshot: MdcSnapshot) {
        restoreKey(MdcKeys.CLUB_ID, snapshot.clubId)
        restoreKey(MdcKeys.LIST_ID, snapshot.listId)
        restoreKey(MdcKeys.ENTRY_ID, snapshot.entryId)
        restoreKey(MdcKeys.BOOKING_ID, snapshot.bookingId)
    }

    private fun restoreKey(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            MDC.remove(key)
        } else {
            MDC.put(key, value)
        }
    }
}

@PublishedApi
internal data class MdcSnapshot(
    val clubId: String?,
    val listId: String?,
    val entryId: String?,
    val bookingId: String?,
)
