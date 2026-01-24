package com.example.bot

import com.example.bot.booking.BookingService
import com.example.bot.checkin.CheckinService
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.coredata.CoreDataSeeder
import com.example.bot.data.security.UserRepository
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.coredata.CoreDataSeed
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MixtapeService
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.HallPlansRepository
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installCorsFromEnv
import com.example.bot.plugins.installDiagTime
import com.example.bot.plugins.installHttpSecurityFromEnv
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
import com.example.bot.routes.hostEntranceRoutes
import com.example.bot.routes.hostChecklistRoutes
import com.example.bot.routes.hostCheckinRoutes
import com.example.bot.routes.hostWaitlistRoutes
import com.example.bot.routes.adminClubsRoutes
import com.example.bot.routes.adminHallsRoutes
import com.example.bot.routes.adminHallPlanRoutes
import com.example.bot.routes.adminTablesRoutes
import com.example.bot.routes.hallPlanRoutes
import com.example.bot.routes.layoutRoutes
import com.example.bot.routes.meBookingsRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.musicLikesRoutes
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
import com.example.bot.telegram.InvitationTelegramHandler
import com.example.bot.telegram.SupportTelegramHandler
import com.example.bot.telegram.TelegramClient
import com.example.bot.telegram.TelegramCallbackRouter
import com.example.bot.web.installBookingWebApp
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.runBlocking
import org.koin.core.logger.Level
import org.koin.core.module.Module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.time.Clock
import java.time.ZoneId
import com.example.bot.host.ShiftChecklistService
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

@Suppress("unused")
fun Application.module() {
    // 1) Базовые плагины
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
    installJsonErrorPages()
    installWebAppCspFromEnv()
    installWebAppImmutableCacheFromEnv()

    // 2) БД и миграции
    installMigrationsAndDatabase()

    // 3) UI и мини‑приложение
    installWebUi()
    installBookingWebApp()

    // 4) DI через Koin
    val isDev: Boolean =
        environment.config.propertyOrNull("ktor.deployment.development")
            ?.getString()?.toBooleanStrictOrNull()
            ?: System.getProperty("io.ktor.development")?.toBoolean() ?: false

    val koinModules = loadKoinModulesReflectively()
    if (koinModules.isEmpty()) {
        error(
            "Koin modules aggregator not found. " +
                "Добавьте функцию/свойство, возвращающее List<Module>, в пакет com.example.bot.di " +
                "и подключите её явно: install(Koin) { modules(<ВАША_ФУНКЦИЯ_ИЛИ_val>()) }.",
        )
    }

    install(Koin) {
        slf4jLogger(if (isDev) Level.DEBUG else Level.INFO)
        modules(koinModules)
    }
    environment.log.info("Koin: loaded ${koinModules.size} module(s)")

    val coreDataSeeder by inject<CoreDataSeeder>()
    val coreDataSeed by inject<CoreDataSeed>()
    runBlocking {
        coreDataSeeder.seedIfEmpty(coreDataSeed)
    }

    if (isDev) {
        installDiagTime()
    }

    // 5) Security
    configureSecurity()

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
    val hallPlansRepository by inject<HallPlansRepository>()
    val musicService by inject<MusicService>()
    val musicLikesRepository by inject<MusicLikesRepository>()
    val mixtapeService by inject<MixtapeService>()
    val musicPlaylistRepository by inject<MusicPlaylistRepository>()
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
    val userRepository by inject<UserRepository>()
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
    val telegramCallbackRouter =
        TelegramCallbackRouter(
            supportHandler = supportTelegramHandler::handle,
            invitationHandler = invitationTelegramHandler::handle,
        )

    environment.monitor.subscribe(ApplicationStarted) {
        opsNotificationLogger.info("Starting TelegramOperationalNotificationService")
        opsNotificationService.start()
    }

    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking {
            runCatching { opsNotificationService.shutdown() }
                .onSuccess { opsNotificationLogger.info("TelegramOperationalNotificationService stopped") }
                .onFailure { opsNotificationLogger.warn("TelegramOperationalNotificationService stop failed", it) }
        }
    }

    // 8) Роуты (все роуты сами внутри вешают withMiniAppAuth на нужные ветки)
    errorCodesRoutes()
    pingRoute()
    guestListRoutes(repository = guestListRepository, parser = guestListCsvParser)
    bookingA3Routes(bookingState = bookingState, meterRegistry = registry)
    meBookingsRoutes(
        bookingState = bookingState,
        eventsRepository = eventsRepository,
        clubsRepository = clubsRepository,
        meterRegistry = registry,
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
    )
    promoterRatingRoutes(promoterRatingService = promoterRatingService)
    promoterQuotasAdminRoutes(promoterQuotaService = promoterQuotaService)
    promoterAdminRoutes(promoterAdminService = promoterAdminService)
    adminClubsRoutes(adminClubsRepository = adminClubsRepository)
    adminHallsRoutes(adminHallsRepository = adminHallsRepository, adminClubsRepository = adminClubsRepository)
    adminHallPlanRoutes(adminHallsRepository = adminHallsRepository, hallPlansRepository = hallPlansRepository)
    adminTablesRoutes(adminTablesRepository = adminTablesRepository, adminHallsRepository = adminHallsRepository)
    clubsRoutes(clubsRepository = clubsRepository, eventsRepository = eventsRepository)
    layoutRoutes(layoutRepository = layoutRepository, layoutAssetsRepository = layoutAssetsRepository)
    hallPlanRoutes(hallPlansRepository = hallPlansRepository)
    ownerHealthRoutes(
        service = ownerHealthService,
        layoutRepository = layoutRepository,
        clock = appClock,
    )
    musicRoutes(service = musicService)
    musicLikesRoutes(
        likesRepository = musicLikesRepository,
        mixtapeService = mixtapeService,
        clock = appClock,
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
        onUpdate = { update -> telegramCallbackRouter.route(update) },
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
        get("/health") { call.respondText("OK") }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        bookingState.close()
    }
}

