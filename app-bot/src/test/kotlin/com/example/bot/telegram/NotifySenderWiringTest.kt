package com.example.bot.telegram

import com.example.bot.di.bookingModule
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class NotifySenderWiringTest : StringSpec({

    fun koinApp(
        config: NotificationsDispatchConfig,
        sender: NotifySender = mockk(relaxed = true),
    ) =
        koinApplication {
            modules(
                bookingModule,
                module { single { sender } },
                module(override = true) { single { config } },
            )
        }

    "binds NotifySenderSendPort when notifications active" {
        val sender = mockk<NotifySender>()
        coEvery { sender.sendMessage(any(), any(), any(), any()) } returns SendResult.Ok(messageId = 1)

        val app = koinApp(NotificationsDispatchConfig(flagEnabled = true, botToken = "token"), sender)

        try {
            val port = app.koin.get<SendPort>()
            port.shouldBeInstanceOf<NotifySenderSendPort>()

            val payload =
                buildJsonObject {
                    put("chatId", 123456L)
                    put("text", "hello")
                    put("dedup", "dedup-key")
                }

            port.send("topic", payload) shouldBe SendOutcome.Ok
            coVerify { sender.sendMessage(123456L, "hello", null, "dedup-key") }
        } finally {
            app.close()
        }
    }

    "binds DummySendPort when disabled" {
        val app = koinApp(NotificationsDispatchConfig(flagEnabled = false, botToken = "token"))

        try {
            val port = app.koin.get<SendPort>()
            port::class.simpleName shouldBe "DummySendPort"
        } finally {
            app.close()
        }
    }

    "binds DummySendPort when token missing" {
        val app = koinApp(NotificationsDispatchConfig(flagEnabled = true, botToken = null))

        try {
            val port = app.koin.get<SendPort>()
            port::class.simpleName shouldBe "DummySendPort"
        } finally {
            app.close()
        }
    }

    "adapter maps payload and handles downstream results softly" {
        val basePayload =
            buildJsonObject {
                put("chatId", 42L)
                put("text", "ping")
            }

        suspend fun runScenario(
            result: SendResult,
            payload: JsonObject = basePayload,
            verify: suspend (NotifySender) -> Unit = {},
            expected: MetricsExpectation,
        ) {
            val sender = mockk<NotifySender>()
            coEvery { sender.sendMessage(any(), any(), any(), any()) } returns result

            var attempts = 0
            var ok = 0
            val retryAfter = mutableListOf<Long>()
            var retryable = 0
            var permanent = 0
            val metrics =
                NotifyAdapterMetrics(
                    onAttempt = { attempts += 1 },
                    onOk = { ok += 1 },
                    onRetryAfter = { retryAfter += it },
                    onRetryable = { retryable += 1 },
                    onPermanent = { permanent += 1 },
                )
            val port = NotifySenderSendPort(sender, metrics)

            port.send("topic", payload) shouldBe SendOutcome.Ok
            verify(sender)

            attempts shouldBe expected.attempts
            ok shouldBe expected.ok
            retryAfter shouldBe expected.retryAfter
            retryable shouldBe expected.retryable
            permanent shouldBe expected.permanent
        }

        runScenario(
            result = SendResult.Ok(messageId = 10),
            payload =
                buildJsonObject {
                    put("chatId", 42L)
                    put("text", "ping")
                    put("dedup", "dedup")
                },
            verify = { sender ->
                coVerify { sender.sendMessage(42L, "ping", null, "dedup") }
            },
            expected = MetricsExpectation(attempts = 1, ok = 1),
        )

        runScenario(
            result = SendResult.RetryAfter(retryAfterMs = 1000),
            expected = MetricsExpectation(attempts = 1, ok = 0, retryAfter = listOf(1000)),
        )

        runScenario(
            result = SendResult.RetryableError("io"),
            expected = MetricsExpectation(attempts = 1, ok = 0, retryable = 1),
        )

        runScenario(
            result = SendResult.PermanentError("bad"),
            expected = MetricsExpectation(attempts = 1, ok = 0, permanent = 1),
        )

        val sender = mockk<NotifySender>(relaxed = true)
        var attempts = 0
        var permanent = 0
        val metrics =
            NotifyAdapterMetrics(
                onAttempt = { attempts += 1 },
                onPermanent = { permanent += 1 },
            )
        val port = NotifySenderSendPort(sender, metrics)
        val payloadWithoutChat = buildJsonObject { put("text", "no chat") }

        port.send("topic", payloadWithoutChat) shouldBe SendOutcome.Ok
        attempts shouldBe 1
        permanent shouldBe 1
        coVerify(exactly = 0) { sender.sendMessage(any(), any(), any(), any()) }
    }
})

private data class MetricsExpectation(
    val attempts: Int,
    val ok: Int,
    val retryAfter: List<Long> = emptyList(),
    val retryable: Int = 0,
    val permanent: Int = 0,
)

