package com.example.bot.plugins

import com.example.bot.security.rbac.rbacContext
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import kotlinx.coroutines.Job
import org.slf4j.MDC

private const val ACTOR_ID_KEY = "actor_id"

val ActorMdcPlugin =
    createApplicationPlugin(name = "ActorMdcPlugin") {
        onCall { call ->
            val actorId =
                runCatching {
                    val context = call.rbacContext()
                    context.user.id.toString()
                }.getOrNull()
            if (actorId.isNullOrBlank()) {
                return@onCall
            }
            val closeable = MDC.putCloseable(ACTOR_ID_KEY, actorId)
            val job = call.coroutineContext[Job]
            if (job == null) {
                closeable.close()
            } else {
                job.invokeOnCompletion { closeable.close() }
            }
        }
    }

fun Application.installActorMdcPlugin() {
    install(ActorMdcPlugin)
}
