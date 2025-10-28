package com.example.bot.routes

import com.example.bot.di.PaymentsService
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TEST_BOT_TOKEN = "test-token"

class PaymentsCancelRefundRoutesTest : StringSpec() {
    private val user = TelegramMiniUser(id = 123L, username = "tester")

    override suspend fun beforeEach(testCase: TestCase) {
        overrideMiniAppValidatorForTesting { _, _ -> user }
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        resetMiniAppValidator()
    }

    init {
        "cancel booking happy path and idempotency" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply { seed(clubId = 1L, bookingId = bookingId, capturedMinor = 1_000) }
            testApplication {
                application { configureForTest(service) }

                val payload = "{\"reason\":\"guest_cancelled\"}"
                val first =
                    client.post("/api/clubs/1/bookings/${bookingId}/cancel") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-cancel-1")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                first.status shouldBe HttpStatusCode.OK
                val firstBody = Json.decodeFromString<CancelResponse>(first.bodyAsText())
                firstBody.status shouldBe "CANCELLED"
                firstBody.bookingId shouldBe bookingId.toString()
                firstBody.idempotent shouldBe false
                firstBody.alreadyCancelled shouldBe false

                val second =
                    client.post("/api/clubs/1/bookings/${bookingId}/cancel") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-cancel-1")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                second.status shouldBe HttpStatusCode.OK
                val secondBody = Json.decodeFromString<CancelResponse>(second.bodyAsText())
                secondBody.idempotent shouldBe true
                secondBody.alreadyCancelled shouldBe false
            }
        }

