package com.example.bot.data.repo

import com.example.bot.data.db.Clubs
import com.example.bot.data.db.HqNotify
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

data class HqEndpoints(
    val chatId: Long,
    val general: Int?,
    val bookings: Int?,
    val lists: Int?,
    val qa: Int?,
    val system: Int?,
)

data class ClubNotify(
    val clubId: Int,
    val adminChatId: Long?,
    val generalTopicId: Int?,
    val bookingsTopicId: Int?,
    val listsTopicId: Int?,
    val qaTopicId: Int?,
    val mediaTopicId: Int?,
    val systemTopicId: Int?,
)

class NotifyEndpointRepository(private val db: Database) {
    suspend fun loadHq(): HqEndpoints? {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            HqNotify
                .selectAll()
                .limit(1)
                .firstOrNull()
                ?.toHqEndpoints()
        }
    }

    suspend fun listClubs(): List<ClubNotify> {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            Clubs
                .selectAll()
                .map { it.toClubNotify() }
        }
    }

    private fun ResultRow.toHqEndpoints(): HqEndpoints {
        return HqEndpoints(
            chatId = this[HqNotify.chatId],
            general = this[HqNotify.generalTopicId],
            bookings = this[HqNotify.bookingsTopicId],
            lists = this[HqNotify.listsTopicId],
            qa = this[HqNotify.qaTopicId],
            system = this[HqNotify.systemTopicId],
        )
    }

    private fun ResultRow.toClubNotify(): ClubNotify {
        return ClubNotify(
            clubId = this[Clubs.id].value,
            adminChatId = this[Clubs.adminChatId],
            generalTopicId = this[Clubs.generalTopicId],
            bookingsTopicId = this[Clubs.bookingsTopicId],
            listsTopicId = this[Clubs.listsTopicId],
            qaTopicId = this[Clubs.qaTopicId],
            mediaTopicId = this[Clubs.mediaTopicId],
            systemTopicId = this[Clubs.systemTopicId],
        )
    }
}
