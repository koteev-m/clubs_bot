package com.example.bot

import com.example.bot.booking.BookingService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.music.MusicService
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
import com.example.bot.routes.checkinRoutes
import com.example.bot.routes.clubsRoutes
import com.example.bot.routes.errorCodesRoutes
import com.example.bot.routes.guestListInviteRoutes
import com.example.bot.routes.guestListRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.layoutRoutes
import com.example.bot.routes.pingRoute
import com.example.bot.routes.meBookingsRoutes
import com.example.bot.routes.securedBookingRoutes
import com.example.bot.routes.waitlistRoutes
import com.example.bot.routes.bookingA3Routes
import com.example.bot.web.installBookingWebApp
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Metrics
import org.koin.core.logger.Level
import org.koin.core.module.Module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile

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

    if (isDev) {
        installDiagTime()
    }

    // 5) Security
    configureSecurity()

    // 6) Инжект сервисов
    val guestListRepository by inject<GuestListRepository>()
    val guestListCsvParser by inject<GuestListCsvParser>()
    val bookingService by inject<BookingService>()
    val bookingState by inject<com.example.bot.booking.a3.BookingState>()
    val clubsRepository by inject<ClubsRepository>()
    val eventsRepository by inject<EventsRepository>()
    val layoutRepository by inject<LayoutRepository>()
    val musicService by inject<MusicService>()
    val waitlistRepository by inject<WaitlistRepository>()

    // 7) Метрики
    val registry = Metrics.globalRegistry
    UiCheckinMetrics.bind(registry)
    UiWaitlistMetrics.bind(registry)

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
    checkinRoutes(repository = guestListRepository)
    clubsRoutes(clubsRepository = clubsRepository, eventsRepository = eventsRepository)
    layoutRoutes(layoutRepository = layoutRepository)
    musicRoutes(service = musicService)
    guestListInviteRoutes(repository = guestListRepository)
    waitlistRoutes(repository = waitlistRepository)

    // 9) Прочее
    routing {
        securedBookingRoutes(bookingService)
        get("/health") { call.respondText("OK") }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        bookingState.close()
    }
}

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
