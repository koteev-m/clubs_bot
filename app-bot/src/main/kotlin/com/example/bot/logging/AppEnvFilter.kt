package com.example.bot.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

class AppEnvFilter : Filter<ILoggingEvent>() {
    private var requiredEnv: String? = null
    private var onMatch: FilterReply = FilterReply.NEUTRAL
    private var onMismatch: FilterReply = FilterReply.DENY
    private var currentEnv: String = DEFAULT_ENV

    fun setRequiredEnv(value: String) {
        requiredEnv = value.trim().ifEmpty { null }
    }

    fun setOnMatch(value: String) {
        onMatch = parseReply(value)
    }

    fun setOnMismatch(value: String) {
        onMismatch = parseReply(value)
    }

    override fun start() {
        val env = resolveEnv()
        if (requiredEnv.isNullOrBlank()) {
            addError("requiredEnv must be specified for AppEnvFilter")
            return
        }
        currentEnv = env
        super.start()
    }

    override fun decide(event: ILoggingEvent?): FilterReply {
        if (!isStarted) {
            return FilterReply.NEUTRAL
        }

        val targetEnv = requiredEnv ?: return FilterReply.NEUTRAL
        return if (currentEnv.equals(targetEnv, ignoreCase = true)) {
            onMatch
        } else {
            onMismatch
        }
    }

    private fun resolveEnv(): String {
        val ctxEnv = context?.getProperty(APP_ENV_PROPERTY)?.takeIf { it.isNotBlank() }
        val systemProp = System.getProperty(APP_ENV_PROPERTY)?.takeIf { it.isNotBlank() }
        val envVar = System.getenv(APP_ENV_PROPERTY)?.takeIf { it.isNotBlank() }
        return ctxEnv ?: systemProp ?: envVar ?: DEFAULT_ENV
    }

    private fun parseReply(value: String): FilterReply {
        return runCatching { FilterReply.valueOf(value.trim().uppercase()) }
            .getOrDefault(FilterReply.NEUTRAL)
    }

    companion object {
        private const val APP_ENV_PROPERTY = "APP_ENV"
        private const val DEFAULT_ENV = "dev"
    }
}
