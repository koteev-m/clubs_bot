package com.example.bot.data.client

import java.util.Collections

/** Server-selected restriction; it never grants membership or club access by itself. */
sealed interface ClientAudience {
    data object Platform : ClientAudience

    class Client(
        val clientId: Long,
        allowedClubIds: Set<Long>,
    ) : ClientAudience {
        val allowedClubIds: Set<Long> = Collections.unmodifiableSet(HashSet(allowedClubIds))

        init {
            require(clientId > 0 && this.allowedClubIds.all { it > 0 }) { "Invalid client audience" }
        }
    }
}

data class ClientClub(
    val id: Long,
    val name: String,
)

data class ClientContext(
    val id: Long,
    val name: String,
    val clubs: List<ClientClub>,
)

data class ClientClubDetail(
    val clientId: Long,
    val id: Long,
    val name: String,
)

/** Reads authority and its minimal projection in the same fresh database statement snapshot. */
interface ClientContextRepository {
    suspend fun context(
        telegramUserId: Long,
        audience: ClientAudience,
    ): List<ClientContext>

    suspend fun club(
        telegramUserId: Long,
        audience: ClientAudience,
        clubId: Long,
    ): ClientClubDetail?
}
