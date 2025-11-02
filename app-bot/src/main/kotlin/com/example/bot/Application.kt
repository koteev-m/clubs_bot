package com.example.bot

import com.example.bot.booking.BookingService
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.music.MusicService
import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.plugins.configureSecurity
import com.example.bot.routes.checkinRoutes
import com.example.bot.routes.guestListInviteRoutes
import com.example.bot.routes.guestListRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.securedBookingRoutes
import com.example.bot.routes.waitlistRoutes
import com.example.bot.webapp.InitDataAuthConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.header
import io.micrometer.core.instrument.Metrics
import org.koin.ktor.ext.inject
import java.security.SecureRandom

@Suppress("unused")
fun Application.module() {
    install(CallId) {
        header("X-Request-Id")
        header("X-Request-ID")
        generate { generateRequestId() }
        verify { id -> id.isNotBlank() && id.matches(Regex("[A-Za-z0-9._-]+")) }
        reply { call, id -> call.response.header("X-Request-Id", id) }
    }

    install(CallLogging) {
        mdc("request_id") { it.callId }
    }

    // Глобальная безопасность
    configureSecurity()
    install(ActorMdcPlugin)

    // --- DI через Koin ---
    val guestListRepository by inject<GuestListRepository>()
    val guestListCsvParser by inject<GuestListCsvParser>()
    val bookingService by inject<BookingService>()
    val musicService by inject<MusicService>()
    val waitlistRepository by inject<WaitlistRepository>()

    // Подключаем метрики (глобальный реестр Micrometer)
    val registry = Metrics.globalRegistry
    UiCheckinMetrics.bind(registry)
    UiWaitlistMetrics.bind(registry)

    // Общая конфигурация Mini App auth
    val initDataAuth: InitDataAuthConfig.() -> Unit = {
        botTokenProvider = {
            System.getenv("TELEGRAM_BOT_TOKEN")
                ?: error("TELEGRAM_BOT_TOKEN missing")
        }
    }

    // --- Application-level routes ---
    guestListRoutes(
        repository = guestListRepository,
        parser = guestListCsvParser,
        initDataAuth = initDataAuth
    )
    checkinRoutes(
        repository = guestListRepository,
        initDataAuth = initDataAuth
    )
    musicRoutes(service = musicService)
    guestListInviteRoutes(
        repository = guestListRepository,
        initDataAuth = initDataAuth
    )
    waitlistRoutes(
        repository = waitlistRepository,
        initDataAuth = initDataAuth
    )

    // --- Route-level routes ---
    routing {
        securedBookingRoutes(bookingService)
        get("/health") { call.respondText("OK") }
    }
}

private val secureRandom: SecureRandom by lazy { SecureRandom() }

private fun generateRequestId(): String {
    val bytes = ByteArray(10)
    secureRandom.nextBytes(bytes)
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() and 0xff).toString(16)).padStart(2, '0'))
        }
    }
}
