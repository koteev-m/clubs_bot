package com.example.bot.data.client.onboarding

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

/** H2 equivalent of V063's PostgreSQL guard, compiled once instead of invoking javac per database. */
class ClientClubOnboardingImmutableTrigger : Trigger {
    override fun fire(
        connection: Connection?,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        if (oldRow == null ||
            newRow == null ||
            (0 until IMMUTABLE_COLUMN_COUNT).any { oldRow[it] != newRow[it] }
        ) {
            throw SQLException("client_club_onboarding_immutable", "23514")
        }
    }

    private companion object {
        // The first nine V063 columns pin approval, ownership, and original creation identity.
        const val IMMUTABLE_COLUMN_COUNT = 9
    }
}
