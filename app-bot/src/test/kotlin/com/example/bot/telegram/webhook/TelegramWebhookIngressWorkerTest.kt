package com.example.bot.telegram.webhook

import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import com.example.bot.data.security.webhook.TelegramWebhookUpdateStatus
import com.example.bot.data.security.webhook.TelegramWebhookUpdatesTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TelegramWebhookIngressWorkerTest :
    StringSpec({
        "worker drains queued updates" {
            val database = testDatabase()
            val repository = TelegramWebhookIngressRepository(database)
            val metrics = TelegramWebhookIngressMetrics(SimpleMeterRegistry())
            val processed = AtomicInteger(0)
            repository.enqueue(1, """{"update_id":1}""")

            val worker =
                TelegramWebhookIngressWorker(
                    repository = repository,
                    onUpdate = { processed.incrementAndGet() },
                    metrics = metrics,
                    idleDelay = Duration.ofMillis(20),
                )

            worker.start()
            awaitUntilDone(worker) {
                processed.get() == 1 && statusOf(database, 1) == TelegramWebhookUpdateStatus.DONE
            }

            processed.get() shouldBe 1
            statusOf(database, 1) shouldBe TelegramWebhookUpdateStatus.DONE
            payloadOf(database, 1) shouldBe ""
        }

        "failed update is retried by new worker instance" {
            val database = testDatabase()
            val repository = TelegramWebhookIngressRepository(database)
            val metrics = TelegramWebhookIngressMetrics(SimpleMeterRegistry())
            val processed = AtomicInteger(0)
            repository.enqueue(2, """{"update_id":2}""")

            val firstWorker =
                TelegramWebhookIngressWorker(
                    repository = repository,
                    onUpdate = { error("boom") },
                    metrics = metrics,
                    idleDelay = Duration.ofMillis(20),
                )
            firstWorker.start()
            awaitUntilDone(firstWorker) {
                statusOf(database, 2) == TelegramWebhookUpdateStatus.FAILED
            }

            transaction(database) {
                TelegramWebhookUpdatesTable.update({ TelegramWebhookUpdatesTable.updateId eq 2L }) {
                    it[TelegramWebhookUpdatesTable.nextAttemptAt] = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)
                }
            }

            val secondWorker =
                TelegramWebhookIngressWorker(
                    repository = repository,
                    onUpdate = { processed.incrementAndGet() },
                    metrics = metrics,
                    idleDelay = Duration.ofMillis(20),
                )
            secondWorker.start()
            awaitUntilDone(secondWorker) {
                processed.get() == 1 && statusOf(database, 2) == TelegramWebhookUpdateStatus.DONE
            }

            processed.get() shouldBe 1
            statusOf(database, 2) shouldBe TelegramWebhookUpdateStatus.DONE
        }

        "worker reclaims stale processing lease" {
            val database = testDatabase()
            val repository = TelegramWebhookIngressRepository(database)
            val metrics = TelegramWebhookIngressMetrics(SimpleMeterRegistry())
            val processed = AtomicInteger(0)

            transaction(database) {
                TelegramWebhookUpdatesTable.insert {
                    it[updateId] = 3L
                    it[payloadJson] = """{"update_id":3}"""
                    it[status] = TelegramWebhookUpdateStatus.PROCESSING.name
                    it[attempts] = 2
                    it[nextAttemptAt] = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)
                    it[lastError] = null
                    it[processedAt] = null
                }
            }

            val worker =
                TelegramWebhookIngressWorker(
                    repository = repository,
                    onUpdate = { processed.incrementAndGet() },
                    metrics = metrics,
                    idleDelay = Duration.ofMillis(20),
                )

            worker.start()
            awaitUntilDone(worker) {
                processed.get() == 1 && statusOf(database, 3) == TelegramWebhookUpdateStatus.DONE
            }

            processed.get() shouldBe 1
            statusOf(database, 3) shouldBe TelegramWebhookUpdateStatus.DONE
        }
    })

private fun testDatabase(): Database {
    val database =
        Database.connect(
            url = "jdbc:h2:mem:telegram-worker-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
    transaction(database) {
        SchemaUtils.create(TelegramWebhookUpdatesTable)
    }
    return database
}

private fun statusOf(
    database: Database,
    updateId: Long,
): TelegramWebhookUpdateStatus =
    transaction(database) {
        TelegramWebhookUpdatesTable
            .selectAll()
            .where { TelegramWebhookUpdatesTable.updateId eq updateId }
            .first()[TelegramWebhookUpdatesTable.status]
            .let(TelegramWebhookUpdateStatus::valueOf)
    }

private fun payloadOf(
    database: Database,
    updateId: Long,
): String =
    transaction(database) {
        TelegramWebhookUpdatesTable
            .selectAll()
            .where { TelegramWebhookUpdatesTable.updateId eq updateId }
            .first()[TelegramWebhookUpdatesTable.payloadJson]
    }

private suspend fun awaitUntilDone(
    worker: TelegramWebhookIngressWorker,
    condition: () -> Boolean,
) {
    try {
        withTimeout(2_000) {
            while (!condition()) {
                delay(20)
            }
        }
    } finally {
        worker.shutdown()
    }
}
