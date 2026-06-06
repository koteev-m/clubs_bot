package com.example.bot.deprecated.legacy.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingWebAppRoutesPrivacyTest {
    @Test
    fun `hq notification text does not include qr secret and contains safe booking ref`() {
        val booking =
            BookingCreated(
                clubId = 1,
                eventId = 10,
                tableId = 20,
                tableNumber = 7,
                guestsCount = 3,
                minDeposit = "10000",
                totalDeposit = "30000",
                qrSecret = "super-secret-qr",
                bookingRef = "a1b2c3d4",
            )

        val text = buildNotifyText(booking, tgUserId = 1000L, user = "guest", display = "Guest")

        assertFalse(text.contains("super-secret-qr"))
        assertFalse(text.contains("QR:"))
        assertTrue(text.contains("Ref:"))
        assertTrue(text.contains("a1b2c3d4"))
    }

    @Test
    fun `hq notifier retries non success telegram response`() =
        runTest {
            val client = RecordingHttpClient(TelegramResult.Status(500), TelegramResult.Status(502))
            val notifier = telegramNotifier(client)

            notifier.notify("<b>retry</b>")

            assertEquals(2, client.sendAttempts)
        }

    @Test
    fun `hq notifier retries io exception`() =
        runTest {
            val client = RecordingHttpClient(TelegramResult.Failure(IOException("network")), TelegramResult.Status(502))
            val notifier = telegramNotifier(client)

            notifier.notify("<b>retry</b>")

            assertEquals(2, client.sendAttempts)
        }

    @Test
    fun `hq notifier stops after successful second attempt`() =
        runTest {
            val client =
                RecordingHttpClient(
                    TelegramResult.Status(500),
                    TelegramResult.Status(200),
                    TelegramResult.Status(500),
                )
            val notifier = telegramNotifier(client)

            notifier.notify("<b>ok</b>")

            assertEquals(2, client.sendAttempts)
        }

    @Test
    fun `hq notifier propagates coroutine cancellation without retrying`() =
        runTest {
            val client = RecordingHttpClient(TelegramResult.Failure(CancellationException("cancelled")))
            val notifier = telegramNotifier(client)

            assertFailsWith<CancellationException> {
                notifier.notify("<b>cancel</b>")
            }
            assertEquals(1, client.sendAttempts, "Cancellation must not be retried")
        }

    @Test
    fun `legacy booking insert classifier treats constraint errors as conflict`() {
        assertTrue(isLegacyBookingInsertConflict(RuntimeException(SQLException("duplicate", "23505"))))
        assertTrue(isLegacyBookingInsertConflict(SQLException("foreign key", "23503")))
    }

    @Test
    fun `legacy booking insert classifier keeps runtime and connection failures unexpected`() {
        assertFalse(isLegacyBookingInsertConflict(RuntimeException("boom")))
        assertFalse(isLegacyBookingInsertConflict(SQLException("connection", "08006")))
    }

    private fun telegramNotifier(client: HttpClient): TelegramLegacyHqNotifier =
        TelegramLegacyHqNotifier(
            token = "111111:token",
            chatId = "1000",
            client = client,
        )
}

private sealed interface TelegramResult {
    data class Status(val code: Int) : TelegramResult

    data class Failure(val error: Throwable) : TelegramResult
}

private class RecordingHttpClient(vararg results: TelegramResult) : HttpClient() {
    private val results = ArrayDeque(results.toList())

    var sendAttempts: Int = 0
        private set

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        sendAttempts += 1
        return when (val result = results.removeFirst()) {
            is TelegramResult.Status -> EmptyHttpResponse(result.code, request)
            is TelegramResult.Failure -> throw result.error
        }
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("unused"))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("unused"))

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()
}

private class EmptyHttpResponse<T>(
    private val statusCode: Int,
    private val request: HttpRequest,
) : HttpResponse<T> {
    override fun statusCode(): Int = statusCode

    override fun request(): HttpRequest = request

    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    @Suppress("UNCHECKED_CAST")
    override fun body(): T = null as T

    override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

    override fun uri(): URI = request.uri()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
