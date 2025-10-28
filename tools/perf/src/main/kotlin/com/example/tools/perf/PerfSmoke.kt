package com.example.tools.perf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.toKotlinDuration

data class Args(
    val baseUrl: String,
    val endpoints: List<String>,
    val workers: Int,
    val duration: Duration,
    val targetRps: Int,
    val assertP95: Duration,
    val maxErrorRate: Double,
    val warmup: Duration,
)

private const val DEFAULT_WORKERS = 8
private const val DEFAULT_TARGET_RPS = 0
private const val DEFAULT_MAX_ERROR_RATE = 0.01
private const val MAX_THREAD_POOL_WORKERS = 256
private val DEFAULT_DURATION: Duration = Duration.ofSeconds(30)
private val DEFAULT_WARMUP: Duration = Duration.ofSeconds(3)
private val DEFAULT_ASSERT_P95: Duration = Duration.ofMillis(300)
private val ONE_SECOND_NANOS: Double = Duration.ofSeconds(1).toNanos().toDouble()
private val HTTP_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val HTTP_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 399
private const val PERCENTILE_MEDIAN = 0.50
private const val PERCENTILE_P95 = 0.95

private fun parseArgs(raw: Array<String>): Args {
    fun get(
        name: String,
        def: String? = null,
    ): String? =
        raw.asSequence()
            .mapNotNull {
                val p = it.trim()
                if (!p.startsWith("--")) null else p.removePrefix("--")
            }
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i < 0) kv to "" else kv.substring(0, i) to kv.substring(i + 1)
            }
            .toMap()[name] ?: def

    fun durationSeconds(
        name: String,
        default: Duration,
        minSeconds: Long,
    ): Duration =
        get(name, default.seconds.toString())
            ?.toLongOrNull()
            ?.coerceAtLeast(minSeconds)
            ?.let(Duration::ofSeconds)
            ?: default

    fun durationMillis(
        name: String,
        default: Duration,
        minMillis: Long,
    ): Duration =
        get(name, default.toMillis().toString())
            ?.toLongOrNull()
            ?.coerceAtLeast(minMillis)
            ?.let(Duration::ofMillis)
            ?: default

    val url = get("url") ?: error("--url is required (e.g. --url=http://localhost:8080)")
    val endpoints =
        (get("endpoints", "/health,/ready") ?: "/health,/ready")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    val workers =
        (get("workers", DEFAULT_WORKERS.toString()) ?: DEFAULT_WORKERS.toString())
            .toInt()
            .coerceAtLeast(1)
    val testDuration = durationSeconds("duration-sec", DEFAULT_DURATION, minSeconds = 1)
    val targetRps =
        (get("target-rps", DEFAULT_TARGET_RPS.toString()) ?: DEFAULT_TARGET_RPS.toString())
            .toInt()
            .coerceAtLeast(0)
    val assertP95 = durationMillis("assert-p95-ms", DEFAULT_ASSERT_P95, minMillis = 1)
    val maxErrorRate =
        (get("max-error-rate", DEFAULT_MAX_ERROR_RATE.toString()) ?: DEFAULT_MAX_ERROR_RATE.toString())
            .toDouble()
            .coerceIn(0.0, 1.0)
    val warmup = durationSeconds("warmup-sec", DEFAULT_WARMUP, minSeconds = 0)

    return Args(
        baseUrl = url.trimEnd('/'),
        endpoints = endpoints,
        workers = workers,
        duration = testDuration,
        targetRps = targetRps,
        assertP95 = assertP95,
        maxErrorRate = maxErrorRate,
        warmup = warmup,
    )
}

private class Metrics {
    val started = AtomicLong(0)
    val successful = AtomicLong(0)
    val errors = AtomicLong(0)
    val latencies = Collections.synchronizedList(mutableListOf<Duration>())
}

