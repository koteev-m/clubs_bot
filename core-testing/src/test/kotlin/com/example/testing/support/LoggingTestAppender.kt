package com.example.testing.support

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

/**
 * Logback appender collecting log messages for assertions.
 */
class LoggingTestAppender : AppenderBase<ILoggingEvent>() {
    private val events = mutableListOf<ILoggingEvent>()

    override fun append(eventObject: ILoggingEvent) {
        events += eventObject
    }

    fun events(): List<ILoggingEvent> = events.toList()

    fun clear() {
        events.clear()
    }
}
