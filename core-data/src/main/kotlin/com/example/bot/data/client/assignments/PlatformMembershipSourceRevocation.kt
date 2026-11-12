package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.client.onboarding.roleFoundationSchemaPresent

/**
 * A transaction-local capability for the existing platform membership path, never a public API.
 * Only the configured platform authority can prepare it after current membership CAS/client lock.
 * It permits legacy scope cleanup only after all corresponding V077 authority has been fenced.
 */
internal class PlatformMembershipSourceRevocation private constructor(
    private val origin: OnboardingSession,
    private val clientId: Long,
    private val userId: Long,
    private val memberVersion: Long,
    private val restoring: Boolean,
    private val controlledClubs: Set<Long>,
) {
    private var consumed = false

    fun consume(
        session: OnboardingSession,
        clientId: Long,
        userId: Long,
    ) {
        val sameScope = this.clientId == clientId && this.userId == userId
        if (consumed || session !== origin || !sameScope) {
            fail("invalid_transition")
        }
        currentMember(session, clientId, userId, memberVersion, restoring)
        consumed = true
    }

    /** Flat grants cannot bypass a disabled/controlled admission through the mounted legacy reader. */
    fun requireLegacyRestoreAllowed(clubId: Long) {
        if (clubId in controlledClubs) fail("invalid_transition")
    }

    companion object {
        fun prepare(
            session: OnboardingSession,
            clientId: Long,
            userId: Long,
            memberVersion: Long,
            restoring: Boolean,
        ): PlatformMembershipSourceRevocation {
            currentMember(session, clientId, userId, memberVersion, restoring)
            val clubs =
                if (session.sql.roleFoundationSchemaPresent()) {
                    PlatformMembershipSourceInvalidator(session, clientId, userId, restoring).apply()
                } else {
                    emptySet()
                }
            return PlatformMembershipSourceRevocation(session, clientId, userId, memberVersion, restoring, clubs)
        }

        private fun currentMember(
            session: OnboardingSession,
            clientId: Long,
            userId: Long,
            version: Long,
            restoring: Boolean,
        ) {
            session.owner()
            if (session.sql.connection.autoCommit || session.userId == null) fail("invalid_transition")
            session.client(clientId, lock = true)
            val member =
                session.sql.row(
                    "SELECT * FROM client_memberships WHERE client_id=? AND user_id=?",
                    clientId,
                    userId,
                ) ?: fail("not_found")
            if (member.long("version") != version) fail("version_conflict")
            if (restoring == member.active()) fail("invalid_transition")
        }
    }
}
