package com.example.bot

import com.example.bot.booking.BookingService
import com.example.bot.checkin.CheckinService
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.coredata.CoreDataSeeder
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.gamification.GamificationSettingsRepository
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.VisitRepository
import com.example.bot.data.stories.GuestSegmentsRepository
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.analytics.AdminAnalyticsRefreshWorker
import com.example.bot.analytics.AdminAnalyticsSnapshotService
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.coredata.CoreDataSeed
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.HallPlansRepository
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.enforceRbacStartupGuard
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installCorsFromEnv
import com.example.bot.plugins.installDiagTime
import com.example.bot.plugins.installHttpSecurityFromEnv
import com.example.bot.plugins.installHotPathLimiterDefaults
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.resolveFlag
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRequestGuardsFromEnv
import com.example.bot.plugins.installWebAppCspFromEnv
import com.example.bot.plugins.installWebAppEtagForFingerprints
import com.example.bot.plugins.installWebAppImmutableCacheFromEnv
import com.example.bot.plugins.installWebUi
import com.example.bot.plugins.appConfig
import com.example.bot.routes.checkinRoutes
import com.example.bot.routes.clubsRoutes
import com.example.bot.booking.a3.Booking
import com.example.bot.host.BookingProvider
import com.example.bot.host.HostEntranceService
import com.example.bot.host.HostSearchService
import com.example.bot.notifications.LoggingNotificationService
import com.example.bot.notifications.NotificationService
import com.example.bot.notifications.TelegramOperationalNotificationService
import com.example.bot.promoter.admin.PromoterAdminService
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.quotas.PromoterQuotaService
import com.example.bot.promoter.rating.PromoterRatingService
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.support.SupportService
import com.example.bot.support.sanitizeClubName
import com.example.bot.routes.bookingA3Routes
import com.example.bot.routes.errorCodesRoutes
import com.example.bot.routes.guestListInviteRoutes
import com.example.bot.routes.guestListRoutes
import com.example.bot.routes.adminPrivacyRoutes
import com.example.bot.routes.hostEntranceRoutes
import com.example.bot.routes.hostChecklistRoutes
import com.example.bot.routes.hostCheckinRoutes
import com.example.bot.routes.hostWaitlistRoutes
import com.example.bot.routes.adminClubsRoutes
import com.example.bot.routes.adminAnalyticsRoutes
import com.example.bot.routes.adminFinanceShiftRoutes
import com.example.bot.routes.adminFinanceTemplateRoutes
import com.example.bot.routes.adminGamificationRoutes
import com.example.bot.routes.adminHallsRoutes
import com.example.bot.routes.adminHallPlanRoutes
import com.example.bot.routes.adminOpsChatsRoutes
import com.example.bot.routes.adminTableOpsRoutes
import com.example.bot.routes.adminTablesRoutes
import com.example.bot.routes.guestGamificationRoutes
import com.example.bot.routes.hallPlanRoutes
import com.example.bot.routes.layoutRoutes
import com.example.bot.routes.meBookingsRoutes
import com.example.bot.routes.adminMusicRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.musicLikesRoutes
import com.example.bot.routes.musicBattleRoutes
import com.example.bot.routes.ownerHealthRoutes
import com.example.bot.routes.supportRoutes
import com.example.bot.routes.pingRoute
import com.example.bot.routes.promoterInvitesRoutes
import com.example.bot.routes.promoterGuestListRoutes
import com.example.bot.routes.promoterQuotasAdminRoutes
import com.example.bot.routes.promoterAdminRoutes
import com.example.bot.routes.promoterRatingRoutes
import com.example.bot.routes.securedBookingRoutes
import com.example.bot.routes.trackOfNightRoutes
import com.example.bot.routes.waitlistRoutes
import com.example.bot.routes.invitationRoutes
import com.example.bot.routes.telegramWebhookRoutes
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import com.example.bot.tables.GuestQrResolver
import com.example.bot.telegram.InvitationTelegramHandler
import com.example.bot.telegram.SupportTelegramHandler
import com.example.bot.telegram.TelegramClient
import com.example.bot.telegram.TelegramCallbackRouter
import com.example.bot.telegram.TelegramGuestFallbackHandler
import com.example.bot.telegram.webhook.TelegramWebhookIngressMetrics
import com.example.bot.telegram.webhook.TelegramWebhookIngressWorker
import com.example.bot.deprecated.legacy.web.LegacyBookingConfig
import com.example.bot.deprecated.legacy.web.installLegacyBookingWebApp
import com.example.bot.di.appModules
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.runBlocking
import org.koin.core.logger.Level
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.util.concurrent.ConcurrentHashMap
import java.time.Clock
import java.time.ZoneId
import com.example.bot.host.ShiftChecklistService
import com.example.bot.data.privacy.PrivacyConfig
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

