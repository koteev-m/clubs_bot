package com.example.bot.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class DenySensitiveTurboFilterTest :
    StringSpec({
        val context = LoggerContext().apply { start() }
        val logger: Logger = context.getLogger("DenySensitiveTurboFilterTest")
        val exposedLogger: Logger = context.getLogger("Exposed")
        val appender =
            ListAppender<ILoggingEvent>().apply {
                this.context = context
                start()
            }

        context.addTurboFilter(DenySensitiveTurboFilter())
        logger.addAppender(appender)
        exposedLogger.addAppender(appender)

        beforeTest { appender.list.clear() }

        afterSpec {
            logger.detachAppender(appender)
            exposedLogger.detachAppender(appender)
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

        "exposed sql transaction failure is denied without matching a concrete secret" {
            exposedLogger.warn(
                "Transaction attempt #1 failed: database detail. Statement(s): INSERT INTO payment_actions",
                SQLException("Key (idempotency_key)=(sensitive-value)", "23505"),
            )
            appender.list.shouldBeEmpty()
        }

        "non sql exposed warning remains observable" {
            exposedLogger.warn("Exposed diagnostic without a transaction failure")
            appender.list.shouldHaveSize(1)
        }
    })
