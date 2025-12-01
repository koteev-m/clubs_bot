package com.example.bot.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight counters for cache hits/misses (304 vs 200) by route tag.
 */
object RouteCacheMetrics {
    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    private fun counter(name: String, route: String): Counter? {
        val registry = runCatching { Metrics.globalRegistry }.getOrNull() ?: return null
        return counters.getOrPut(name to route) {
            registry.find(name).tag("route", route).counter()
                ?: Counter.builder(name)
                    .description("Cache response metric")
                    .tag("route", route)
                    .register(registry)
        }
    }

    fun recordNotModified(route: String) {
        counter("miniapp.cache.hit304", route)?.increment()
    }

    fun recordOk(route: String) {
        counter("miniapp.cache.miss304", route)?.increment()
    }
}
