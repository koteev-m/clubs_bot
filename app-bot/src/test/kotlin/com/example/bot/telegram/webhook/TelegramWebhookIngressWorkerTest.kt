package com.example.bot.telegram.webhook

import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import com.example.bot.data.security.webhook.TelegramWebhookUpdateStatus
import com.example.bot.data.security.webhook.TelegramWebhookUpdatesTable
import com.pengrad.telegrambot.model.Update
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
            // Both repository clocks are in the past so the database's CURRENT_TIMESTAMP
            // also permits a due claim. The first worker cannot age into its own retry.
            val firstClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repository = TelegramWebhookIngressRepository(database, firstClock)
            repository.enqueue(2, """{"update_id":2}""")

            runWorkerPass(repository) { error("boom") }
            val failed =
                transaction(database) {
                    TelegramWebhookUpdatesTable
                        .selectAll()
                        .where { TelegramWebhookUpdatesTable.updateId eq 2L }
                        .single()
                }
            failed[TelegramWebhookUpdatesTable.status] shouldBe TelegramWebhookUpdateStatus.FAILED.name
            failed[TelegramWebhookUpdatesTable.lastError] shouldBe "boom"
            failed[TelegramWebhookUpdatesTable.attempts] shouldBe 1
            val retryAt = failed[TelegramWebhookUpdatesTable.nextAttemptAt].toInstant()
            retryAt.isAfter(firstClock.instant()) shouldBe true

            val processed = AtomicInteger(0)
            val retryRepository = TelegramWebhookIngressRepository(database, Clock.fixed(retryAt, ZoneOffset.UTC))
            runWorkerPass(retryRepository) { update ->
                update.updateId() shouldBe 2
                processed.incrementAndGet()
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

private suspend fun runWorkerPass(
    repository: TelegramWebhookIngressRepository,
    onUpdate: suspend (Update) -> Unit,
) {
    // The worker refreshes metrics after the batch's database writes have returned.
    val finished = CompletableDeferred<Unit>()
    val metrics = mockk<TelegramWebhookIngressMetrics>(relaxUnitFun = true)
    coEvery { metrics.refreshQueueStats(any(), any()) } answers { finished.complete(Unit) }
    val worker = TelegramWebhookIngressWorker(repository, onUpdate, metrics)
    worker.start()
    try {
        withTimeout(2_000) { finished.await() }
    } finally {
        worker.shutdown()
    }
}

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
