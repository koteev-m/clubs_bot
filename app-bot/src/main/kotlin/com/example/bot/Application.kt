package com.example.bot

import com.example.bot.booking.BookingService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.music.MusicService

import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase

import com.example.bot.routes.checkinRoutes
import com.example.bot.routes.guestListInviteRoutes
import com.example.bot.routes.guestListRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.pingRoute
import com.example.bot.routes.securedBookingRoutes
import com.example.bot.routes.waitlistRoutes

import com.example.bot.webapp.InitDataAuthConfig

import io.ktor.server.application.Application
import io.ktor.server.application.install
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
    // 1) Базовые плагины, не зависящие от DI
    configureLoggingAndRequestId()
    installAppConfig()
    installMetrics()              // даёт /metrics и регистрирует Micrometer registry
    install(ActorMdcPlugin)

    // 2) База данных и миграции (инициализирует DataSourceHolder)
    installMigrationsAndDatabase()

    // 3) DI через Koin — обязательно ДО любых `by inject`
    val isDev: Boolean =
        environment.config.propertyOrNull("ktor.deployment.development")
            ?.getString()?.toBooleanStrictOrNull()
            ?: System.getProperty("io.ktor.development")?.toBoolean() ?: false

    val koinModules = loadKoinModulesReflectively()
    if (koinModules.isEmpty()) {
        error(
            "Koin modules aggregator not found. " +
                "Добавьте функцию/свойство, возвращающее List<Module>, в пакет com.example.bot.di " +
                "и подключите её явно: install(Koin) { modules(<ВАША_ФУНКЦИЯ_ИЛИ_val>()) }."
        )
    }

    install(Koin) {
        slf4jLogger(if (isDev) Level.DEBUG else Level.INFO)
        modules(koinModules)
    }
    environment.log.info("Koin: loaded ${koinModules.size} module(s)")

    // 4) Глобальная безопасность
    configureSecurity()

    // 5) Инжектим зависимости из Koin
    val guestListRepository by inject<GuestListRepository>()
    val guestListCsvParser by inject<GuestListCsvParser>()
    val bookingService by inject<BookingService>()
    val musicService by inject<MusicService>()
    val waitlistRepository by inject<WaitlistRepository>()

    // 6) Доп. метрики
    val registry = Metrics.globalRegistry
    UiCheckinMetrics.bind(registry)
    UiWaitlistMetrics.bind(registry)

    // 7) Общая конфигурация Mini App auth
    val initDataAuth: InitDataAuthConfig.() -> Unit = {
        botTokenProvider = {
            System.getenv("TELEGRAM_BOT_TOKEN")
                ?: error("TELEGRAM_BOT_TOKEN missing")
        }
    }

    // 8) Роуты уровня приложения
    pingRoute() // /ping для быстрых проверок
    guestListRoutes(
        repository = guestListRepository,
        parser = guestListCsvParser,
        initDataAuth = initDataAuth,
    )
    checkinRoutes(
        repository = guestListRepository,
        initDataAuth = initDataAuth,
    )
    musicRoutes(service = musicService)
    guestListInviteRoutes(
        repository = guestListRepository,
        initDataAuth = initDataAuth,
    )
    waitlistRoutes(
        repository = waitlistRepository,
        initDataAuth = initDataAuth,
    )

    // 9) Верхнеуровневые роуты
    routing {
        securedBookingRoutes(bookingService)
        get("/health") { call.respondText("OK") }
    }
}

/**
 * Универсальный загрузчик Koin-модулей:
 *  - сканирует пакет `com.example.bot.di` на classpath (в т.ч. внутри JAR);
 *  - берёт все top‑level Kotlin‑классы `*Kt` и вызывает любые public static методы/геттеры без параметров,
 *    которые возвращают `Module` или `List<Module>`;
 *  - поддерживает как функции (`appModules()`, `bookingModules()`), так и `val` (через геттеры `getAppModules()` и т.п.)
 *    и даже статические поля c `@JvmField`.
 */
private fun loadKoinModulesReflectively(): List<Module> {
    val result = mutableListOf<Module>()
    val cl = Thread.currentThread().contextClassLoader
    val pkgPath = "com/example/bot/di"

    // Собираем имена классов внутри пакета com.example.bot.di
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
            else -> {
                // игнорируем экзотические протоколы
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

        // 1) public static методы без параметров
        kls.methods
            .filter { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
            .forEach { m ->
                // Немного сузим по имени/возврату, чтобы не трогать посторонние методы
                val returnsModuleLike =
                    Module::class.java.isAssignableFrom(m.returnType) ||
                        List::class.java.isAssignableFrom(m.returnType)
                val looksLikeModuleName = m.name.contains("module", ignoreCase = true)
                    || m.name.contains("modules", ignoreCase = true)
                    || m.name.startsWith("get") // геттер для top-level val
                if (returnsModuleLike || looksLikeModuleName) {
                    collectFrom(runCatching { m.invoke(null) }.getOrNull())
                }
            }

        // 2) статические поля (если кто-то сделал @JvmField val …)
        kls.fields
            .filter { Modifier.isStatic(it.modifiers) }
            .forEach { f ->
                val typeOk =
                    Module::class.java.isAssignableFrom(f.type) ||
                        List::class.java.isAssignableFrom(f.type)
                val nameOk = f.name.contains("module", ignoreCase = true)
                    || f.name.contains("modules", ignoreCase = true)
                if (typeOk || nameOk) {
                    collectFrom(runCatching { f.get(null) }.getOrNull())
                }
            }
    }

    return result.distinct()
}
