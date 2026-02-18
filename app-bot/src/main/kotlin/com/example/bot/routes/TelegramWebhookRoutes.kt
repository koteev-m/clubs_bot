package com.example.bot.routes

import com.example.bot.config.BotRunMode
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.TelegramWebhookEnqueueResult
import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.security.webhook.WebhookSecurity
import com.example.bot.security.webhook.WebhookSecurityConfig
import com.example.bot.security.webhook.webhookRawBody
import com.pengrad.telegrambot.utility.BotUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

fun Application.telegramWebhookRoutes(
    expectedSecret: String?,
    runMode: BotRunMode,
    dedupRepository: WebhookUpdateDedupRepository,
    ingressRepository: TelegramWebhookIngressRepository,
    suspiciousIpRepository: SuspiciousIpRepository,
    security: WebhookSecurityConfig.() -> Unit = {},
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
                enableDedup = false
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
                    when (
                        ingressRepository.enqueue(
                            updateId = update.updateId().toLong(),
                            payloadJson = body,
                        )
                    ) {
                        is TelegramWebhookEnqueueResult.Duplicate -> {
                            logger.debug("webhook: duplicate update_id={} already queued", update.updateId())
                        }

                        is TelegramWebhookEnqueueResult.Enqueued -> Unit
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    logger.warn(
                        "webhook: enqueue failed update_id={} error={}",
                        update.updateId(),
                        e.javaClass.simpleName,
                    )
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@post
                }

                call.respond(HttpStatusCode.OK, "OK")
            }
        }
    }
}
