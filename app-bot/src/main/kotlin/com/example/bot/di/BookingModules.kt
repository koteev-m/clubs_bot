package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.checkin.CheckinService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationService
import com.example.bot.club.WaitlistRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.club.GuestListServiceImpl
import com.example.bot.club.EventRepository
import com.example.bot.data.club.EventRepositoryImpl
import com.example.bot.data.club.InvitationDbRepository
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.ClubOpsChatConfigRepositoryImpl
import com.example.bot.data.club.InvitationServiceImpl
import com.example.bot.data.club.WaitlistRepositoryImpl
import com.example.bot.data.checkin.CheckinServiceImpl
import com.example.bot.admin.AdminBadgeRepository
import com.example.bot.admin.AdminGamificationSettingsRepository
import com.example.bot.admin.AdminNightOverrideRepository
import com.example.bot.admin.AdminPrizeRepository
import com.example.bot.admin.AdminRewardLadderRepository
import com.example.bot.data.gamification.AdminBadgeRepositoryImpl
import com.example.bot.data.gamification.AdminGamificationSettingsRepositoryImpl
import com.example.bot.data.gamification.AdminNightOverrideRepositoryImpl
import com.example.bot.data.gamification.AdminPrizeRepositoryImpl
import com.example.bot.data.gamification.AdminRewardLadderRepositoryImpl
import com.example.bot.data.gamification.BadgeRepository
import com.example.bot.data.gamification.BadgeRepositoryAdapter
import com.example.bot.data.gamification.CouponRepository
import com.example.bot.data.gamification.CouponRepositoryAdapter
import com.example.bot.data.gamification.GamificationSettingsRepository
import com.example.bot.data.gamification.GamificationSettingsRepositoryAdapter
import com.example.bot.data.gamification.PrizeRepository
import com.example.bot.data.gamification.PrizeRepositoryAdapter
import com.example.bot.data.gamification.RewardLadderRepository
import com.example.bot.data.gamification.RewardLadderRepositoryAdapter
import com.example.bot.data.gamification.UserBadgeRepository
import com.example.bot.data.gamification.UserBadgeRepositoryAdapter
import com.example.bot.data.gamification.VisitMetricsRepositoryAdapter
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.gamification.DefaultGamificationReadRepository
import com.example.bot.gamification.GamificationReadRepository
import com.example.bot.gamification.GuestGamificationService
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.promo.BookingTemplateRepositoryImpl
import com.example.bot.data.promo.PromoAttributionRepositoryImpl
import com.example.bot.data.promo.PromoLinkRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.tables.DefaultGuestQrResolver
import com.example.bot.tables.GuestQrResolver
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.VisitRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.gamification.BadgeRepository as DomainBadgeRepository
import com.example.bot.gamification.CouponRepository as DomainCouponRepository
import com.example.bot.gamification.GamificationEngine
import com.example.bot.gamification.GamificationSettingsRepository as DomainGamificationSettingsRepository
import com.example.bot.gamification.PrizeRepository as DomainPrizeRepository
import com.example.bot.gamification.RewardLadderRepository as DomainRewardLadderRepository
import com.example.bot.gamification.UserBadgeRepository as DomainUserBadgeRepository
import com.example.bot.gamification.VisitMetricsRepository
import com.example.bot.opschat.OpsNotificationPublisher
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttributionCoordinator
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoAttributionStore
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.telegram.NotificationsDispatchConfig
import com.example.bot.telegram.NotifyAdapterMetrics
import com.example.bot.telegram.NotifySenderSendPort
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import com.example.bot.opschat.ClubOpsChatConfigRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import kotlin.math.ceil

