package com.example.bot.plugins

import io.ktor.server.application.Application

private val prodLikeProfiles = setOf("PROD", "PRODUCTION", "STAGE", "STAGING")

internal fun Application.isProdLikeProfile(): Boolean {
    val profile = (resolveEnv("APP_PROFILE") ?: resolveEnv("APP_ENV"))?.trim()?.uppercase()
    return profile in prodLikeProfiles
}
