package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject

/** Constructed exclusively from the fresh, server-verified platform connection identity. */
data class OnboardingActor(
    val telegramUserId: Long,
    val connectionKey: String,
    val displayName: String? = null,
    val username: String? = null,
)

enum class OnboardingRead {
    INVITATION_RESOLVE,
    INVITATION_STATUS,
    CLIENT_ONBOARDING,
    CLIENT_APPLICATION_SEARCH,
    CLIENT_APPLICATION_GET,
    CLUB_PROFILE_GET,
    PLATFORM_INVITATION_GET,
    PLATFORM_CLIENT_SEARCH,
    PLATFORM_CLIENT_GET,
    PLATFORM_APPLICATION_SEARCH,
    PLATFORM_APPLICATION_GET,
}

enum class OnboardingMutation {
    INVITATION_ISSUE,
    INVITATION_CLAIM,
    INVITATION_CONFIRM,
    INVITATION_REVOKE,
    INVITATION_REISSUE,
    MEMBER_REVOKE,
    MEMBER_RESTORE,
    APPLICATION_CREATE,
    APPLICATION_UPDATE,
    APPLICATION_SUBMIT,
    APPLICATION_CANCEL,
    APPLICATION_REVIEW,
    APPLICATION_APPROVE,
    APPLICATION_REJECT,
    PLATFORM_APPLICATION_CANCEL,
    APPLICATION_CREATE_CLUB,
    CLUB_PROFILE_UPDATE,
}

class OnboardingFailure(
    val code: String,
) : RuntimeException(code)

interface ClientOnboardingRepository {
    suspend fun read(
        actor: OnboardingActor,
        operation: OnboardingRead,
        resourceId: Long? = null,
        body: JsonObject = JsonObject(emptyMap()),
    ): JsonObject

    suspend fun mutate(
        actor: OnboardingActor,
        operation: OnboardingMutation,
        resourceId: Long? = null,
        body: JsonObject,
        idempotencyKey: String,
    ): JsonObject
}
