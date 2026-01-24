package com.example.bot.notifications

import com.example.bot.opschat.ClubOpsChatConfig
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationEvent
import com.example.bot.telegram.TelegramClient
import com.pengrad.telegrambot.response.BaseResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Duration

class TelegramOperationalNotificationServiceTest {
    @Test
    fun `enqueue dispatches telegram message with mapped thread id`() =
        runBlocking {
            val response = mockk<BaseResponse> {
                io.mockk.every { isOk } returns true
            }
            val signal = CompletableDeferred<Unit>()
            val client = mockk<TelegramClient>()
            coEvery { client.sendMessage(456L, any(), 99) } coAnswers {
                signal.complete(Unit)
                response
            }

            val repo =
                object : ClubOpsChatConfigRepository {
                    override suspend fun getByClubId(clubId: Long): ClubOpsChatConfig? =
                        ClubOpsChatConfig(
                            clubId = clubId,
                            chatId = 456L,
                            bookingsThreadId = 99,
                            checkinThreadId = 11,
                            supportThreadId = 22,
                            updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
                        )

                    override suspend fun upsert(config: com.example.bot.opschat.ClubOpsChatConfigUpsert) =
                        throw UnsupportedOperationException("not used")
                }

            val service =
                TelegramOperationalNotificationService(
                    telegramClient = client,
                    configRepository = repo,
                    config =
                        OpsNotificationServiceConfig(
                            queueCapacity = 1,
                            maxAttempts = 1,
                        ),
                )

            service.start()
            service.enqueue(
                OpsDomainNotification(
                    clubId = 1,
                    event = OpsNotificationEvent.BOOKING_CREATED,
                    subjectId = "42",
                    occurredAt = Instant.parse("2025-01-02T03:04:05Z"),
                ),
            )

            withTimeout(1_000) {
                signal.await()
            }

            service.stop()

            coVerify { client.sendMessage(456L, any(), 99) }
        }

    @Test
    fun `timeout does not cancel worker and retries`() =
        runBlocking {
            val response = mockk<BaseResponse> {
                io.mockk.every { isOk } returns true
            }
            val client = mockk<TelegramClient>()
            coEvery { client.sendMessage(456L, any(), 99) } coAnswers {
                delay(50)
                response
            }

            val repo =
                object : ClubOpsChatConfigRepository {
                    override suspend fun getByClubId(clubId: Long): ClubOpsChatConfig? =
                        ClubOpsChatConfig(
                            clubId = clubId,
                            chatId = 456L,
                            bookingsThreadId = 99,
                            checkinThreadId = 11,
                            supportThreadId = 22,
                            updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
                        )

                    override suspend fun upsert(config: com.example.bot.opschat.ClubOpsChatConfigUpsert) =
                        throw UnsupportedOperationException("not used")
                }

            val service =
                TelegramOperationalNotificationService(
                    telegramClient = client,
                    configRepository = repo,
                    config =
                        OpsNotificationServiceConfig(
                            queueCapacity = 1,
                            sendTimeout = Duration.ofMillis(10),
                            maxAttempts = 2,
                            retryDelay = Duration.ZERO,
                        ),
                )

            service.start()
            service.enqueue(
                OpsDomainNotification(
                    clubId = 1,
                    event = OpsNotificationEvent.BOOKING_CREATED,
                    subjectId = "42",
                    occurredAt = Instant.parse("2025-01-02T03:04:05Z"),
                ),
            )

            coVerify(timeout = 1_000, exactly = 2) { client.sendMessage(456L, any(), 99) }

            service.stop()
        }

    @Test
    fun `dispatch exception does not stop worker`() =
        runBlocking {
            val response = mockk<BaseResponse> {
                io.mockk.every { isOk } returns true
            }
            val signal = CompletableDeferred<Unit>()
            val client = mockk<TelegramClient>()
            coEvery { client.sendMessage(456L, any(), 99) } coAnswers {
                signal.complete(Unit)
                response
            }

            val config =
                ClubOpsChatConfig(
                    clubId = 1,
                    chatId = 456L,
                    bookingsThreadId = 99,
                    checkinThreadId = 11,
                    supportThreadId = 22,
                    updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
                )
            val repo = mockk<ClubOpsChatConfigRepository>()
            coEvery { repo.getByClubId(1L) } throws RuntimeException("boom") andThen config
            coEvery { repo.upsert(any()) } throws UnsupportedOperationException("not used")

            val service =
                TelegramOperationalNotificationService(
                    telegramClient = client,
                    configRepository = repo,
                    config =
                        OpsNotificationServiceConfig(
                            queueCapacity = 2,
                            maxAttempts = 1,
                        ),
                )

            service.start()
            service.enqueue(
                OpsDomainNotification(
                    clubId = 1,
                    event = OpsNotificationEvent.BOOKING_CREATED,
                    subjectId = "42",
                    occurredAt = Instant.parse("2025-01-02T03:04:05Z"),
                ),
            )
            service.enqueue(
                OpsDomainNotification(
                    clubId = 1,
                    event = OpsNotificationEvent.BOOKING_CREATED,
                    subjectId = "43",
                    occurredAt = Instant.parse("2025-01-02T03:04:06Z"),
                ),
            )

            withTimeout(1_000) {
                signal.await()
            }

            coVerify(exactly = 1) { client.sendMessage(456L, any(), 99) }

            service.stop()
        }
}