@Suppress("unused")
fun Application.module() {
    val isDev: Boolean =
        environment.config.propertyOrNull("ktor.deployment.development")
            ?.getString()?.toBooleanStrictOrNull()
            ?: System.getProperty("io.ktor.development")?.toBoolean() ?: false
    bootstrapPlatformPlugins()
    bootstrapPersistence()
    bootstrapSecurity()
    bootstrapKoin()

    val privacyConfig by inject<PrivacyConfig>()

    bootstrapRoutes(privacyConfig)

    val coreDataSeeder by inject<CoreDataSeeder>()
    val coreDataSeed by inject<CoreDataSeed>()
    runBlocking {
        coreDataSeeder.seedIfEmpty(coreDataSeed)
    }

    if (isDev) {
        installDiagTime()
    }


    // 6) Инжект сервисов
    val guestListRepository by inject<GuestListRepository>()
    val guestListService by inject<GuestListService>()
    val guestListEntryRepository by inject<GuestListEntryDbRepository>()
    val guestListDbRepository by inject<GuestListDbRepository>()
    val guestListCsvParser by inject<GuestListCsvParser>()
    val bookingService by inject<BookingService>()
    val bookingRepository by inject<com.example.bot.data.booking.core.BookingRepository>()
    val bookingState by inject<com.example.bot.booking.a3.BookingState>()
    val clubsRepository by inject<ClubsRepository>()
    val eventsRepository by inject<EventsRepository>()
    val layoutRepository by inject<LayoutRepository>()
    val layoutAssetsRepository by inject<LayoutAssetsRepository>()
    val adminTablesRepository by inject<AdminTablesRepository>()
    val adminClubsRepository by inject<AdminClubsRepository>()
    val adminHallsRepository by inject<AdminHallsRepository>()
    val adminGamificationSettingsRepository by inject<com.example.bot.admin.AdminGamificationSettingsRepository>()
    val adminNightOverrideRepository by inject<com.example.bot.admin.AdminNightOverrideRepository>()
    val adminBadgeRepository by inject<com.example.bot.admin.AdminBadgeRepository>()
    val adminPrizeRepository by inject<com.example.bot.admin.AdminPrizeRepository>()
    val adminRewardLadderRepository by inject<com.example.bot.admin.AdminRewardLadderRepository>()
    val auditLogger by inject<AuditLogger>()
    val auditLogRepository by inject<com.example.bot.audit.AuditLogRepository>()
    val privacyService by inject<com.example.bot.data.privacy.PrivacyService>()
    val shiftReportRepository by inject<com.example.bot.data.finance.ShiftReportRepository>()
    val shiftReportTemplateRepository by inject<com.example.bot.data.finance.ShiftReportTemplateRepository>()
    val tableSessionRepository by inject<TableSessionRepository>()
    val tableDepositRepository by inject<TableDepositRepository>()
    val visitRepository by inject<VisitRepository>()
    val nightOverrideRepository by inject<NightOverrideRepository>()
    val postEventStoryRepository by inject<PostEventStoryRepository>()
    val guestSegmentsRepository by inject<GuestSegmentsRepository>()
    val analyticsSnapshotService by inject<AdminAnalyticsSnapshotService>()
    val analyticsRefreshWorker by inject<AdminAnalyticsRefreshWorker>()
    val gamificationSettingsRepository by inject<GamificationSettingsRepository>()
    val guestQrResolver by inject<GuestQrResolver>()
    val hallPlansRepository by inject<HallPlansRepository>()
    val musicItemsRepository by inject<MusicItemRepository>()
    val musicAssetsRepository by inject<MusicAssetRepository>()
    val musicService by inject<MusicService>()
    val musicLikesRepository by inject<MusicLikesRepository>()
    val mixtapeService by inject<MixtapeService>()
    val musicPlaylistRepository by inject<MusicPlaylistRepository>()
    val musicBattleService by inject<com.example.bot.music.MusicBattleService>()
    val musicStemsRepository by inject<com.example.bot.music.MusicStemsRepository>()
    val trackOfNightRepository by inject<TrackOfNightRepository>()
    val waitlistRepository by inject<WaitlistRepository>()
    val promoterInviteService by inject<PromoterInviteService>()
    val promoterRatingService by inject<PromoterRatingService>()
    val promoterQuotaService by inject<PromoterQuotaService>()
    val promoterAdminService by inject<PromoterAdminService>()
    val promoterAssignments by inject<PromoterBookingAssignmentsRepository>()
    val invitationService by inject<InvitationService>()
    val ownerHealthService by inject<com.example.bot.owner.OwnerHealthService>()
    val checkinService by inject<CheckinService>()
    val guestGamificationService by inject<com.example.bot.gamification.GuestGamificationService>()
    val opsChatConfigRepository by inject<ClubOpsChatConfigRepository>()
    val userRepository by inject<UserRepository>()
    val suspiciousIpRepository by inject<SuspiciousIpRepository>()
    val webhookUpdateDedupRepository by inject<WebhookUpdateDedupRepository>()
    val telegramWebhookIngressRepository by inject<TelegramWebhookIngressRepository>()
    val paymentsHandlers by inject<com.example.bot.telegram.PaymentsHandlers>()
    val supportService by inject<SupportService>()
    val appClock = Clock.systemUTC()
    val notificationService: NotificationService = LoggingNotificationService()
    val telegramClient by inject<TelegramClient>()
    val opsNotificationService by inject<TelegramOperationalNotificationService>()
    val hostEntranceService =
        HostEntranceService(
            guestListRepository = guestListRepository,
            waitlistRepository = waitlistRepository,
            bookingProvider =
                object : BookingProvider {
                    override fun findBookingsForEvent(clubId: Long, eventId: Long): List<Booking> =
                        bookingState.findBookingsForEvent(clubId, eventId)
                },
            eventsRepository = eventsRepository,
        )
    val shiftChecklistService = ShiftChecklistService(clock = appClock)
    val hostSearchService = HostSearchService(bookingRepository, guestListRepository, guestListDbRepository)

    // 7) Метрики
    val registry = Metrics.globalRegistry
    val rotationConfig = com.example.bot.metrics.QrRotationConfig.fromEnv()
    UiCheckinMetrics.bind(registry, rotationConfig)
    UiWaitlistMetrics.bind(registry)

    val config = appConfig
    val invitationTelegramHandler =
        InvitationTelegramHandler(
            send = telegramClient::send,
            invitationService = invitationService,
            meterRegistry = registry,
            zoneId = ZoneId.systemDefault(),
        )
    val supportTelegramHandler =
        SupportTelegramHandler(
            send = telegramClient::send,
            supportService = supportService,
            userRepository = userRepository,
        )
    val telegramGuestFallbackHandler =
        TelegramGuestFallbackHandler(
            send = telegramClient::send,
            bookingState = bookingState,
            clubsRepository = clubsRepository,
            userRepository = userRepository,
            supportService = supportService,
            botUsername = config.bot.username,
            qrSecretProvider = { System.getenv("QR_SECRET") ?: "" },
        )
    val telegramCallbackRouter =
        TelegramCallbackRouter(
            supportHandler = supportTelegramHandler::handle,
            invitationHandler = invitationTelegramHandler::handle,
            guestFallbackHandler = telegramGuestFallbackHandler::handle,
            paymentsHandler = paymentsHandlers,
        )

    val telegramWebhookIngressMetrics = TelegramWebhookIngressMetrics(registry)
    val telegramWebhookIngressWorker =
        TelegramWebhookIngressWorker(
            repository = telegramWebhookIngressRepository,
            onUpdate = { update -> telegramCallbackRouter.route(update) },
            metrics = telegramWebhookIngressMetrics,
        )

    environment.monitor.subscribe(ApplicationStarted) {
        opsNotificationLogger.info("Starting TelegramOperationalNotificationService")
        opsNotificationService.start()
        telegramWebhookIngressWorker.start()
    }

    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking {
            runCatching { analyticsRefreshWorker.shutdown() }
                .onFailure { opsNotificationLogger.warn("AdminAnalyticsRefreshWorker stop failed", it) }
            runCatching { telegramWebhookIngressWorker.shutdown() }
                .onFailure { opsNotificationLogger.warn("TelegramWebhookIngressWorker stop failed", it) }
            runCatching { opsNotificationService.shutdown() }
                .onSuccess { opsNotificationLogger.info("TelegramOperationalNotificationService stopped") }
                .onFailure { opsNotificationLogger.warn("TelegramOperationalNotificationService stop failed", it) }
        }
    }

    // 8) Роуты (все роуты сами внутри вешают withMiniAppAuth на нужные ветки)
    errorCodesRoutes()
    pingRoute()
    guestListRoutes(repository = guestListRepository, parser = guestListCsvParser, auditLogRepository = auditLogRepository)
    adminPrivacyRoutes(privacyService = privacyService)
    bookingA3Routes(
        bookingState = bookingState,
        meterRegistry = registry,
        opsPublisher = opsNotificationService,
    )
    meBookingsRoutes(
        bookingState = bookingState,
        eventsRepository = eventsRepository,
        clubsRepository = clubsRepository,
        meterRegistry = registry,
    )
    guestGamificationRoutes(
        clubsRepository = clubsRepository,
        gamificationService = guestGamificationService,
    )
    checkinRoutes(
        repository = guestListRepository,
        promoterInviteService = promoterInviteService,
        rotationConfig = rotationConfig,
    )
    promoterInvitesRoutes(promoterInviteService = promoterInviteService, meterRegistry = registry)
    promoterGuestListRoutes(
        guestListRepository = guestListRepository,
        guestListService = guestListService,
        guestListEntryRepository = guestListEntryRepository,
        invitationService = invitationService,
        clock = appClock,
        guestListDbRepository = guestListDbRepository,
        clubsRepository = clubsRepository,
        eventsRepository = eventsRepository,
        adminHallsRepository = adminHallsRepository,
        adminTablesRepository = adminTablesRepository,
        bookingState = bookingState,
        promoterAssignments = promoterAssignments,
        opsPublisher = opsNotificationService,
    )
    promoterRatingRoutes(promoterRatingService = promoterRatingService)
    promoterQuotasAdminRoutes(promoterQuotaService = promoterQuotaService)
    promoterAdminRoutes(promoterAdminService = promoterAdminService)
    adminOpsChatsRoutes(repository = opsChatConfigRepository)
    adminClubsRoutes(adminClubsRepository = adminClubsRepository)
    adminHallsRoutes(adminHallsRepository = adminHallsRepository, adminClubsRepository = adminClubsRepository)
    adminHallPlanRoutes(adminHallsRepository = adminHallsRepository, hallPlansRepository = hallPlansRepository)
    adminTablesRoutes(adminTablesRepository = adminTablesRepository, adminHallsRepository = adminHallsRepository)
    adminTableOpsRoutes(
        adminTablesRepository = adminTablesRepository,
        tableSessionRepository = tableSessionRepository,
        tableDepositRepository = tableDepositRepository,
        shiftReportRepository = shiftReportRepository,
        visitRepository = visitRepository,
        nightOverrideRepository = nightOverrideRepository,
        gamificationSettingsRepository = gamificationSettingsRepository,
        auditLogger = auditLogger,
        guestQrResolver = guestQrResolver,
        clock = appClock,
    )
    adminFinanceShiftRoutes(
        shiftReportRepository = shiftReportRepository,
        templateRepository = shiftReportTemplateRepository,
        auditLogger = auditLogger,
        clock = appClock,
    )
    adminFinanceTemplateRoutes(templateRepository = shiftReportTemplateRepository)
    adminAnalyticsRoutes(
        snapshotService = analyticsSnapshotService,
        refreshWorker = analyticsRefreshWorker,
        storyRepository = postEventStoryRepository,
    )
    adminGamificationRoutes(
        settingsRepository = adminGamificationSettingsRepository,
        nightOverrideRepository = adminNightOverrideRepository,
        badgeRepository = adminBadgeRepository,
        prizeRepository = adminPrizeRepository,
        rewardLadderRepository = adminRewardLadderRepository,
    )
    adminMusicRoutes(itemsRepository = musicItemsRepository, assetsRepository = musicAssetsRepository, clock = appClock)
    clubsRoutes(clubsRepository = clubsRepository, eventsRepository = eventsRepository)
    layoutRoutes(layoutRepository = layoutRepository, layoutAssetsRepository = layoutAssetsRepository)
    hallPlanRoutes(hallPlansRepository = hallPlansRepository)
    ownerHealthRoutes(
        service = ownerHealthService,
        layoutRepository = layoutRepository,
        clock = appClock,
    )
    musicRoutes(
        service = musicService,
        itemsRepository = musicItemsRepository,
        likesRepository = musicLikesRepository,
        assetsRepository = musicAssetsRepository,
        mixtapeService = mixtapeService,
    )
    musicLikesRoutes(
        likesRepository = musicLikesRepository,
        mixtapeService = mixtapeService,
        itemsRepository = musicItemsRepository,
        clock = appClock,
    )
    musicBattleRoutes(
        battleService = musicBattleService,
        itemsRepository = musicItemsRepository,
        stemsRepository = musicStemsRepository,
        assetsRepository = musicAssetsRepository,
    )
    trackOfNightRoutes(
        trackOfNightRepository = trackOfNightRepository,
        playlistsRepository = musicPlaylistRepository,
        clock = appClock,
    )
    guestListInviteRoutes(repository = guestListRepository)
    invitationRoutes(invitationService = invitationService)
    val clubNameCache = ConcurrentHashMap<Long, String>()
    supportRoutes(
        supportService = supportService,
        userRepository = userRepository,
        sendTelegram = telegramClient::send,
        opsPublisher = opsNotificationService,
        clubNameProvider = clubNameProvider@{ clubId ->
            clubNameCache[clubId]?.let { return@clubNameProvider it }
            try {
                val clubName = sanitizeClubName(clubsRepository.getById(clubId)?.name)
                if (clubName != null) {
                    if (clubNameCache.size > 1000) {
                        clubNameCache.clear()
                    }
                    clubNameCache[clubId] = clubName
                }
                clubName
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        },
    )
    telegramWebhookRoutes(
        expectedSecret = config.webhook.secretToken,
        runMode = config.runMode,
        dedupRepository = webhookUpdateDedupRepository,
        ingressRepository = telegramWebhookIngressRepository,
        suspiciousIpRepository = suspiciousIpRepository,
        metrics = telegramWebhookIngressMetrics,
        clock = appClock,
    )
    waitlistRoutes(
        repository = waitlistRepository,
        notificationService = notificationService,
        clock = appClock,
    )
    hostWaitlistRoutes(
        repository = waitlistRepository,
    )
    hostEntranceRoutes(service = hostEntranceService)
    hostChecklistRoutes(
        checklistService = shiftChecklistService,
        eventsRepository = eventsRepository,
        clock = appClock,
    )
    hostCheckinRoutes(checkinService = checkinService, hostSearchService = hostSearchService)

    // 9) Прочее
    routing {
        securedBookingRoutes(bookingService)
        healthRoute()
        readinessRoute()
    }

    environment.monitor.subscribe(ApplicationStopped) {
        bookingState.close()
    }
}

