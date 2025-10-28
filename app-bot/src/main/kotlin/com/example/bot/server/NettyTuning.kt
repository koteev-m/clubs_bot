package com.example.bot.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.io.DEFAULT_BUFFER_SIZE

private const val DEFAULT_MAX_REQUEST_SIZE_BYTES = 2L * 1024 * 1024

/**
 * Dispatcher для CPU/рендер-операций (ограниченный пул).
 * Размер пула настраивается ENV `CPU_POOL_SIZE` (по умолчанию = кол-во ядер).
 */
object PerfDispatchers {
    val cpu: CoroutineDispatcher by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val size = System.getenv("CPU_POOL_SIZE")?.toIntOrNull()?.coerceAtLeast(1) ?: cores
        val tf =
            ThreadFactory { r ->
                Thread(r, "cpu-dispatcher-${System.nanoTime()}").apply { isDaemon = true }
            }
        Executors.newFixedThreadPool(size, tf).asCoroutineDispatcher()
    }
}

/**
 * Установить безопасные лимиты HTTP на уровне приложения.
 *
 * @param maxRequestSizeBytes максимальный размер тела запроса в байтах
 * Примечание: engine-таймауты Netty (idle/read/write) настраиваются в application.conf;
 * здесь реализован fast-fail по Content-Length → 413.
 */
fun Application.installServerTuning(
    maxRequestSizeBytes: Long =
        System.getenv("MAX_REQUEST_SIZE_BYTES")?.toLongOrNull()
            ?: DEFAULT_MAX_REQUEST_SIZE_BYTES,
) {
    intercept(ApplicationCallPipeline.Setup) {
        val clHeader = call.request.header("Content-Length")
        val length = clHeader?.toLongOrNull()
        if (length != null && length > maxRequestSizeBytes) {
            call.respondText(
                text = "Payload too large",
                status = HttpStatusCode.PayloadTooLarge,
            )
            finish()
        }
    }
}

/**
 * Хелпер: безопасно читать тело без блокировок и без переполнения.
 * Если Content-Length отсутствует, можно дополнительно подсчитать фактический размер (при необходимости).
 */
suspend fun PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.safeReceiveBytes(
    maxRequestSizeBytes: Long,
): ByteArray? {
    val clHeader = call.request.header("Content-Length")?.toLongOrNull()
    if (clHeader != null && clHeader > maxRequestSizeBytes) {
        call.respondText(
            text = "Payload too large",
            status = HttpStatusCode.PayloadTooLarge,
        )
        return null
    }
    return withContext(PerfDispatchers.cpu) {
        call.receiveChannel().toByteArray()
    }
}

private suspend fun ByteReadChannel.toByteArray(): ByteArray {
    // Ktor 3.x: readBytes() → readByteArray()
    return readRemaining(DEFAULT_BUFFER_SIZE.toLong()).readByteArray()
}
