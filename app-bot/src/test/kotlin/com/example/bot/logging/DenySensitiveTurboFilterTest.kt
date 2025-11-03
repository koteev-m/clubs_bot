package com.example.bot.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class DenySensitiveTurboFilterTest : StringSpec({
    val context = LoggerContext().apply { start() }
    val logger: Logger = context.getLogger("DenySensitiveTurboFilterTest")
    val appender = ListAppender<ILoggingEvent>().apply {
        this.context = context
        start()
    }

    context.addTurboFilter(DenySensitiveTurboFilter())
    logger.addAppender(appender)

    beforeTest { appender.list.clear() }

    afterSpec {
        logger.detachAppender(appender)
        context.stop()
    }

    "message with qr is denied" {
        logger.info("qr=GL:123")
        appender.list.shouldBeEmpty()
    }

    "message with start_param is denied" {
        logger.info("payload start_param=G_ABC")
        appender.list.shouldBeEmpty()
    }

    "message with idempotencyKey is denied" {
        logger.info("idempotencyKey=abc")
        appender.list.shouldBeEmpty()
    }

    "safe message passes through" {
        val message = "booking.created clubId=42"
        logger.info(message)
        appender.list.shouldHaveSize(1)
        appender.list.single().formattedMessage shouldBe message
    }
})
