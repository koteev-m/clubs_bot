package com.example.bot.data.client.onboarding

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

/** Direct profile ownership and initial venue identity cannot be removed to free an allowance. */
class DirectClubProfileImmutableTrigger : Trigger {
    override fun fire(
        connection: Connection?,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        if (oldRow == null || newRow == null) rejectMutation()
        if ((0 until IMMUTABLE_COLUMNS).any { oldRow[it] != newRow[it] } ||
            oldRow[CREATED_AT_COLUMN] != newRow[CREATED_AT_COLUMN]
        ) {
            rejectMutation()
        }
    }

    private fun rejectMutation(): Nothing = throw SQLException("client_direct_club_profile_immutable", "23514")

    private companion object {
        const val IMMUTABLE_COLUMNS = 12
        const val CREATED_AT_COLUMN = 13
    }
}
