package com.example.bot.routes

import com.example.bot.data.repo.ClubRepository
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.example.bot.data.repo.ClubDto as ClubProjection

@Serializable
data class ClubDto(
    val id: Long,
    val name: String,
)

private fun ClubProjection.toDto(): ClubDto =
    ClubDto(
        id = id,
        name = name,
    )

fun Application.clubsPublicRoutes(repository: ClubRepository) {
    routing {
        get("/api/clubs") {
            val clubs =
                withContext(Dispatchers.IO + MDCContext()) {
                    repository.listClubs(limit = Int.MAX_VALUE)
                }
            val response = clubs.map { it.toDto() }
            call.application.log.info("clubs_public.list count={}", response.size)
            call.respond(response)
        }
    }
}
