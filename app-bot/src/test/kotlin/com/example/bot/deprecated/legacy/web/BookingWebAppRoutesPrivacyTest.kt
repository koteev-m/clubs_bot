package com.example.bot.deprecated.legacy.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
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
    fun `hq notifier propagates coroutine cancellation without retrying`() =
        runTest {
            val client = CancellingHttpClient()
            val notifier =
                TelegramLegacyHqNotifier(
                    token = "111111:token",
                    chatId = "1000",
                    client = client,
                )

            assertFailsWith<CancellationException> {
                notifier.notify("<b>cancel</b>")
            }
            assertTrue(client.sendAttempts == 1, "Cancellation must not be retried")
        }
}

private class CancellingHttpClient : HttpClient() {
    var sendAttempts: Int = 0
        private set

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        sendAttempts += 1
        throw CancellationException("cancelled")
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> =
        CompletableFuture.failedFuture(CancellationException("cancelled"))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> =
        CompletableFuture.failedFuture(CancellationException("cancelled"))

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
