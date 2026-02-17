package com.example.bot.routes

import com.example.bot.config.BotRunMode
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.security.webhook.WebhookSecurity
import com.example.bot.security.webhook.WebhookSecurityConfig
import com.example.bot.security.webhook.webhookRawBody
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.utility.BotUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

fun Application.telegramWebhookRoutes(
    expectedSecret: String?,
    runMode: BotRunMode,
    dedupRepository: WebhookUpdateDedupRepository,
    suspiciousIpRepository: SuspiciousIpRepository,
    security: WebhookSecurityConfig.() -> Unit = {},
    onUpdate: suspend (Update) -> Unit,
) {
    val logger = LoggerFactory.getLogger("TelegramWebhookRoutes")
    if (runMode == BotRunMode.POLLING) {
        logger.info("webhook: route registration skipped in polling mode")
        return
    }
    val webhookSecret =
        expectedSecret?.takeUnless { it.isBlank() }
            ?: throw IllegalStateException("WEBHOOK_SECRET_TOKEN must be configured in WEBHOOK mode")

    routing {
        route("/telegram/webhook") {
            install(WebhookSecurity) {
                requireSecret = true
                secretToken = webhookSecret
                this.dedupRepository = dedupRepository
                this.suspiciousIpRepository = suspiciousIpRepository
                security(this)
            }

            post {
                val body = call.webhookRawBody().decodeToString()
                val update = runCatching { BotUtils.parseUpdate(body) }.getOrNull()
                if (update == null) {
                    logger.warn("webhook: invalid update payload")
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

                try {
                    onUpdate(update)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    logger.warn("webhook: handler failed: {}", t.javaClass.simpleName)
                }

                call.respond(HttpStatusCode.OK, "OK")
            }
        }
    }
}