private val opsNotificationLogger = LoggerFactory.getLogger("OpsNotificationService")


private fun Application.bootstrapPlatformPlugins() {
    configureLoggingAndRequestId()
    installAppConfig()
    install(AutoHeadResponse)
    installWebAppEtagForFingerprints()
    install(ConditionalHeaders)
    installMetrics()
    install(ActorMdcPlugin)
    installCorsFromEnv()
    installHttpSecurityFromEnv()
    install(ContentNegotiation) { json() }
    installRequestGuardsFromEnv()
    if (resolveFlag("RATE_LIMIT_ENABLED", true)) {
        installRateLimitPluginDefaults()
    }
    if (resolveFlag("HOT_PATH_ENABLED", true)) {
        installHotPathLimiterDefaults()
    }
    installJsonErrorPages()
    installWebAppCspFromEnv()
    installWebAppImmutableCacheFromEnv()
    installWebUi()
}

private fun Application.bootstrapPersistence() {
    installMigrationsAndDatabase()
}

private fun Application.bootstrapSecurity() {
    configureSecurity()
    enforceRbacStartupGuard()
}

private fun Application.bootstrapKoin() {
    val isDev =
        (environment.config.propertyOrNull("ktor.deployment.development")?.getString()?.toBooleanStrictOrNull())
            ?: System.getProperty("io.ktor.development")?.toBoolean() ?: false
    val modules = appModules()
    install(Koin) {
        slf4jLogger(if (isDev) Level.DEBUG else Level.INFO)
        modules(modules)
    }
    environment.log.info("Koin: loaded ${modules.size} module(s)")
}

