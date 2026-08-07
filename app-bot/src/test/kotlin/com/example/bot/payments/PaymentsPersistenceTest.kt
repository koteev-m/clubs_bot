package com.example.bot.payments

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.data.db.Clubs
import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentActionsTable
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentRefundsTable
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentsTable
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.Role
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.http.ApiError
import com.example.bot.http.ErrorCodes
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.routes.RefundResponse
import com.example.bot.routes.paymentsCancelRefundRoutes
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.PostgresAppTest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import testing.RequiresDocker
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private object PaymentsRbacUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object PaymentsRbacUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

@RequiresDocker
@Suppress("LargeClass")
class PaymentsPersistenceTest : PostgresAppTest() {
    @Test
    fun `cancel persists action and updates booking`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 1L, idemKey = "booking-1")

            val result =
                service.service.cancel(
                    clubId = booking.clubId,
                    bookingId = booking.id,
                    reason = "guest_request",
                    idemKey = "cancel-1",
                    actorUserId = 42L,
                )

            assertEquals(false, result.idempotent)
            assertEquals(false, result.alreadyCancelled)

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-1")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)
            assertEquals("guest_request", saved.result.reason)

            val status = currentBookingStatus(booking.id)
            assertEquals(BookingStatus.CANCELLED, status)
        }

    @Test
    fun `cancel idempotency returns stored result`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 2L, idemKey = "booking-2")

            service.service.cancel(booking.clubId, booking.id, "original", "cancel-repeat", 7L)
            val second = service.service.cancel(booking.clubId, booking.id, "changed", "cancel-repeat", 7L)

            assertTrue(second.idempotent)
            assertEquals(false, second.alreadyCancelled)

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-repeat")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)
            assertEquals("original", saved.result.reason)

            val actionsCount = transaction(database) { PaymentActionsTable.selectAll().count() }
            assertEquals(1, actionsCount)
        }

    @Test
    fun `cancel with blank idemKey does not persist payment_action`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 7L, idemKey = "booking-7")

            val result =
                service.service.cancel(
                    clubId = booking.clubId,
                    bookingId = booking.id,
                    reason = "guest_request",
                    idemKey = "   ",
                    actorUserId = 101L,
                )

            assertEquals(false, result.idempotent)
            assertEquals(false, result.alreadyCancelled)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))

            val cancelActionsForBooking =
                transaction(database) {
                    val actions =
                        PaymentActionsTable
                            .selectAll()
                            .where {
                                (PaymentActionsTable.bookingId eq booking.id) and
                                    (PaymentActionsTable.action eq PaymentsRepository.Action.CANCEL.name)
                            }
                    actions.count()
                }
            assertEquals(0, cancelActionsForBooking)
            assertEquals(null, service.paymentsRepo.findActionByIdempotencyKey("   "))
        }

    @Test
    fun `cancel rejects idempotency key reused for different booking`() =
        runBlocking {
            val service = createService()
            val booking1 = seedBooking(status = BookingStatus.BOOKED, clubId = 8L, idemKey = "booking-8-1")
            val booking2 = seedBooking(status = BookingStatus.BOOKED, clubId = 9L, idemKey = "booking-8-2")

            service.service.cancel(booking1.clubId, booking1.id, "first", "same-key", 201L)

            try {
                service.service.cancel(booking2.clubId, booking2.id, "second", "same-key", 202L)
                fail("expected validation")
            } catch (validation: PaymentsService.ValidationException) {
                assertEquals("idempotency key already used for different operation", validation.message)
            }

            assertEquals(BookingStatus.BOOKED, currentBookingStatus(booking2.id))

            val sameKeyActions =
                transaction(database) {
                    PaymentActionsTable
                        .selectAll()
                        .where { PaymentActionsTable.idempotencyKey eq "same-key" }
                        .count()
                }
            assertEquals(1, sameKeyActions)
        }

    @Test
    fun `cancel concurrent duplicate idempotency key does not fail with 500`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 5L, idemKey = "booking-5")

            val results =
                listOf(1, 2)
                    .map {
                        async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            service.service.cancel(
                                clubId = booking.clubId,
                                bookingId = booking.id,
                                reason = "race",
                                idemKey = "cancel-race",
                                actorUserId = 99L,
                            )
                        }
                    }
            results.forEach { it.start() }
            val completedResults = results.awaitAll()

            assertEquals(2, completedResults.size)
            assertTrue(completedResults.all { it.bookingId == booking.id })
            assertEquals(setOf(false, true), completedResults.map { it.idempotent }.toSet())
            assertTrue(completedResults.none { it.alreadyCancelled })
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))

            val actionsCount =
                transaction(database) {
                    PaymentActionsTable
                        .selectAll()
                        .where { PaymentActionsTable.idempotencyKey eq "cancel-race" }
                        .count()
                }
            assertEquals(1, actionsCount)
            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-race")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)
        }

    @Test
    fun `cancel concurrent duplicate key is atomic across service instances`() =
        runBlocking {
            val first = createService(database, PaymentsRepositoryImpl(database))
            val secondApplicationName = "cancel-replay-${UUID.randomUUID()}"
            val secondDatabase = connectToPostgres(secondApplicationName)
            val second = createService(secondDatabase, PaymentsRepositoryImpl(secondDatabase))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 50L, idemKey = "booking-50")
            val lockNamespace = 73_009
            val lockKey = 73_010
            val fixture = installBlockingCancelTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            val results =
                try {
                    acquireAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    lockHeld = true
                    val winner =
                        async(Dispatchers.Default) {
                            first.service.cancel(booking.clubId, booking.id, "first", "cancel-two-instances", 88L)
                        }
                    awaitAdvisoryWait(lockNamespace, lockKey)
                    val replay =
                        async(Dispatchers.Default) {
                            second.service.cancel(booking.clubId, booking.id, "changed", "cancel-two-instances", 89L)
                        }
                    awaitTransactionWait(secondApplicationName)
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    lockHeld = false
                    listOf(winner.await(), replay.await())
                } finally {
                    if (lockHeld) {
                        releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    }
                    lockConnection.close()
                    dropActionTrigger(fixture)
                }

            assertEquals(setOf(false, true), results.map { it.idempotent }.toSet())
            assertTrue(results.none { it.alreadyCancelled })
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
            assertEquals(1, actionRowsCount("cancel-two-instances"))
            val stored = first.paymentsRepo.findActionByIdempotencyKey("cancel-two-instances")
            assertEquals("first", stored?.result?.reason)
            assertEquals(PaymentsRepository.Result.Status.OK, stored?.result?.status)
        }

    @Test
    fun `cancel wins global key and concurrent refund does not mutate`() =
        runBlocking {
            val first = createService(database, PaymentsRepositoryImpl(database))
            val secondApplicationName = "cancel-wins-refund-loser-${UUID.randomUUID()}"
            val secondDatabase = connectToPostgres(secondApplicationName)
            val second = createService(secondDatabase, PaymentsRepositoryImpl(secondDatabase))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 51L, idemKey = "booking-51")
            seedPayment(booking, amountMinor = 500)
            val lockNamespace = 73_001
            val lockKey = 73_002
            val fixture = installBlockingCancelTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            try {
                acquireAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = true
                val cancel =
                    async(Dispatchers.Default) {
                        first.service.cancel(booking.clubId, booking.id, "winner", "cross-action-cancel", 90L)
                    }
                awaitAdvisoryWait(lockNamespace, lockKey)
                val refund =
                    async(Dispatchers.Default) {
                        expectValidation {
                            second.service.refund(
                                booking.clubId,
                                booking.id,
                                300,
                                "cross-action-cancel",
                                91L,
                            )
                        }
                    }
                awaitTransactionWait(secondApplicationName)
                releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = false

                val cancelResult = cancel.await()
                val refundError = refund.await()
                assertFalse(cancelResult.idempotent)
                assertEquals(
                    "idempotency key already used for different operation",
                    refundError.message,
                )
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                }
                lockConnection.close()
                dropActionTrigger(fixture)
            }

            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
            assertEquals(1, actionRowsCount("cross-action-cancel"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))
        }

    @Test
    fun `refund wins global key and concurrent cancel does not mutate`() =
        runBlocking {
            val first = createService(database, PaymentsRepositoryImpl(database))
            val secondApplicationName = "refund-wins-cancel-loser-${UUID.randomUUID()}"
            val secondDatabase = connectToPostgres(secondApplicationName)
            val second = createService(secondDatabase, PaymentsRepositoryImpl(secondDatabase))
            val refundBooking = seedBooking(status = BookingStatus.BOOKED, clubId = 52L, idemKey = "booking-52")
            val cancelBooking = seedBooking(status = BookingStatus.BOOKED, clubId = 520L, idemKey = "booking-520")
            seedPayment(refundBooking, amountMinor = 500)
            val lockNamespace = 73_003
            val lockKey = 73_004
            val fixture = installBlockingRefundTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            try {
                acquireAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = true
                val refund =
                    async(Dispatchers.Default) {
                        first.service.refund(
                            refundBooking.clubId,
                            refundBooking.id,
                            300,
                            "cross-action-refund",
                            92L,
                        )
                    }
                awaitAdvisoryWait(lockNamespace, lockKey)
                val cancel =
                    async(Dispatchers.Default) {
                        expectValidation {
                            second.service.cancel(
                                cancelBooking.clubId,
                                cancelBooking.id,
                                "loser",
                                "cross-action-refund",
                                93L,
                            )
                        }
                    }
                awaitTransactionWait(secondApplicationName)
                releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = false

                val refundResult = refund.await()
                val cancelError = cancel.await()
                assertEquals(300, refundResult.refundAmountMinor)
                assertEquals(
                    "idempotency key already used for different operation",
                    cancelError.message,
                )
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                }
                lockConnection.close()
                dropRefundTrigger(fixture)
            }

            assertEquals(BookingStatus.BOOKED, currentBookingStatus(refundBooking.id))
            assertEquals(BookingStatus.BOOKED, currentBookingStatus(cancelBooking.id))
            assertEquals(1, actionRowsCount("cross-action-refund"))
            assertEquals(1, refundRowsCount(refundBooking.id))
            assertEquals(0, refundRowsCount(cancelBooking.id))
            assertEquals(300, refundedTotal(refundBooking.id))
            assertEquals(200, remainingAmount(refundBooking.id))
        }

    @Test
    fun `cancel rollback removes global claim and booking mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 53L, idemKey = "booking-53")
            val fixture = installFailingCancelTrigger()

            try {
                var failed = false
                try {
                    service.service.cancel(booking.clubId, booking.id, "failure", "cancel-rollback", 94L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
                assertTrue(failed, "expected controlled database failure")
            } finally {
                dropActionTrigger(fixture)
            }

            assertEquals(BookingStatus.BOOKED, currentBookingStatus(booking.id))
            assertNull(repository.findActionByIdempotencyKey("cancel-rollback"))
            val retry = service.service.cancel(booking.clubId, booking.id, "retry", "cancel-rollback", 94L)
            assertFalse(retry.idempotent)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
        }

    @Test
    fun `cancel cancellation rolls back claim and booking mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 54L, idemKey = "booking-54")
            val lockNamespace = 73_005
            val lockKey = 73_006
            val fixture = installBlockingCancelTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            try {
                acquireAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = true
                val deferred =
                    async(Dispatchers.Default) {
                        service.service.cancel(booking.clubId, booking.id, "cancel", "cancel-cancelled", 95L)
                    }
                awaitAdvisoryWait(lockNamespace, lockKey)
                deferred.cancel(CancellationException("controlled cancellation"))
                releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = false
                try {
                    deferred.await()
                    fail("expected cancellation")
                } catch (_: CancellationException) {
                    // Expected: ensureActive before Exposed commit forces rollback.
                }
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                }
                lockConnection.close()
                dropActionTrigger(fixture)
            }

            assertEquals(BookingStatus.BOOKED, currentBookingStatus(booking.id))
            assertNull(repository.findActionByIdempotencyKey("cancel-cancelled"))
            val retry = service.service.cancel(booking.clubId, booking.id, null, "cancel-cancelled", 95L)
            assertFalse(retry.idempotent)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
            assertEquals(1, actionRowsCount("cancel-cancelled"))
            assertEquals(
                PaymentsRepository.Result.Status.OK,
                repository.findActionByIdempotencyKey("cancel-cancelled")?.result?.status,
            )
        }

    @Test
    fun `different booking duplicate claim does not log raw idempotency key`() =
        runBlocking {
            val secretKey = "refund-secret-key-${UUID.randomUUID()}"
            val winner = createService(database, PaymentsRepositoryImpl(database))
            val loserApplicationName = "raw-key-loser-${UUID.randomUUID()}"
            val loserDatabase = connectToPostgres(loserApplicationName)
            val loser = createService(loserDatabase, PaymentsRepositoryImpl(loserDatabase))
            val winnerBooking = seedBooking(status = BookingStatus.BOOKED, clubId = 55L, idemKey = "booking-55-a")
            val loserBooking = seedBooking(status = BookingStatus.BOOKED, clubId = 56L, idemKey = "booking-55-b")
            seedPayment(loserBooking, amountMinor = 500)
            val lockNamespace = 73_007
            val lockKey = 73_008
            val fixture = installBlockingCancelTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false
            val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)

            try {
                acquireAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = true
                val cancel =
                    async(Dispatchers.Default) {
                        winner.service.cancel(
                            winnerBooking.clubId,
                            winnerBooking.id,
                            "winner",
                            secretKey,
                            96L,
                        )
                    }
                awaitAdvisoryWait(lockNamespace, lockKey)
                val refund =
                    async(Dispatchers.Default) {
                        expectValidation {
                            loser.service.refund(
                                loserBooking.clubId,
                                loserBooking.id,
                                300,
                                secretKey,
                                97L,
                            )
                        }
                    }
                awaitTransactionWait(loserApplicationName)
                releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = false

                assertFalse(cancel.await().idempotent)
                assertEquals(
                    "idempotency key already used for different operation",
                    refund.await().message,
                )
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                }
                lockConnection.close()
                dropActionTrigger(fixture)
                rootLogger.detachAppender(appender)
                appender.stop()
            }

            val emittedLogs =
                appender.list.joinToString("\n") { event ->
                    buildString {
                        append(event.formattedMessage)
                        event.argumentArray?.let { arguments -> append(arguments.contentDeepToString()) }
                        append(event.mdcPropertyMap.values.joinToString(" "))
                        event.throwableProxy?.let { proxy ->
                            append(ThrowableProxyUtil.asString(proxy))
                        }
                    }
                }
            assertFalse(emittedLogs.contains(secretKey), emittedLogs)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(winnerBooking.id))
            assertEquals(BookingStatus.BOOKED, currentBookingStatus(loserBooking.id))
            assertEquals(1, actionRowsCount(secretKey))
            assertEquals(0, refundRowsCount(loserBooking.id))
            assertEquals(500, remainingAmount(loserBooking.id))
        }

    @Test
    fun `refund concurrent duplicate idempotency key does not fail with 500`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val gate = RefundStartGate(parties = 2)
            val service = createService(paymentsRepo = GatedPaymentsRepository(repository, gate))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 6L, idemKey = "booking-6")
            seedPayment(booking, amountMinor = 500)
            val lockNamespace = 72_001
            val lockKey = 72_002
            val fixture = installBlockingRefundTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            val results =
                try {
                    lockConnection.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_lock($lockNamespace, $lockKey)")
                    }
                    lockHeld = true
                    val deferred =
                        listOf(1, 2)
                            .map {
                                async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                                    service.service.refund(
                                        clubId = booking.clubId,
                                        bookingId = booking.id,
                                        amountMinor = 300,
                                        idemKey = "refund-race",
                                        actorUserId = 100L,
                                    )
                                }
                            }.also { requests -> requests.forEach { it.start() } }
                    awaitAdvisoryWait(lockNamespace, lockKey)
                    awaitTransactionWait()
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    lockHeld = false
                    deferred.awaitAll()
                } finally {
                    if (lockHeld) {
                        releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    }
                    lockConnection.close()
                    dropRefundTrigger(fixture)
                }

            assertEquals(2, results.size)
            assertTrue(results.all { it.refundAmountMinor == 300L })
            assertEquals(setOf(false, true), results.map { it.idempotent }.toSet())

            val actionsCount =
                transaction(database) {
                    PaymentActionsTable
                        .selectAll()
                        .where { PaymentActionsTable.idempotencyKey eq "refund-race" }
                        .count()
                }
            assertEquals(1, actionsCount)
            assertEquals(1, refundRowsCount(booking.id))
            assertEquals(300, refundedTotal(booking.id))
            assertEquals(200, remainingAmount(booking.id))

            val saved = repository.findActionByIdempotencyKey("refund-race")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)
            assertEquals(300, saved.refundResultAmountMinor)
            assertEquals(
                PaymentsRepository.RefundFingerprint(
                    mode = PaymentsRepository.RefundRequestMode.EXPLICIT,
                    requestAmountMinor = 300,
                ),
                saved.refundFingerprint,
            )
        }

    @Test
    fun `refund concurrent duplicate is atomic across two service instances`() =
        runBlocking {
            val secondDatabase = connectToPostgres()
            val firstRepository = PaymentsRepositoryImpl(database)
            val secondRepository = PaymentsRepositoryImpl(secondDatabase)
            val gate = RefundStartGate(parties = 2)
            val first = createService(database, GatedPaymentsRepository(firstRepository, gate))
            val second = createService(secondDatabase, GatedPaymentsRepository(secondRepository, gate))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 61L, idemKey = "booking-61")
            seedPayment(booking, amountMinor = 500)
            val lockNamespace = 72_003
            val lockKey = 72_004
            val fixture = installBlockingRefundTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            val results =
                try {
                    lockConnection.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_lock($lockNamespace, $lockKey)")
                    }
                    lockHeld = true
                    val deferred =
                        listOf(first.service, second.service)
                            .map { service ->
                                async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                                    service.refund(booking.clubId, booking.id, 300, "refund-two-services", 100L)
                                }
                            }.also { requests -> requests.forEach { it.start() } }
                    awaitAdvisoryWait(lockNamespace, lockKey)
                    awaitTransactionWait()
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    lockHeld = false
                    deferred.awaitAll()
                } finally {
                    if (lockHeld) {
                        releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                    }
                    lockConnection.close()
                    dropRefundTrigger(fixture)
                }

            assertTrue(results.all { it.refundAmountMinor == 300L })
            assertEquals(setOf(false, true), results.map { it.idempotent }.toSet())
            assertEquals(1, actionRowsCount("refund-two-services"))
            assertEquals(1, refundRowsCount(booking.id))
            assertEquals(300, refundedTotal(booking.id))
            assertEquals(200, remainingAmount(booking.id))
        }

    @Test
    fun `refund explicit zero persists terminal success without mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 610L, idemKey = "booking-zero-service")
            val paymentId = seedPayment(booking, amountMinor = 500)
            val key = "refund-explicit-zero-service"

            val first = service.service.refund(booking.clubId, booking.id, 0, key, 610L)
            val replay = service.service.refund(booking.clubId, booking.id, 0, key, 610L)
            val amountMismatch =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 1, key, 610L)
                }
            val modeMismatch =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, null, key, 610L)
                }

            assertEquals(0, first.refundAmountMinor)
            assertFalse(first.idempotent)
            assertEquals(0, replay.refundAmountMinor)
            assertTrue(replay.idempotent)
            assertEquals("idempotency payload mismatch", amountMismatch.message)
            assertEquals("idempotency payload mismatch", modeMismatch.message)
            assertZeroRefundPersistence(booking, paymentId, key)
        }

    @Test
    fun `refund explicit zero production RBAC route replays stable public result without mutation`() {
        val repository = PaymentsRepositoryImpl(database)
        val service = createService(paymentsRepo = repository)
        val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 611L, idemKey = "booking-zero-route")
        val foreignBooking =
            seedBooking(
                status = BookingStatus.BOOKED,
                clubId = 612L,
                idemKey = "booking-zero-rbac-scope",
            )
        val paymentId = seedPayment(booking, amountMinor = 500)
        val key = "refund-explicit-zero-route"
        val deniedKey = "refund-explicit-zero-route-denied"
        val deniedRequestId = "zero-refund-denied-request-611"
        val authorizedTelegramId = 611_001L
        val deniedTelegramId = 611_002L
        seedRbacActor(authorizedTelegramId, "zero-refund-authorized", Role.MANAGER, booking.clubId)
        seedRbacActor(deniedTelegramId, "zero-refund-denied", Role.MANAGER, foreignBooking.clubId)
        overrideMiniAppValidatorForTesting { initData, _ ->
            when (initData) {
                "authorized-zero-rbac" -> TelegramMiniUser(authorizedTelegramId, "zero-refund-authorized")
                "denied-zero-rbac" -> TelegramMiniUser(deniedTelegramId, "zero-refund-denied")
                else -> null
            }
        }

        try {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.APP_PROFILE" to "STAGE",
                            "app.RBAC_ENABLED" to "true",
                            "app.REFUND_ENABLED" to "true",
                        )
                }
                application {
                    configureLoggingAndRequestId()
                    install(ContentNegotiation) { json() }
                    installJsonErrorPages()
                    install(Koin) {
                        modules(
                            module {
                                single<PaymentsService> { service.service }
                            },
                        )
                    }
                    install(RbacPlugin) {
                        userRepository = ExposedUserRepository(database)
                        userRoleRepository = ExposedUserRoleRepository(database)
                        auditLogRepository = AuditLogRepositoryImpl(database)
                        principalExtractor = { call ->
                            if (call.attributes.contains(MiniAppUserKey)) {
                                val miniAppUser = call.attributes[MiniAppUserKey]
                                TelegramPrincipal(miniAppUser.id, miniAppUser.username)
                            } else {
                                call.request.headers["X-Telegram-Id"]?.toLongOrNull()?.let { telegramId ->
                                    TelegramPrincipal(
                                        telegramId,
                                        call.request.headers["X-Telegram-Username"],
                                    )
                                }
                            }
                        }
                    }
                    paymentsCancelRefundRoutes { "zero-refund-route-token" }
                }

                val path = "/api/clubs/${booking.clubId}/bookings/${booking.id}/refund"
                val first =
                    client.postRefund(
                        path,
                        key,
                        """{"amountMinor":0}""",
                        "authorized-zero-rbac",
                        authorizedTelegramId,
                    )
                val replay =
                    client.postRefund(
                        path,
                        key,
                        """{"amountMinor":0}""",
                        "authorized-zero-rbac",
                        authorizedTelegramId,
                    )
                val amountMismatch =
                    client.postRefund(
                        path,
                        key,
                        """{"amountMinor":1}""",
                        "authorized-zero-rbac",
                        authorizedTelegramId,
                    )
                val modeMismatch =
                    client.postRefund(
                        path,
                        key,
                        """{"amountMinor":null}""",
                        "authorized-zero-rbac",
                        authorizedTelegramId,
                    )
                val denied =
                    client.postRefund(
                        path,
                        deniedKey,
                        """{"amountMinor":0}""",
                        "denied-zero-rbac",
                        deniedTelegramId,
                        deniedRequestId,
                    )

                assertEquals(HttpStatusCode.OK, first.status)
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals(HttpStatusCode.Conflict, amountMismatch.status)
                assertEquals(HttpStatusCode.Conflict, modeMismatch.status)
                assertEquals(HttpStatusCode.Forbidden, denied.status)

                val firstBody = Json.decodeFromString<RefundResponse>(first.bodyAsText())
                val replayBody = Json.decodeFromString<RefundResponse>(replay.bodyAsText())
                assertEquals("REFUNDED", firstBody.status)
                assertEquals(booking.id.toString(), firstBody.bookingId)
                assertEquals(0, firstBody.refundAmountMinor)
                assertFalse(firstBody.idempotent)
                assertEquals(firstBody.copy(idempotent = true), replayBody)
                assertEquals(0, replayBody.refundAmountMinor)
                assertTrue(replayBody.idempotent)
                val expectedMismatch = mapOf("error" to "idempotency payload mismatch")
                assertEquals(expectedMismatch, Json.decodeFromString<Map<String, String>>(amountMismatch.bodyAsText()))
                assertEquals(expectedMismatch, Json.decodeFromString<Map<String, String>>(modeMismatch.bodyAsText()))
                val deniedBody = Json.decodeFromString<ApiError>(denied.bodyAsText())
                assertEquals(ErrorCodes.forbidden, deniedBody.code)
                assertEquals(HttpStatusCode.Forbidden.value, deniedBody.status)
                assertEquals(deniedRequestId, deniedBody.requestId)
                assertNull(deniedBody.message)
                assertNull(deniedBody.details)
            }
        } finally {
            resetMiniAppValidator()
        }

        assertZeroRefundPersistence(booking, paymentId, key)
        assertEquals(0, actionRowsCount(deniedKey))
        assertEquals(0, refundRowsCount(booking.id))
        assertEquals(500, remainingAmount(booking.id))
    }

    @Test
    fun `refund same key with different explicit amount is payload mismatch`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 62L, idemKey = "booking-62")
            seedPayment(booking, amountMinor = 500)

            val first = service.service.refund(booking.clubId, booking.id, 300, "refund-mismatch", 101L)
            val mismatch =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 200, "refund-mismatch", 101L)
                }

            assertEquals(300, first.refundAmountMinor)
            assertEquals("idempotency payload mismatch", mismatch.message)
            assertEquals(1, actionRowsCount("refund-mismatch"))
            assertEquals(300, refundedTotal(booking.id))
            assertEquals(200, remainingAmount(booking.id))
            assertEquals(300, repository.findActionByIdempotencyKey("refund-mismatch")?.refundResultAmountMinor)
        }

    @Test
    fun `refund all remaining and explicit amount are different fingerprints`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 63L, idemKey = "booking-63")
            seedPayment(booking, amountMinor = 500)

            val first = service.service.refund(booking.clubId, booking.id, null, "refund-mode-mismatch", 102L)
            val mismatch =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 500, "refund-mode-mismatch", 102L)
                }

            assertEquals(500, first.refundAmountMinor)
            assertEquals("idempotency payload mismatch", mismatch.message)
            assertEquals(1, actionRowsCount("refund-mode-mismatch"))
            assertEquals(500, refundedTotal(booking.id))
        }

    @Test
    fun `refund legacy action without fingerprint fails closed without mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 631L, idemKey = "booking-631")
            seedPayment(booking, amountMinor = 500)
            transaction(database) {
                PaymentActionsTable.insert {
                    it[PaymentActionsTable.bookingId] = booking.id
                    it[idempotencyKey] = "refund-legacy-ambiguous"
                    it[action] = PaymentsRepository.Action.REFUND.name
                    it[status] = PaymentsRepository.Result.Status.OK.name
                    it[reason] = "100"
                }
            }

            val mismatch =
                expectConflict {
                    service.service.refund(
                        booking.clubId,
                        booking.id,
                        100,
                        "refund-legacy-ambiguous",
                        102L,
                    )
                }

            assertEquals("idempotency payload mismatch", mismatch.message)
            assertEquals(1, actionRowsCount("refund-legacy-ambiguous"))
            assertEquals(1, refundRowsCount(booking.id))
            assertEquals(100, refundedTotal(booking.id))
            assertEquals(400, remainingAmount(booking.id))
        }

    @Test
    fun `refund stored exceeds remainder error replays as unprocessable`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 64L, idemKey = "booking-64")
            seedPayment(booking, amountMinor = 500)

            val first =
                expectUnprocessable {
                    service.service.refund(booking.clubId, booking.id, 600, "refund-error-replay", 103L)
                }
            val replay =
                expectUnprocessable {
                    service.service.refund(booking.clubId, booking.id, 600, "refund-error-replay", 103L)
                }

            assertEquals("exceeds remainder", first.message)
            assertEquals(first.message, replay.message)
            assertEquals(1, actionRowsCount("refund-error-replay"))
            val savedAction = repository.findActionByIdempotencyKey("refund-error-replay")
            assertEquals(PaymentsRepository.Result.Status.ERROR, savedAction?.result?.status)
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))
        }

    @Test
    fun `refund nothing to refund conflict replays unchanged`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 65L, idemKey = "booking-65")

            val first =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, null, "refund-conflict-replay", 104L)
                }
            val replay =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, null, "refund-conflict-replay", 104L)
                }

            assertEquals("nothing to refund", first.message)
            assertEquals(first.message, replay.message)
            assertEquals(1, actionRowsCount("refund-conflict-replay"))
            val savedAction = repository.findActionByIdempotencyKey("refund-conflict-replay")
            assertEquals(PaymentsRepository.Result.Status.CONFLICT, savedAction?.result?.status)
            assertEquals(0, refundRowsCount(booking.id))
        }

    @Test
    fun `refund different keys serialize on booking and both succeed when funds allow`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val gate = RefundStartGate(parties = 2)
            val service = createService(paymentsRepo = GatedPaymentsRepository(repository, gate))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 66L, idemKey = "booking-66")
            seedPayment(booking, amountMinor = 500)

            val requests = listOf("refund-key-a" to 200L, "refund-key-b" to 300L)
            val results =
                requests
                    .map { (key, amount) ->
                        async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            service.service.refund(booking.clubId, booking.id, amount, key, 105L)
                        }
                    }.also { deferred -> deferred.forEach { it.start() } }
                    .awaitAll()

            assertEquals(setOf(200L, 300L), results.map { it.refundAmountMinor }.toSet())
            assertTrue(results.none { it.idempotent })
            assertEquals(2, refundActionRowsCount(booking.id))
            assertEquals(2, refundRowsCount(booking.id))
            assertEquals(500, refundedTotal(booking.id))
            assertEquals(0, remainingAmount(booking.id))
        }

    @Test
    fun `refund different keys cannot overdraw booking remainder`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val gate = RefundStartGate(parties = 2)
            val service = createService(paymentsRepo = GatedPaymentsRepository(repository, gate))
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 67L, idemKey = "booking-67")
            seedPayment(booking, amountMinor = 500)

            val outcomes =
                listOf("refund-overdraw-a" to 400L, "refund-overdraw-b" to 200L)
                    .map { (key, amount) ->
                        async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            try {
                                service.service.refund(booking.clubId, booking.id, amount, key, 106L)
                                "OK"
                            } catch (unprocessable: PaymentsService.UnprocessableException) {
                                assertEquals("exceeds remainder", unprocessable.message)
                                "ERROR"
                            }
                        }
                    }.also { deferred -> deferred.forEach { it.start() } }
                    .awaitAll()

            assertEquals(1, outcomes.count { it == "OK" })
            assertEquals(1, outcomes.count { it == "ERROR" })
            assertEquals(2, refundActionRowsCount(booking.id))
            assertEquals(1, refundRowsCount(booking.id))
            assertTrue(refundedTotal(booking.id) in setOf(200L, 400L))
            assertTrue(remainingAmount(booking.id) >= 0)
        }

    @Test
    fun `refund different keys cannot overdraw across production isolation pools`() =
        runBlocking {
            repeatableReadDataSource().use { firstDataSource ->
                repeatableReadDataSource().use { secondDataSource ->
                    val firstDatabase = Database.connect(firstDataSource)
                    val secondDatabase = Database.connect(secondDataSource)
                    val firstRepository = PaymentsRepositoryImpl(firstDatabase)
                    val secondRepository = PaymentsRepositoryImpl(secondDatabase)
                    val gate = RefundStartGate(parties = 2)
                    val first = createService(firstDatabase, GatedPaymentsRepository(firstRepository, gate))
                    val second = createService(secondDatabase, GatedPaymentsRepository(secondRepository, gate))
                    val booking =
                        seedBooking(
                            status = BookingStatus.BOOKED,
                            clubId = 671L,
                            idemKey = "booking-671",
                        )
                    seedPayment(booking, amountMinor = 500)

                    val outcomes =
                        listOf(first.service to ("refund-rr-a" to 400L), second.service to ("refund-rr-b" to 200L))
                            .map { (service, request) ->
                                async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                                    try {
                                        service.refund(
                                            booking.clubId,
                                            booking.id,
                                            request.second,
                                            request.first,
                                            106L,
                                        )
                                        "OK"
                                    } catch (unprocessable: PaymentsService.UnprocessableException) {
                                        assertEquals("exceeds remainder", unprocessable.message)
                                        "ERROR"
                                    }
                                }
                            }.also { deferred -> deferred.forEach { it.start() } }
                            .awaitAll()

                    assertEquals(1, outcomes.count { it == "OK" })
                    assertEquals(1, outcomes.count { it == "ERROR" })
                    assertEquals(2, refundActionRowsCount(booking.id))
                    assertEquals(1, refundRowsCount(booking.id))
                    assertTrue(refundedTotal(booking.id) in setOf(200L, 400L))
                    assertTrue(remainingAmount(booking.id) >= 0)
                }
            }
        }

    @Test
    fun `refund mixed captured currencies fails closed`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 68L, idemKey = "booking-68")
            seedPayment(booking, amountMinor = 300, currency = "RUB")
            seedPayment(booking, amountMinor = 200, currency = "USD")

            val conflict =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 100, "refund-mixed-currency", 107L)
                }

            assertEquals("captured payments currency mismatch", conflict.message)
            assertEquals(1, actionRowsCount("refund-mixed-currency"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))
        }

    @Test
    fun `refund wrong club cannot claim or mutate booking`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 681L, idemKey = "booking-681")
            seedPayment(booking, amountMinor = 500)

            val conflict =
                expectConflict {
                    service.service.refund(
                        booking.clubId + 1_000,
                        booking.id,
                        100,
                        "refund-wrong-club",
                        107L,
                    )
                }

            assertEquals("nothing to refund", conflict.message)
            assertEquals(0, actionRowsCount("refund-wrong-club"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))

            val retry =
                service.service.refund(
                    booking.clubId,
                    booking.id,
                    100,
                    "refund-wrong-club",
                    107L,
                )
            assertEquals(100, retry.refundAmountMinor)
            assertFalse(retry.idempotent)
        }

    @Test
    fun `refund global key cannot move to another booking`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val first = seedBooking(status = BookingStatus.BOOKED, clubId = 682L, idemKey = "booking-682")
            val second = seedBooking(status = BookingStatus.BOOKED, clubId = 683L, idemKey = "booking-683")
            seedPayment(first, amountMinor = 500)
            seedPayment(second, amountMinor = 500)

            val winner = service.service.refund(first.clubId, first.id, 100, "refund-global-key", 107L)
            assertEquals(100, winner.refundAmountMinor)

            try {
                service.service.refund(second.clubId, second.id, 100, "refund-global-key", 107L)
                fail("expected idempotency binding validation")
            } catch (validation: PaymentsService.ValidationException) {
                assertEquals("idempotency key already used for different operation", validation.message)
            }

            assertEquals(1, actionRowsCount("refund-global-key"))
            assertEquals(100, refundedTotal(first.id))
            assertEquals(0, refundedTotal(second.id))
            assertEquals(500, remainingAmount(second.id))
        }

    @Test
    fun `refund transaction failure rolls back claim and mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 69L, idemKey = "booking-69")
            seedPayment(booking, amountMinor = 500)
            val fixture = installFailingRefundTrigger()

            try {
                var failed = false
                try {
                    service.service.refund(booking.clubId, booking.id, 300, "refund-rollback", 108L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
                assertTrue(failed, "expected controlled database failure")
            } finally {
                dropRefundTrigger(fixture)
            }

            assertNull(repository.findActionByIdempotencyKey("refund-rollback"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))

            val retry = service.service.refund(booking.clubId, booking.id, 300, "refund-rollback", 108L)
            assertEquals(300, retry.refundAmountMinor)
            assertFalse(retry.idempotent)
            assertEquals(300, refundedTotal(booking.id))
        }

    @Test
    fun `refund cancellation rolls back claim and mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 70L, idemKey = "booking-70")
            seedPayment(booking, amountMinor = 500)
            val lockNamespace = 71_001
            val lockKey = 71_002
            val fixture = installBlockingRefundTrigger(lockNamespace, lockKey)
            val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            var lockHeld = false

            try {
                lockConnection.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_lock($lockNamespace, $lockKey)")
                }
                lockHeld = true
                val deferred =
                    async {
                        service.service.refund(booking.clubId, booking.id, 300, "refund-cancelled", 109L)
                    }

                awaitAdvisoryWait(lockNamespace, lockKey)
                deferred.cancel(CancellationException("controlled cancellation"))
                releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                lockHeld = false
                try {
                    deferred.await()
                    fail("expected cancellation")
                } catch (_: CancellationException) {
                    // Expected: ensureActive before Exposed commit forces rollback.
                }
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(lockConnection, lockNamespace, lockKey)
                }
                lockConnection.close()
                dropRefundTrigger(fixture)
            }

            assertNull(repository.findActionByIdempotencyKey("refund-cancelled"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))

            val retry = service.service.refund(booking.clubId, booking.id, 300, "refund-cancelled", 109L)
            assertEquals(300, retry.refundAmountMinor)
            assertFalse(retry.idempotent)
        }

    @Test
    fun `refund blank idempotency key still persists financial mutation`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 71L, idemKey = "booking-71")
            seedPayment(booking, amountMinor = 500)

            val first = service.service.refund(booking.clubId, booking.id, 200, "", 110L)
            val second = service.service.refund(booking.clubId, booking.id, 200, "", 110L)

            assertFalse(first.idempotent)
            assertFalse(second.idempotent)
            assertEquals(0, actionRowsCount(""))
            assertEquals(2, refundActionRowsCount(booking.id))
            assertEquals(2, refundRowsCount(booking.id))
            assertEquals(400, refundedTotal(booking.id))
            assertEquals(100, remainingAmount(booking.id))
        }

    @Test
    fun `refund reconciles refunded payment status as a full refund`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 72L, idemKey = "booking-72")
            seedPayment(booking, amountMinor = 500, status = "REFUNDED")

            val conflict =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 100, "refund-refunded-status", 111L)
                }

            assertEquals("nothing to refund", conflict.message)
            assertEquals(500, refundedTotal(booking.id))
            assertEquals(0, remainingAmount(booking.id))
        }

    @Test
    fun `refund uses post migration legacy action ledger without over refund`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 73L, idemKey = "booking-73")
            seedPayment(booking, amountMinor = 500)
            insertLegacyRefundAction(booking.id, "legacy-writer-ok", "300")

            assertEquals(1, refundRowsCount(booking.id))
            assertEquals(300, refundedTotal(booking.id))
            val error =
                expectUnprocessable {
                    service.service.refund(booking.clubId, booking.id, 250, "after-legacy-too-large", 112L)
                }
            assertEquals("exceeds remainder", error.message)
            assertEquals(300, refundedTotal(booking.id))

            val final = service.service.refund(booking.clubId, booking.id, 200, "after-legacy-valid", 112L)
            assertEquals(200, final.refundAmountMinor)
            assertFalse(final.idempotent)
            assertEquals(2, refundRowsCount(booking.id))
            assertEquals(500, refundedTotal(booking.id))
            assertEquals(0, remainingAmount(booking.id))
        }

    @Test
    fun `refund fails closed for malformed legacy result`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 74L, idemKey = "booking-74")
            seedPayment(booking, amountMinor = 500)
            insertLegacyRefundAction(booking.id, "legacy-writer-malformed", "not-numeric")

            val first =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 100, "blocked-malformed", 113L)
                }
            val replay =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 100, "blocked-malformed", 113L)
                }

            assertEquals("reconciliation_required", first.message)
            assertEquals(first.message, replay.message)
            assertEquals(1, actionRowsCount("blocked-malformed"))
            assertEquals(0, refundRowsCount(booking.id))
            assertEquals(500, remainingAmount(booking.id))
        }

    @Test
    fun `refund fails closed for ambiguous legacy action and payment status`() =
        runBlocking {
            val repository = PaymentsRepositoryImpl(database)
            val service = createService(paymentsRepo = repository)
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 75L, idemKey = "booking-75")
            val paymentId = seedPayment(booking, amountMinor = 500)
            insertLegacyRefundAction(booking.id, "legacy-writer-ambiguous", "100")
            repository.updateStatus(paymentId, "REFUNDED", null)
            val rowsBefore = refundRowsCount(booking.id)
            val totalBefore = refundedTotal(booking.id)

            val first =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 50, "blocked-ambiguous", 114L)
                }
            val replay =
                expectConflict {
                    service.service.refund(booking.clubId, booking.id, 50, "blocked-ambiguous", 114L)
                }

            assertEquals("reconciliation_required", first.message)
            assertEquals(first.message, replay.message)
            assertEquals(1, actionRowsCount("blocked-ambiguous"))
            assertEquals(rowsBefore, refundRowsCount(booking.id))
            assertEquals(totalBefore, refundedTotal(booking.id))
            assertEquals(2, rowsBefore)
            assertEquals(600, totalBefore)
            val stored = repository.findActionByIdempotencyKey("blocked-ambiguous")
            assertEquals(PaymentsRepository.Result.Status.CONFLICT, stored?.result?.status)
            assertEquals("reconciliation_required", stored?.result?.reason)
        }

    @Test
    fun `cancel conflict persists conflict status`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.SEATED, clubId = 3L, idemKey = "booking-3")

            try {
                service.service.cancel(booking.clubId, booking.id, null, "cancel-conflict", 9L)
                fail("expected conflict")
            } catch (conflict: PaymentsService.ConflictException) {
                assertTrue(conflict.message?.contains("status") == true)
            }

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-conflict")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.CONFLICT, saved!!.result.status)
            assertTrue(saved.result.reason!!.contains("SEATED"))
        }

    @Test
    fun `cancel idempotency survives new service instance`() =
        runBlocking {
            val first = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 4L, idemKey = "booking-4")

            first.service.cancel(booking.clubId, booking.id, null, "cancel-restart", 11L)

            val second = createService()
            val result = second.service.cancel(booking.clubId, booking.id, null, "cancel-restart", 11L)

            assertTrue(result.idempotent)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
        }

    private fun currentBookingStatus(id: UUID): BookingStatus =
        transaction(database) {
            val row =
                BookingsTable
                    .selectAll()
                    .where { BookingsTable.id eq id }
                    .firstOrNull() ?: fail("booking not found")
            BookingStatus.valueOf(row[BookingsTable.status])
        }

    private fun createService(
        serviceDatabase: Database = database,
        paymentsRepo: PaymentsRepository = PaymentsRepositoryImpl(serviceDatabase),
    ): ServiceContext {
        val bookingRepo: PaymentsBookingRepository = BookingRepository(serviceDatabase)
        val service =
            DefaultPaymentsService(
                finalizeService = NoopFinalizeService,
                paymentsRepository = paymentsRepo,
                bookingRepository = bookingRepo,
                metricsProvider = null,
                tracer = null,
            )
        return ServiceContext(service, paymentsRepo)
    }

    private fun connectToPostgres(applicationName: String? = null): Database =
        Database.connect(
            url =
                if (applicationName == null) {
                    postgres.jdbcUrl
                } else {
                    val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
                    "${postgres.jdbcUrl}$separator" + "ApplicationName=$applicationName"
                },
            driver = postgres.driverClassName,
            user = postgres.username,
            password = postgres.password,
        )

    private fun repeatableReadDataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = postgres.driverClassName
                maximumPoolSize = 2
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            },
        )

    private fun seedPayment(
        booking: BookingSeed,
        amountMinor: Long,
        currency: String = "RUB",
        status: String = "CAPTURED",
    ): UUID {
        val suffix = UUID.randomUUID().toString()
        val paymentId = UUID.randomUUID()
        require(status in setOf("INITIATED", "PENDING", "CAPTURED", "REFUNDED", "DECLINED")) {
            "unsupported test payment status: $status"
        }
        transaction(database) {
            PaymentsTable.insert {
                it[id] = paymentId
                it[bookingId] = booking.id
                it[provider] = "TEST"
                it[PaymentsTable.currency] = currency
                it[PaymentsTable.amountMinor] = amountMinor
                it[PaymentsTable.status] = status
                it[payload] = "refund-payment-$suffix"
                it[idempotencyKey] = "refund-payment-idem-$suffix"
            }
        }
        return paymentId
    }

    private fun insertLegacyRefundAction(
        bookingId: UUID,
        key: String,
        reason: String,
    ) {
        transaction(database) {
            PaymentActionsTable.insert {
                it[PaymentActionsTable.bookingId] = bookingId
                it[idempotencyKey] = key
                it[action] = PaymentsRepository.Action.REFUND.name
                it[status] = PaymentsRepository.Result.Status.OK.name
                it[PaymentActionsTable.reason] = reason
            }
        }
    }

    private fun actionRowsCount(idempotencyKey: String): Long =
        transaction(database) {
            PaymentActionsTable
                .selectAll()
                .where { PaymentActionsTable.idempotencyKey eq idempotencyKey }
                .count()
        }

    private fun refundActionRowsCount(bookingId: UUID): Long =
        transaction(database) {
            PaymentActionsTable
                .selectAll()
                .where {
                    (PaymentActionsTable.bookingId eq bookingId) and
                        (PaymentActionsTable.action eq PaymentsRepository.Action.REFUND.name)
                }.count()
        }

    private fun refundRowsCount(bookingId: UUID): Long =
        transaction(database) {
            PaymentRefundsTable
                .selectAll()
                .where { PaymentRefundsTable.bookingId eq bookingId }
                .count()
        }

    private fun refundedTotal(bookingId: UUID): Long =
        transaction(database) {
            PaymentRefundsTable
                .selectAll()
                .where { PaymentRefundsTable.bookingId eq bookingId }
                .sumOf { row -> row[PaymentRefundsTable.amountMinor] }
        }

    private fun capturedTotal(bookingId: UUID): Long =
        transaction(database) {
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.bookingId eq bookingId }
                .filter { row -> row[PaymentsTable.status] in setOf("CAPTURED", "REFUNDED") }
                .sumOf { row -> row[PaymentsTable.amountMinor] }
        }

    private fun remainingAmount(bookingId: UUID): Long = capturedTotal(bookingId) - refundedTotal(bookingId)

    private fun assertZeroRefundPersistence(
        booking: BookingSeed,
        paymentId: UUID,
        key: String,
    ) {
        val action =
            transaction(database) {
                PaymentActionsTable
                    .selectAll()
                    .where { PaymentActionsTable.idempotencyKey eq key }
                    .single()
            }
        assertEquals(1, actionRowsCount(key))
        assertEquals(PaymentsRepository.Action.REFUND.name, action[PaymentActionsTable.action])
        assertEquals(PaymentsRepository.Result.Status.OK.name, action[PaymentActionsTable.status])
        assertEquals(1, action[PaymentActionsTable.refundFingerprintVersion])
        assertEquals(
            PaymentsRepository.RefundRequestMode.EXPLICIT.name,
            action[PaymentActionsTable.refundRequestMode],
        )
        assertEquals(0, action[PaymentActionsTable.refundRequestAmountMinor])
        assertEquals(0, action[PaymentActionsTable.refundResultAmountMinor])
        assertNull(action[PaymentActionsTable.refundSourceKind])
        assertEquals(0, refundRowsCount(booking.id))
        assertEquals(0, refundedTotal(booking.id))
        assertEquals(500, remainingAmount(booking.id))
        assertEquals(
            "CAPTURED",
            transaction(database) {
                PaymentsTable
                    .selectAll()
                    .where { PaymentsTable.id eq paymentId }
                    .single()[PaymentsTable.status]
            },
        )
    }

    private suspend fun io.ktor.client.HttpClient.postRefund(
        path: String,
        key: String,
        payload: String,
        initData: String = "stub",
        principalTelegramId: Long? = null,
        requestId: String? = null,
    ) = post(path) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header("Idempotency-Key", key)
        header("X-Telegram-Init-Data", initData)
        if (principalTelegramId != null) {
            header("X-Telegram-Id", principalTelegramId.toString())
        }
        if (requestId != null) {
            header("X-Request-Id", requestId)
        }
        setBody(payload)
    }

    private suspend fun expectConflict(block: suspend () -> Unit): PaymentsService.ConflictException =
        try {
            block()
            fail("expected conflict")
        } catch (conflict: PaymentsService.ConflictException) {
            conflict
        }

    private suspend fun expectUnprocessable(block: suspend () -> Unit): PaymentsService.UnprocessableException =
        try {
            block()
            fail("expected unprocessable")
        } catch (unprocessable: PaymentsService.UnprocessableException) {
            unprocessable
        }

    private suspend fun expectValidation(block: suspend () -> Unit): PaymentsService.ValidationException =
        try {
            block()
            fail("expected validation")
        } catch (validation: PaymentsService.ValidationException) {
            validation
        }

    private fun installFailingRefundTrigger(): TriggerFixture {
        val fixture = TriggerFixture.random("fail_refund")
        transaction(database) {
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'controlled refund failure';
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                BEFORE INSERT ON payment_refunds
                FOR EACH ROW EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun installBlockingRefundTrigger(
        lockNamespace: Int,
        lockKey: Int,
    ): TriggerFixture {
        val fixture = TriggerFixture.random("block_refund")
        transaction(database) {
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    PERFORM pg_advisory_xact_lock($lockNamespace, $lockKey);
                    RETURN NEW;
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                AFTER INSERT ON payment_refunds
                FOR EACH ROW EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun installFailingCancelTrigger(): TriggerFixture {
        val fixture = TriggerFixture.random("fail_cancel")
        transaction(database) {
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'controlled cancel failure';
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                BEFORE UPDATE OF status ON payment_actions
                FOR EACH ROW
                WHEN (
                    OLD.status = 'PROCESSING'
                    AND NEW.action = 'CANCEL'
                    AND NEW.status <> 'PROCESSING'
                )
                EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun installBlockingCancelTrigger(
        lockNamespace: Int,
        lockKey: Int,
    ): TriggerFixture {
        val fixture = TriggerFixture.random("block_cancel")
        transaction(database) {
            exec(
                """
                CREATE FUNCTION ${fixture.functionName}() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    PERFORM pg_advisory_xact_lock($lockNamespace, $lockKey);
                    RETURN NEW;
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER ${fixture.triggerName}
                AFTER UPDATE OF status ON payment_actions
                FOR EACH ROW
                WHEN (
                    OLD.status = 'PROCESSING'
                    AND NEW.action = 'CANCEL'
                    AND NEW.status <> 'PROCESSING'
                )
                EXECUTE FUNCTION ${fixture.functionName}()
                """.trimIndent(),
            )
        }
        return fixture
    }

    private fun dropRefundTrigger(fixture: TriggerFixture) {
        transaction(database) {
            exec("DROP TRIGGER IF EXISTS ${fixture.triggerName} ON payment_refunds")
            exec("DROP FUNCTION IF EXISTS ${fixture.functionName}()")
        }
    }

    private fun dropActionTrigger(fixture: TriggerFixture) {
        transaction(database) {
            exec("DROP TRIGGER IF EXISTS ${fixture.triggerName} ON payment_actions")
            exec("DROP FUNCTION IF EXISTS ${fixture.functionName}()")
        }
    }

    private suspend fun awaitAdvisoryWait(
        lockNamespace: Int,
        lockKey: Int,
    ) {
        withTimeout(10_000L) {
            while (!hasAdvisoryWaiter(lockNamespace, lockKey)) {
                yield()
            }
        }
    }

    private suspend fun awaitTransactionWait() {
        withTimeout(10_000L) {
            while (!hasTransactionWaiter()) {
                yield()
            }
        }
    }

    private suspend fun awaitTransactionWait(applicationName: String) {
        withTimeout(10_000L) {
            while (!hasTransactionWaiter(applicationName)) {
                yield()
            }
        }
    }

    private fun hasTransactionWaiter(): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_locks
                            WHERE locktype = 'transactionid'
                              AND NOT granted
                        )
                        """.trimIndent(),
                    ).use { result ->
                        check(result.next())
                        result.getBoolean(1)
                    }
            }
        }

    private fun hasTransactionWaiter(applicationName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity activity
                        JOIN pg_locks waiting_lock ON waiting_lock.pid = activity.pid
                        WHERE activity.application_name = ?
                          AND waiting_lock.locktype = 'transactionid'
                          AND NOT waiting_lock.granted
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, applicationName)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getBoolean(1)
                    }
                }
        }

    private fun hasAdvisoryWaiter(
        lockNamespace: Int,
        lockKey: Int,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks
                        WHERE locktype = 'advisory'
                          AND classid::bigint = ?
                          AND objid::bigint = ?
                          AND NOT granted
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, lockNamespace.toLong())
                    statement.setLong(2, lockKey.toLong())
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getBoolean(1)
                    }
                }
        }

    private fun releaseAdvisoryLock(
        connection: java.sql.Connection,
        lockNamespace: Int,
        lockKey: Int,
    ) {
        connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)").use { statement ->
            statement.setInt(1, lockNamespace)
            statement.setInt(2, lockKey)
            statement.execute()
        }
    }

    private fun acquireAdvisoryLock(
        connection: java.sql.Connection,
        lockNamespace: Int,
        lockKey: Int,
    ) {
        connection.prepareStatement("SELECT pg_advisory_lock(?, ?)").use { statement ->
            statement.setInt(1, lockNamespace)
            statement.setInt(2, lockKey)
            statement.execute()
        }
    }

    private fun seedBooking(
        status: BookingStatus,
        clubId: Long,
        idemKey: String,
    ): BookingSeed {
        val id = UUID.randomUUID()
        val slotStart = OffsetDateTime.ofInstant(Instant.parse("2025-01-01T20:00:00Z"), ZoneOffset.UTC)
        val slotEnd = slotStart.plusHours(4)
        var persistedClubId = clubId
        transaction(database) {
            val clubPk =
                Clubs.insert {
                    it[name] = "Club $clubId"
                    it[description] = "Test club"
                    it[timezone] = "UTC"
                } get Clubs.id
            persistedClubId = clubPk.value.toLong()
            val persistedTableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = persistedClubId
                    it[zoneId] = null
                    it[tableNumber] = 10
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id
            val persistedEventId =
                EventsTable.insert {
                    it[EventsTable.clubId] = persistedClubId
                    it[startAt] = slotStart
                    it[endAt] = slotEnd
                    it[title] = "Party"
                    it[isSpecial] = false
                    it[posterUrl] = null
                } get EventsTable.id
            BookingsTable.insert {
                it[BookingsTable.id] = id
                it[BookingsTable.eventId] = persistedEventId
                it[BookingsTable.clubId] = persistedClubId
                it[BookingsTable.tableId] = persistedTableId
                it[BookingsTable.tableNumber] = 10
                it[BookingsTable.guestUserId] = null
                it[BookingsTable.guestName] = "Guest"
                it[BookingsTable.phoneE164] = null
                it[BookingsTable.promoterUserId] = null
                it[BookingsTable.guestsCount] = 2
                it[BookingsTable.minDeposit] = BigDecimal("100.00")
                it[BookingsTable.totalDeposit] = BigDecimal("200.00")
                it[BookingsTable.slotStart] = slotStart
                it[BookingsTable.slotEnd] = slotEnd
                it[BookingsTable.arrivalBy] = slotStart
                it[BookingsTable.status] = status.name
                it[BookingsTable.qrSecret] = "qr-$id"
                it[BookingsTable.idempotencyKey] = idemKey
                it[BookingsTable.createdAt] = slotStart
                it[BookingsTable.updatedAt] = slotStart
            }
        }
        return BookingSeed(id = id, clubId = persistedClubId)
    }

    private fun seedRbacActor(
        telegramId: Long,
        username: String,
        role: Role,
        clubId: Long,
    ) {
        transaction(database) {
            val userId =
                PaymentsRbacUsersTable.insert { row ->
                    row[PaymentsRbacUsersTable.telegramUserId] = telegramId
                    row[PaymentsRbacUsersTable.username] = username
                } get PaymentsRbacUsersTable.id
            PaymentsRbacUserRolesTable.insert { row ->
                row[PaymentsRbacUserRolesTable.userId] = userId
                row[PaymentsRbacUserRolesTable.roleCode] = role.name
                row[PaymentsRbacUserRolesTable.scopeType] = "CLUB"
                row[PaymentsRbacUserRolesTable.scopeClubId] = clubId
            }
        }
    }

    private data class ServiceContext(
        val service: DefaultPaymentsService,
        val paymentsRepo: PaymentsRepository,
    )

    private data class BookingSeed(
        val id: UUID,
        val clubId: Long,
    )

    private data class TriggerFixture(
        val functionName: String,
        val triggerName: String,
    ) {
        companion object {
            fun random(prefix: String): TriggerFixture {
                val suffix = UUID.randomUUID().toString().replace("-", "")
                return TriggerFixture(
                    functionName = "test_${prefix}_fn_$suffix",
                    triggerName = "test_${prefix}_trg_$suffix",
                )
            }
        }
    }

    private object NoopFinalizeService : PaymentsFinalizeService {
        override suspend fun finalize(
            clubId: Long,
            bookingId: UUID,
            paymentToken: String?,
            idemKey: String,
            actorUserId: Long,
        ): PaymentsFinalizeService.FinalizeResult = PaymentsFinalizeService.FinalizeResult("NOOP")
    }
}

private class RefundStartGate(
    private val parties: Int,
) {
    private val arrivals = AtomicInteger(0)
    private val open = CompletableDeferred<Unit>()

    suspend fun await() {
        if (arrivals.incrementAndGet() == parties) {
            open.complete(Unit)
        }
        open.await()
    }
}

private class GatedPaymentsRepository(
    private val delegate: PaymentsRepository,
    private val gate: RefundStartGate,
) : PaymentsRepository by delegate {
    override suspend fun executeRefundIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: PaymentsRepository.RefundFingerprint,
    ): PaymentsRepository.RefundExecution {
        gate.await()
        return delegate.executeRefundIdempotently(clubId, bookingId, idempotencyKey, fingerprint)
    }
}