private val opsNotificationLogger = LoggerFactory.getLogger("OpsNotificationService")

// Сканер Koin‑модулей в пакете com.example.bot.di
@Suppress("NestedBlockDepth")
private fun loadKoinModulesReflectively(): List<Module> {
    val result = mutableListOf<Module>()
    val cl = Thread.currentThread().contextClassLoader
    val pkgPath = "com/example/bot/di"

    val classNames = mutableSetOf<String>()
    val resources = cl.getResources(pkgPath)
    val seenJars = mutableSetOf<String>()
    while (resources.hasMoreElements()) {
        val url: URL = resources.nextElement()
        when (url.protocol) {
            "jar" -> {
                val conn = (url.openConnection() as? JarURLConnection) ?: continue
                val jar: JarFile = conn.jarFile
                if (seenJars.add(jar.name)) {
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.name.startsWith("$pkgPath/") && e.name.endsWith("Kt.class")) {
                            val cn = e.name.removeSuffix(".class").replace('/', '.')
                            classNames += cn
                        }
                    }
                }
            }
            "file" -> {
                val dir = File(url.toURI())
                if (dir.exists()) {
                    dir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith("Kt.class") }
                        .forEach { file ->
                            val abs = file.absolutePath.replace(File.separatorChar, '/')
                            val idx = abs.indexOf(pkgPath)
                            if (idx >= 0) {
                                val cn = abs.substring(idx).removeSuffix(".class").replace('/', '.')
                                classNames += cn
                            }
                        }
                }
            }
        }
    }

    fun collectFrom(any: Any?) {
        when (any) {
            is Module -> result += any
            is Collection<*> -> any.filterIsInstance<Module>().forEach { result += it }
        }
    }

    for (cn in classNames) {
        val kls = runCatching { Class.forName(cn) }.getOrNull() ?: continue

        // public static методы без параметров
        kls.methods
            .filter { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
            .forEach { m ->
                val returnsModuleLike =
                    Module::class.java.isAssignableFrom(m.returnType) ||
                        List::class.java.isAssignableFrom(m.returnType)
                val looksLikeModuleName =
                    m.name.contains("module", ignoreCase = true) ||
                        m.name.contains("modules", ignoreCase = true) ||
                        m.name.startsWith("get")
                if (returnsModuleLike || looksLikeModuleName) {
                    collectFrom(runCatching { m.invoke(null) }.getOrNull())
                }
            }

        // статические поля (если кто-то сделал @JvmField val …)
        kls.fields
            .filter { Modifier.isStatic(it.modifiers) }
            .forEach { f ->
                val typeOk =
                    Module::class.java.isAssignableFrom(f.type) ||
                        List::class.java.isAssignableFrom(f.type)
                val nameOk =
                    f.name.contains("module", ignoreCase = true) ||
                        f.name.contains("modules", ignoreCase = true)
                if (typeOk || nameOk) {
                    collectFrom(runCatching { f.get(null) }.getOrNull())
                }
            }
    }

    return result.distinct()
}
