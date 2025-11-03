package com.example.bot.logging

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLoggingConfig

fun CallLoggingConfig.callIdMdc(key: String) {
    mdc(key) { call: ApplicationCall -> call.callId }
}
