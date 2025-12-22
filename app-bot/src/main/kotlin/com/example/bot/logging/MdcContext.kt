package com.example.bot.logging

import org.slf4j.MDC

object MdcContext {
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
            mapOf(
                MdcKeys.CLUB_ID to MDC.get(MdcKeys.CLUB_ID),
                MdcKeys.LIST_ID to MDC.get(MdcKeys.LIST_ID),
                MdcKeys.ENTRY_ID to MDC.get(MdcKeys.ENTRY_ID),
                MdcKeys.BOOKING_ID to MDC.get(MdcKeys.BOOKING_ID),
                MdcKeys.TRACE_ID to MDC.get(MdcKeys.TRACE_ID),
                MdcKeys.SPAN_ID to MDC.get(MdcKeys.SPAN_ID),
            ),
        )

    @PublishedApi
    internal fun restore(snapshot: MdcSnapshot) {
        snapshot.values.forEach { (key, value) ->
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value)
            }
        }
    }
}

internal data class MdcSnapshot(val values: Map<String, String?>)
