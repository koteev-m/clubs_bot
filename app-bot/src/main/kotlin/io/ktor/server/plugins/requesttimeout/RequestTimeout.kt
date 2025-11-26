package io.ktor.server.plugins.requesttimeout

import com.example.bot.http.ApiError
import com.example.bot.http.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

public class RequestTimeoutConfig {
    public var requestTimeoutMillis: Long = 0
}

public val RequestTimeout = createApplicationPlugin(
    name = "RequestTimeout",
    createConfiguration = ::RequestTimeoutConfig,
) {
    val timeout = pluginConfig.requestTimeoutMillis
    val logger = LoggerFactory.getLogger("RequestTimeout")
    if (timeout <= 0) return@createApplicationPlugin

    application.intercept(ApplicationCallPipeline.Plugins) {
        try {
            withTimeout(timeout) { proceed() }
        } catch (_: TimeoutCancellationException) {
            logger.warn("request timeout {}", call.callId ?: "-")
            call.respond(
                HttpStatusCode.RequestTimeout,
                ApiError(
                    code = ErrorCodes.request_timeout,
                    message = "Request timed out",
                    requestId = call.callId,
                    status = HttpStatusCode.RequestTimeout.value,
                    details = null,
                ),
            )
        }
    }
}