        "refund full amount happy path" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply { seed(1L, bookingId, capturedMinor = 1_500) }
            testApplication {
                application { configureForTest(service) }

                val payload = "{\"amountMinor\":null}"
                val response =
                    client.post("/api/clubs/1/bookings/${bookingId}/refund") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-refund-full")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.decodeFromString<RefundResponse>(response.bodyAsText())
                body.status shouldBe "REFUNDED"
                body.refundAmountMinor shouldBe 1_500
                body.idempotent shouldBe false
            }
        }

        "refund partial amount and idempotency" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply { seed(1L, bookingId, capturedMinor = 2_000) }
            testApplication {
                application { configureForTest(service) }

                val payload = "{\"amountMinor\":700}"
                val first =
                    client.post("/api/clubs/1/bookings/${bookingId}/refund") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-refund-partial")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                first.status shouldBe HttpStatusCode.OK
                val firstBody = Json.decodeFromString<RefundResponse>(first.bodyAsText())
                firstBody.refundAmountMinor shouldBe 700
                firstBody.idempotent shouldBe false

                val second =
                    client.post("/api/clubs/1/bookings/${bookingId}/refund") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-refund-partial")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                second.status shouldBe HttpStatusCode.OK
                val secondBody = Json.decodeFromString<RefundResponse>(second.bodyAsText())
                secondBody.refundAmountMinor shouldBe 700
                secondBody.idempotent shouldBe true
            }
        }

        "missing auth yields 401" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply { seed(1L, bookingId, capturedMinor = 500) }
            testApplication {
                application { configureForTest(service) }

                val response =
                    client.post("/api/clubs/1/bookings/${bookingId}/cancel") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-unauth")
                        setBody("{\"reason\":\"no\"}")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "state conflict returns 409" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply { seed(1L, bookingId, status = FakePaymentsService.BookingStatus.CANCELLED) }
            testApplication {
                application { configureForTest(service) }

                val response =
                    client.post("/api/clubs/1/bookings/${bookingId}/cancel") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-conflict")
                        header("X-Telegram-Init-Data", "stub")
                        setBody("{\"reason\":\"already\"}")
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        "partial amount exceeding remainder yields 422" {
            val bookingId = UUID.randomUUID()
            val service = FakePaymentsService().apply {
                seed(1L, bookingId, capturedMinor = 900, refundedMinor = 400)
            }
            testApplication {
                application { configureForTest(service) }

                val response =
                    client.post("/api/clubs/1/bookings/${bookingId}/refund") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-refund-422")
                        header("X-Telegram-Init-Data", "stub")
                        setBody("{\"amountMinor\":600}")
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }
    }

    private fun Application.configureForTest(service: FakePaymentsService) {
        install(ContentNegotiation) { json() }
        install(Koin) {
            modules(
                module {
                    single<PaymentsService> { service }
                },
            )
        }
        paymentsCancelRefundRoutes { TEST_BOT_TOKEN }
    }
}

private class FakePaymentsService : PaymentsService {
    private data class CancelRecord(
        val clubId: Long,
        val bookingId: UUID,
        val alreadyCancelled: Boolean,
    )

    private data class RefundRecord(
        val clubId: Long,
        val bookingId: UUID,
        val amount: Long,
    )

    enum class BookingStatus { BOOKED, CANCELLED }

    private data class BookingState(
        var status: BookingStatus = BookingStatus.BOOKED,
        var capturedMinor: Long = 0,
        var refundedMinor: Long = 0,
    )

    private val bookings = ConcurrentHashMap<Pair<Long, UUID>, BookingState>()
    private val cancelHistory = ConcurrentHashMap<String, CancelRecord>()
    private val refundHistory = ConcurrentHashMap<String, RefundRecord>()

    fun seed(
        clubId: Long,
        bookingId: UUID,
        status: BookingStatus = BookingStatus.BOOKED,
        capturedMinor: Long = 0,
        refundedMinor: Long = 0,
    ) {
        bookings[clubId to bookingId] = BookingState(status, capturedMinor, refundedMinor)
    }

    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.FinalizeResult {
        return PaymentsService.FinalizeResult("NOOP")
    }

    override suspend fun cancel(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.CancelResult {
        val existing = cancelHistory[idemKey]
        if (existing != null) {
            if (existing.clubId != clubId || existing.bookingId != bookingId) {
                throw PaymentsService.ConflictException("idempotency mismatch")
            }
            return PaymentsService.CancelResult(
                bookingId = bookingId,
                idempotent = true,
                alreadyCancelled = existing.alreadyCancelled,
            )
        }

        val state = bookings[clubId to bookingId] ?: throw PaymentsService.ValidationException("booking not found")
        synchronized(state) {
            if (state.status != BookingStatus.BOOKED) {
                throw PaymentsService.ConflictException("cannot cancel")
            }
            state.status = BookingStatus.CANCELLED
        }
        cancelHistory[idemKey] = CancelRecord(clubId, bookingId, alreadyCancelled = false)
        return PaymentsService.CancelResult(
            bookingId = bookingId,
            idempotent = false,
            alreadyCancelled = false,
        )
    }

    override suspend fun refund(
        clubId: Long,
        bookingId: UUID,
        amountMinor: Long?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.RefundResult {
        val existing = refundHistory[idemKey]
        if (existing != null) {
            if (existing.clubId != clubId || existing.bookingId != bookingId) {
                throw PaymentsService.ConflictException("idempotency mismatch")
            }
            if (amountMinor != null && existing.amount != amountMinor) {
                throw PaymentsService.ConflictException("idempotency payload mismatch")
            }
            return PaymentsService.RefundResult(existing.amount, idempotent = true)
        }

        if (amountMinor != null && amountMinor < 0) {
            throw PaymentsService.ValidationException("amount must be non-negative")
        }

        val state = bookings[clubId to bookingId] ?: throw PaymentsService.ValidationException("booking not found")
        val resultAmount: Long
        synchronized(state) {
            val remainder = state.capturedMinor - state.refundedMinor
            if (remainder <= 0) {
                throw PaymentsService.ConflictException("nothing to refund")
            }
            val effective = amountMinor ?: remainder
            if (effective < 0) {
                throw PaymentsService.ValidationException("invalid amount")
            }
            if (effective > remainder) {
                throw PaymentsService.UnprocessableException("exceeds remainder")
            }
            state.refundedMinor += effective
            resultAmount = effective
        }
        refundHistory[idemKey] = RefundRecord(clubId, bookingId, resultAmount)
        return PaymentsService.RefundResult(resultAmount, idempotent = false)
    }
}