private fun Application.bootstrapRoutes(privacyConfig: PrivacyConfig) {
    bootstrapLegacyBookingWebApp(privacyConfig)
}

internal fun Application.bootstrapLegacyBookingWebApp(privacyConfig: PrivacyConfig) {
    if (!isLegacyBookingEnabled()) {
        return
    }
    val legacyConfig = LegacyBookingConfig.fromEnvForEnabled(::resolveLegacyBookingEnv)
    installLegacyBookingWebApp(
        privacyConfig = privacyConfig,
        legacyHqNotifier = legacyConfig.buildHqNotifier(),
        legacyBotTokenProvider = legacyConfig::botTokenProvider,
    )
}

internal fun Application.isLegacyBookingEnabled(): Boolean =
    resolveFlag("LEGACY_BOOKING_WEBAPP_ENABLED", default = false)

internal fun Application.resolveLegacyBookingEnv(name: String): String? {
    val fromConfig = environment.config.propertyOrNull("app.env.$name")
    return resolveLegacyBookingEnvValue(
        configValue = fromConfig?.getString(),
        hasConfigValue = fromConfig != null,
        processValue = System.getenv(name),
    )
}

internal fun resolveLegacyBookingEnvValue(
    configValue: String?,
    hasConfigValue: Boolean,
    processValue: String?,
): String? {
    if (hasConfigValue) {
        return configValue.orEmpty().trim().takeIf { it.isNotBlank() }
    }
    return processValue
}
