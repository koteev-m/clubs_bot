package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.payments.finalize.PaymentsFinalizeService.ConflictException
import com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttribution
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionResult
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoLink
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.promo.PromoLinkToken
import com.example.bot.promo.PromoLinkTokenCodec
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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Instant
import java.util.UUID

class PaymentsFinalizeRoutesTest : StringSpec() {
    private val user = TelegramMiniUser(id = 42L, username = "tester")

    override suspend fun beforeEach(testCase: TestCase) {
        overrideMiniAppValidatorForTesting { _, _ -> user }
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        resetMiniAppValidator()
    }

    init {
        "happy path and idempotency" {
            val service = RecordingFinalizeService()
            testApplication {
                application { configureTestApp(service) }

                val bookingId = UUID.randomUUID().toString()
                val payload = Json.encodeToString(FinalizeRequest(bookingId = bookingId, paymentToken = "tok-value"))
                val first =
                    client.post("/api/clubs/1/bookings/finalize") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-1")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                first.status shouldBe HttpStatusCode.OK
                val firstBody = Json.decodeFromString<FinalizeResponse>(first.bodyAsText())
                firstBody.status shouldBe "OK"
                firstBody.paymentStatus shouldBe "CAPTURED"
                firstBody.promoAttached shouldBe false

                val second =
                    client.post("/api/clubs/1/bookings/finalize") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-1")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(payload)
                    }
                second.status shouldBe HttpStatusCode.OK
                second.bodyAsText() shouldBe first.bodyAsText()
            }
        }

        "401 when auth missing" {
            testApplication {
                application { configureTestApp(FailingFinalizeService()) }

                val response =
                    client.post("/api/clubs/1/bookings/finalize") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-401")
                        setBody(Json.encodeToString(FinalizeRequest(UUID.randomUUID().toString())))
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "invalid token yields 400" {
            testApplication {
                val validatingService =
                    object : PaymentsFinalizeService {
                        override suspend fun finalize(
                            clubId: Long,
                            bookingId: UUID,
                            paymentToken: String?,
                            idemKey: String,
                            actorUserId: Long,
                        ): FinalizeResult {
                            throw PaymentsFinalizeService.ValidationException("invalid payment token")
                        }
                    }
                application { configureTestApp(validatingService) }

                val response =
                    client.post("/api/clubs/1/bookings/finalize") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-400")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(Json.encodeToString(FinalizeRequest(UUID.randomUUID().toString(), paymentToken = "bad")))
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        "promo deep link attaches" {
            val service = RecordingFinalizeService()
            val promoLink =
                PromoLink(
                    id = 15L,
                    promoterUserId = 99L,
                    clubId = 1L,
                    utmSource = "telegram",
                    utmMedium = "bot",
                    utmCampaign = "promo",
                    utmContent = null,
                    createdAt = Instant.now(),
                )
            val promoService =
                PromoAttributionService(
                    promoLinkRepository = object : PromoLinkRepository {
                        override suspend fun issueLink(
                            promoterUserId: Long,
                            clubId: Long?,
                            utmSource: String,
                            utmMedium: String,
                            utmCampaign: String,
                            utmContent: String?,
                        ): PromoLink = error("not used")

                        override suspend fun get(id: Long): PromoLink? = if (id == promoLink.id) promoLink else null

                        override suspend fun listByPromoter(
                            promoterUserId: Long,
                            clubId: Long?,
                        ): List<PromoLink> = emptyList()

                        override suspend fun deactivate(id: Long) = error("not used")
                    },
                    promoAttributionRepository =
                        object : PromoAttributionRepository {
                            override suspend fun attachUnique(
                                bookingId: UUID,
                                promoLinkId: Long,
                                promoterUserId: Long,
                                utmSource: String,
                                utmMedium: String,
                                utmCampaign: String,
                                utmContent: String?,
                            ): PromoAttributionResult<PromoAttribution> {
                                return PromoAttributionResult.Success(
                                    PromoAttribution(
                                        id = 1L,
                                        bookingId = bookingId,
                                        promoLinkId = promoLinkId,
                                        promoterUserId = promoterUserId,
                                        utmSource = utmSource,
                                        utmMedium = utmMedium,
                                        utmCampaign = utmCampaign,
                                        utmContent = utmContent,
                                        createdAt = Instant.now(),
                                    ),
                                )
                            }

                            override suspend fun findByBooking(bookingId: UUID): PromoAttribution? = null
                        },
                    store = InMemoryPromoAttributionStore(),
                    userRepository = object : UserRepository {
                        override suspend fun getByTelegramId(id: Long): User? = null
                    },
                    userRoleRepository = object : UserRoleRepository {
                        override suspend fun listRoles(userId: Long): Set<Role> = emptySet()

                        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
                    },
                )
            testApplication {
                application { configureTestApp(service, promoService) }

                val encodedToken = PromoLinkTokenCodec.encode(PromoLinkToken(promoLinkId = promoLink.id, clubId = promoLink.clubId))
                val response =
                    client.post("/api/clubs/1/bookings/finalize") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-promo")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(
                            Json.encodeToString(
                                FinalizeRequest(
                                    bookingId = UUID.randomUUID().toString(),
                                    promoDeepLink = encodedToken,
                                ),
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                val payload = Json.decodeFromString<FinalizeResponse>(response.bodyAsText())
                payload.promoAttached shouldBe true
            }
        }
    }

    private class RecordingFinalizeService : PaymentsFinalizeService {
        private val responses = mutableMapOf<String, FinalizeResult>()

        override suspend fun finalize(
            clubId: Long,
            bookingId: UUID,
            paymentToken: String?,
            idemKey: String,
            actorUserId: Long,
        ): FinalizeResult {
            return responses.getOrPut(idemKey) { FinalizeResult("CAPTURED") }
        }
    }

    private class FailingFinalizeService : PaymentsFinalizeService {
        override suspend fun finalize(
            clubId: Long,
            bookingId: UUID,
            paymentToken: String?,
            idemKey: String,
            actorUserId: Long,
        ): FinalizeResult {
            throw ConflictException("should not be called")
        }
    }

    private fun Application.configureTestApp(
        paymentsService: PaymentsFinalizeService,
        promoService: PromoAttributionService? = null,
    ) {
        install(Koin) {
            modules(
                module {
                    single<PaymentsFinalizeService> { paymentsService }
                    promoService?.let { service -> single<PromoAttributionService> { service } }
                },
            )
        }
        install(ContentNegotiation) { json() }
        paymentsFinalizeRoutes { "token" }
    }
}
