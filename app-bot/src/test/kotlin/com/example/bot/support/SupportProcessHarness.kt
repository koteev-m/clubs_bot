package com.example.bot.support

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal const val PRODUCTION_ENGINE_MAIN: String = "io.ktor.server.netty.EngineMain"

internal data class RestartHttpResponse(
    val status: Int,
    val body: String,
)

internal object RestartHttpClient {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(8),
    ): RestartHttpResponse {
        val builder =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(timeout)
        headers.forEach(builder::header)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = builder.method(method, publisher).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return RestartHttpResponse(status = response.statusCode(), body = response.body())
    }
}

internal class SupportChildProcess private constructor(
    val label: String,
    val port: Int,
    val stdoutPath: Path,
    val stderrPath: Path,
    private val process: Process,
) {
    val pid: Long = process.pid()
    val startedAt: Instant = process.info().startInstant().orElseGet(Instant::now)
    val baseUrl: String = "http://127.0.0.1:$port"

    fun isAlive(): Boolean = process.isAlive

    fun awaitReadiness(timeout: Duration = Duration.ofSeconds(45)): Instant {
        awaitCondition(
            description = "$label readiness",
            timeout = timeout,
        ) {
            checkStillAlive("while waiting for readiness")
            val response = RestartHttpClient.request(method = "GET", url = "$baseUrl/ready")
            response.status == 200 && response.body == "READY"
        }
        return Instant.now()
    }

    fun checkStillAlive(context: String) {
        if (!process.isAlive) {
            throw AssertionError(
                "$label exited unexpectedly $context with exit=${process.exitValue()}",
            )
        }
    }

    fun stopGracefully(timeout: Duration = Duration.ofSeconds(20)): Int {
        checkStillAlive("before requested shutdown")
        process.destroy()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            throw AssertionError("$label did not exit within $timeout")
        }
        check(!process.isAlive) { "$label remained alive after waitFor" }
        return process.exitValue()
    }

    fun forceStop() {
        if (!process.isAlive) {
            return
        }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "$label could not be terminated" }
        }
        check(!process.isAlive) { "$label remained alive after forced cleanup" }
    }

    fun boundedLogTail(): String =
        buildString {
            appendLine("$label stdout tail:")
            appendLine(readBoundedTail(stdoutPath))
            appendLine("$label stderr tail:")
            append(readBoundedTail(stderrPath))
        }

    companion object {
        fun start(
            label: String,
            port: Int,
            temporaryDirectory: Path,
            environment: Map<String, String>,
        ): SupportChildProcess {
            Files.createDirectories(temporaryDirectory)
            val stdoutPath = temporaryDirectory.resolve("$label-stdout.log")
            val stderrPath = temporaryDirectory.resolve("$label-stderr.log")
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val command =
                listOf(
                    javaExecutable,
                    "-Dfile.encoding=UTF-8",
                    "-XX:+ExitOnOutOfMemoryError",
                    "-Dio.ktor.development=false",
                    "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
                    "-cp",
                    System.getProperty("java.class.path"),
                    PRODUCTION_ENGINE_MAIN,
                )
            val builder =
                ProcessBuilder(command)
                    .redirectOutput(stdoutPath.toFile())
                    .redirectError(stderrPath.toFile())
            builder.environment().apply {
                clear()
                putAll(environment)
                put("PORT", port.toString())
            }
            val process = builder.start()
            return SupportChildProcess(
                label = label,
                port = port,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                process = process,
            )
        }
    }
}

internal fun findFreeLoopbackPort(excludedPorts: Set<Int> = emptySet()): Int {
    repeat(MAX_PORT_SELECTION_ATTEMPTS) {
        val candidate =
            ServerSocket(0, 1, InetAddress.getByName(IPV4_LOOPBACK_ADDRESS)).use { socket ->
                socket.localPort
            }
        if (candidate !in excludedPorts) {
            return candidate
        }
    }
    error("Could not allocate a distinct IPv4 loopback port after $MAX_PORT_SELECTION_ATTEMPTS attempts")
}

internal fun awaitCondition(
    description: String,
    timeout: Duration,
    diagnostics: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadlineNanos = System.nanoTime() + timeout.toNanos()
    var lastFailure: Exception? = null
    while (System.nanoTime() < deadlineNanos) {
        try {
            if (condition()) {
                return
            }
            lastFailure = null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("Interrupted while waiting for $description", interrupted)
        } catch (failure: Exception) {
            lastFailure = failure
        }
        LockSupport.parkNanos(Duration.ofMillis(75).toNanos())
    }
    val failureSummary = lastFailure?.let { "\nlast failure=${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
    throw AssertionError("Timed out after $timeout waiting for $description$failureSummary\n${diagnostics()}")
}

private fun readBoundedTail(
    path: Path,
    byteLimit: Int = 16 * 1024,
): String {
    if (!Files.exists(path)) {
        return "<missing>"
    }
    val size = Files.size(path)
    val start = (size - byteLimit).coerceAtLeast(0L)
    val length = (size - start).toInt()
    val buffer = ByteBuffer.allocate(length)
    Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
        channel.position(start)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // Continue until the bounded tail has been read.
        }
    }
    buffer.flip()
    return StandardCharsets.UTF_8.decode(buffer).toString()
}

private const val IPV4_LOOPBACK_ADDRESS = "127.0.0.1"
private const val MAX_PORT_SELECTION_ATTEMPTS = 32