suspend fun main(vararg raw: String) {
    val args = parseArgs(raw as Array<String>)
    val http =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    val workerPool =
        Executors.newFixedThreadPool(args.workers.coerceAtMost(MAX_THREAD_POOL_WORKERS)) { r ->
            Thread(r, "perf-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    val scope = CoroutineScope(workerPool)

    val metrics = Metrics()

    if (!args.warmup.isZero) {
        val endWarmup = System.nanoTime() + args.warmup.toNanos()
        val jobs =
            (1..args.workers).map {
                scope.launch {
                    var idx = 0
                    while (System.nanoTime() < endWarmup) {
                        val endpoint = args.endpoints[idx % args.endpoints.size]
                        doOne(http, args.baseUrl, endpoint)
                        idx++
                    }
                }
            }
        jobs.joinAll()
    }

    val testEnd = System.nanoTime() + args.duration.toNanos()
    val targetDelay =
        if (args.targetRps > 0) {
            val perWorker = max(1.0, args.targetRps.toDouble() / args.workers.toDouble())
            Duration.ofNanos((ONE_SECOND_NANOS / perWorker).toLong())
        } else {
            Duration.ZERO
        }

    val jobs =
        (1..args.workers).map { w ->
            scope.launch(Dispatchers.IO) {
                var idx = w % args.endpoints.size
                var nextTime = System.nanoTime()
                while (System.nanoTime() < testEnd) {
                    if (!targetDelay.isZero) {
                        val now = System.nanoTime()
                        if (now < nextTime) {
                            val sleepDuration = Duration.ofNanos(nextTime - now)
                            delay(sleepDuration.toKotlinDuration())
                        }
                        nextTime += targetDelay.toNanos()
                    }
                    val endpoint = args.endpoints[idx % args.endpoints.size]
                    idx++
                    val startedAt = System.nanoTime()
                    metrics.started.incrementAndGet()
                    val status = doOne(http, args.baseUrl, endpoint)
                    val took = Duration.ofNanos(System.nanoTime() - startedAt)
                    if (status in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                        metrics.successful.incrementAndGet()
                        metrics.latencies.add(took)
                    } else {
                        metrics.errors.incrementAndGet()
                    }
                }
            }
        }

    jobs.joinAll()
    scope.cancel()

    val total = metrics.started.get()
    val ok = metrics.successful.get()
    val err = metrics.errors.get()
    val errorRate = if (total == 0L) 0.0 else err.toDouble() / total.toDouble()
    val rps =
        if (!args.duration.isZero) {
            total.toDouble() / (args.duration.toNanos().toDouble() / ONE_SECOND_NANOS)
        } else {
            0.0
        }

    val p50 = percentile(metrics.latencies, PERCENTILE_MEDIAN)
    val p95 = percentile(metrics.latencies, PERCENTILE_P95)

    printReport(args, total, ok, err, errorRate, rps, p50, p95)

    val slaFailed = (p95 != null && p95 > args.assertP95) || errorRate > args.maxErrorRate
    if (slaFailed) {
        System.err.println(
            "SLA FAILED: p95=${p95?.toMillis() ?: -1}ms (limit=${args.assertP95.toMillis()}ms), " +
                "errorRate=${"%.4f".format(Locale.ROOT, errorRate)} (limit=${args.maxErrorRate})",
        )
        kotlin.system.exitProcess(1)
    } else {
        println("SLA OK")
        kotlin.system.exitProcess(0)
    }
}

private fun percentile(
    values: List<Duration>,
    q: Double,
): Duration? {
    if (values.isEmpty()) return null
    val sorted = values.toMutableList().sorted()
    val idx = min(sorted.size - 1, max(0, ceil(q * sorted.size).toInt() - 1))
    return sorted[idx]
}

private fun printReport(
    args: Args,
    total: Long,
    ok: Long,
    err: Long,
    errRate: Double,
    rps: Double,
    p50: Duration?,
    p95: Duration?,
) {
    val durationSeconds = args.duration.seconds
    val p50Millis = p50?.toMillis() ?: -1
    val p95Millis = p95?.toMillis() ?: -1
    val assertP95Millis = args.assertP95.toMillis()
    println("=== PerfSmoke Report ===")
    println("URL: ${args.baseUrl}")
    println("Endpoints: ${args.endpoints.joinToString(",")}")
    println("Workers: ${args.workers}, Duration: ${durationSeconds}s, TargetRps: ${args.targetRps}")
    println("Total: $total, OK: $ok, Errors: $err (${String.format(Locale.ROOT, "%.2f%%", errRate * 100)})")
    val rpsFormatted = String.format(Locale.ROOT, "%.2f", rps)
    println("RPS: $rpsFormatted")
    println("p50: $p50Millis ms, p95: $p95Millis ms, SLA p95 <= $assertP95Millis ms")
    val errorRateFormatted = "%.6f".format(Locale.ROOT, errRate)
    val json =
        buildString {
            append('{')
            append("\"total\":")
            append(total)
            append(",\"ok\":")
            append(ok)
            append(",\"errors\":")
            append(err)
            append(",\"errorRate\":")
            append(errorRateFormatted)
            append(",\"rps\":")
            append(rpsFormatted)
            append(",\"p50Ms\":")
            append(p50Millis)
            append(",\"p95Ms\":")
            append(p95Millis)
            append(",\"assertP95Ms\":")
            append(assertP95Millis)
            append(",\"maxErrorRate\":")
            append(args.maxErrorRate)
            append('}')
        }
    println(json)
}

private suspend fun doOne(
    client: HttpClient,
    baseUrl: String,
    endpoint: String,
): Int {
    val uri = URI.create(if (endpoint.startsWith("/")) "$baseUrl$endpoint" else "$baseUrl/$endpoint")
    val request =
        HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .timeout(HTTP_REQUEST_TIMEOUT)
            .build()
    val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
    return response.statusCode()
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { result, ex ->
            if (ex == null) cont.resume(result) else cont.resumeWithException(ex)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