val bookingModule =
    module {
        single {
            val ds = DataSourceHolder.dataSource ?: error("DataSource is not initialized")
            Database.connect(ds)
        }

        single { GuestListCsvParser() }

        single { BookingRepository(get()) }
        single<PaymentsBookingRepository> { get<BookingRepository>() }
        single { BookingHoldRepository(get()) }
        single { OutboxRepository(get()) }
        single { NotificationsOutboxRepository(get()) }
        single<AuditLogRepository> { AuditLogRepositoryImpl(get()) }
        single { AuditLogger(get()) }
        single { TableSessionRepository(get()) }
        single { TableDepositRepository(get()) }
        single { ShiftReportRepository(get()) }
        single { ShiftReportTemplateRepository(get()) }
        single<GuestQrResolver> { DefaultGuestQrResolver(get(), get(), get()) }

        single<GuestListRepository> { GuestListRepositoryImpl(get()) }
        single<WaitlistRepository> { WaitlistRepositoryImpl(get()) }
        single { GuestListDbRepository(get()) }
        single { GuestListEntryDbRepository(get()) }
        single { InvitationDbRepository(get()) }
        single { CheckinDbRepository(get()) }
        single<ClubOpsChatConfigRepository> { ClubOpsChatConfigRepositoryImpl(get()) }
        single<GuestListService> { GuestListServiceImpl(get(), get()) }
        single<InvitationService> { InvitationServiceImpl(get(), get(), get()) }
        single<EventRepository> { EventRepositoryImpl(get()) }
        single { NightOverrideRepository(get()) }
        single { VisitRepository(get()) }

        single { GamificationSettingsRepository(get()) }
        single { BadgeRepository(get()) }
        single { UserBadgeRepository(get()) }
        single { RewardLadderRepository(get()) }
        single { CouponRepository(get()) }
        single { PrizeRepository(get()) }
        single<AdminGamificationSettingsRepository> { AdminGamificationSettingsRepositoryImpl(get()) }
        single<AdminNightOverrideRepository> { AdminNightOverrideRepositoryImpl(get()) }
        single<AdminBadgeRepository> { AdminBadgeRepositoryImpl(get()) }
        single<AdminPrizeRepository> { AdminPrizeRepositoryImpl(get()) }
        single<AdminRewardLadderRepository> { AdminRewardLadderRepositoryImpl(get()) }

        single<DomainGamificationSettingsRepository> { GamificationSettingsRepositoryAdapter(get()) }
        single<DomainBadgeRepository> { BadgeRepositoryAdapter(get()) }
        single<DomainUserBadgeRepository> { UserBadgeRepositoryAdapter(get()) }
        single<DomainRewardLadderRepository> { RewardLadderRepositoryAdapter(get()) }
        single<DomainCouponRepository> { CouponRepositoryAdapter(get()) }
        single<DomainPrizeRepository> { PrizeRepositoryAdapter(get()) }
        single<VisitMetricsRepository> { VisitMetricsRepositoryAdapter(get()) }
        single<GamificationReadRepository> {
            DefaultGamificationReadRepository(
                badgeRepository = get(),
                userBadgeRepository = get(),
                rewardLadderRepository = get(),
                prizeRepository = get(),
                couponRepository = get(),
            )
        }
        single {
            GuestGamificationService(
                readRepository = get(),
                visitMetricsRepository = get(),
            )
        }

        single {
            GamificationEngine(
                get<DomainGamificationSettingsRepository>(),
                get<DomainBadgeRepository>(),
                get<DomainUserBadgeRepository>(),
                get<DomainRewardLadderRepository>(),
                get<DomainPrizeRepository>(),
                get<DomainCouponRepository>(),
                get<VisitMetricsRepository>(),
                get(),
            )
        }

        single<CheckinService> {
            CheckinServiceImpl(
                checkinRepo = get(),
                invitationService = get(),
                guestListRepo = get(),
                guestListEntryRepo = get(),
                bookingRepo = get(),
                auditLogger = get(),
                userRepository = get(),
                promoterBookingAssignmentsRepository = get(),
                eventRepository = get<EventRepository>(),
                nightOverrideRepository = get(),
                visitRepository = get(),
                gamificationSettingsRepository = get(),
                gamificationEngine = get(),
            )
        }
        single { PromoterBookingAssignmentsRepository(get()) }

        single<PromoLinkRepository> { PromoLinkRepositoryImpl(get()) }
        single<PromoAttributionRepository> { PromoAttributionRepositoryImpl(get()) }
        single<BookingTemplateRepository> { BookingTemplateRepositoryImpl(get()) }

        single<UserRepository> { ExposedUserRepository(get()) }
        single<UserRoleRepository> { ExposedUserRoleRepository(get()) }

        single { MyBookingsMetrics(runCatching { get<MeterRegistry>() }.getOrNull()) }
        single { MyBookingsService(get(), get(), get(), get(), get(), get<OpsNotificationPublisher>()) }

        single<PromoAttributionStore> { InMemoryPromoAttributionStore() }

        single {
            PromoAttributionService(
                get<PromoLinkRepository>(),
                get<PromoAttributionRepository>(),
                get<PromoAttributionStore>(),
                get<UserRepository>(),
                get<UserRoleRepository>(),
            )
        }
        single<PromoAttributionCoordinator> { get<PromoAttributionService>() }

        single {
            BookingTemplateService(
                get<BookingTemplateRepository>(),
                get<BookingService>(),
                get<UserRepository>(),
                get<UserRoleRepository>(),
                get<NotificationsOutboxRepository>(),
            )
        }

        single { NotificationsDispatchConfig.fromEnvironment() }

        single<SendPort> {
            val dispatchConfig = get<NotificationsDispatchConfig>()
            if (dispatchConfig.isDispatchActive) {
                notifySendPortLogger.info("Using NotifySenderSendPort (notifications enabled)")
                val metrics =
                    runCatching { get<MeterRegistry>() }
                        .map { registry ->
                            val attempts = registry.counter("notify.adapter.attempts")
                            val ok = registry.counter("notify.adapter.ok")
                            val retryable = registry.counter("notify.adapter.retryable")
                            val permanent = registry.counter("notify.adapter.permanent")
                            NotifyAdapterMetrics(
                                onAttempt = { attempts.increment() },
                                onOk = { ok.increment() },
                                onRetryAfter = { delayMs ->
                                    val seconds = ceil(delayMs / 1000.0).toLong().coerceAtLeast(0)
                                    registry
                                        .counter(
                                            "notify.adapter.retry_after",
                                            "seconds",
                                            seconds.toString(),
                                        ).increment()
                                },
                                onRetryable = { retryable.increment() },
                                onPermanent = { permanent.increment() },
                            )
                        }.getOrNull()
                NotifySenderSendPort(get(), metrics)
            } else {
                notifySendPortLogger.info("Using DummySendPort (notifications disabled)")
                DummySendPort
            }
        }

        single {
            val config = get<NotificationsDispatchConfig>()
            val port = get<SendPort>()
            val implementation = port::class.simpleName ?: port::class.java.simpleName
            config.toHealth(implementation)
        }

        single {
            BookingService(
                get(),
                get(),
                get(),
                get(),
                get<OpsNotificationPublisher>(),
                get(),
                runCatching { get<MeterRegistry>() }.getOrNull(),
            )
        }
        single {
            val tracer = runCatching { get<Tracer>() }.getOrNull()
            OutboxWorker(get(), get(), tracer = tracer)
        }
    }

private object DummySendPort : SendPort {
    override suspend fun send(
        topic: String,
        payload: JsonObject,
    ): SendOutcome = SendOutcome.Ok
}

private val notifySendPortLogger = LoggerFactory.getLogger("NotifySenderWiring")
