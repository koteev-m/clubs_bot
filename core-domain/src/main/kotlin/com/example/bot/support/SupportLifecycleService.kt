package com.example.bot.support

enum class SupportLifecycleMutation {
    RESOLVE,
    CLOSE,
}

interface SupportLifecycleService {
    suspend fun mutateLifecycle(
        ticketId: Long,
        agentUserId: Long,
        mutation: SupportLifecycleMutation,
    ): SupportServiceResult<Ticket> = SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)

    suspend fun resolve(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket> =
        mutateLifecycle(
            ticketId = ticketId,
            agentUserId = agentUserId,
            mutation = SupportLifecycleMutation.RESOLVE,
        )

    suspend fun close(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket> =
        mutateLifecycle(
            ticketId = ticketId,
            agentUserId = agentUserId,
            mutation = SupportLifecycleMutation.CLOSE,
        )
}
