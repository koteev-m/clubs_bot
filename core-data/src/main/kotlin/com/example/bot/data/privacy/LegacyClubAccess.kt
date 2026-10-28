package com.example.bot.data.privacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/** Recognized legacy object identities, never arbitrary client-provided SQL identifiers. */
enum class LegacyResource(
    internal val ownership: String,
) {
    CLUB("SELECT c.club_id FROM client_clubs c WHERE CAST(c.club_id AS VARCHAR) = ?"),
    EVENT("SELECT r.club_id FROM events r WHERE CAST(r.id AS VARCHAR) = ?"),
    HALL("SELECT r.club_id FROM halls r WHERE CAST(r.id AS VARCHAR) = ?"),
    HALL_TABLE("SELECT h.club_id FROM hall_tables r JOIN halls h ON h.id=r.hall_id WHERE CAST(r.id AS VARCHAR) = ?"),
    TABLE("SELECT r.club_id FROM tables r WHERE CAST(r.id AS VARCHAR) = ?"),
    BOOKING("SELECT r.club_id FROM bookings r WHERE CAST(r.id AS VARCHAR) = ?"),
    HOLD("SELECT e.club_id FROM booking_holds r JOIN events e ON e.id=r.event_id WHERE CAST(r.id AS VARCHAR) = ?"),
    WAITLIST("SELECT r.club_id FROM waitlist r WHERE CAST(r.id AS VARCHAR) = ?"),
    TICKET("SELECT r.club_id FROM tickets r WHERE CAST(r.id AS VARCHAR) = ?"),
    GUEST_LIST("SELECT r.club_id FROM guest_lists r WHERE CAST(r.id AS VARCHAR) = ?"),
    GUEST_ENTRY(
        "SELECT l.club_id FROM guest_list_entries r JOIN guest_lists l ON l.id=r.guest_list_id " +
            "WHERE CAST(r.id AS VARCHAR) = ?",
    ),
    INVITATION(
        "SELECT l.club_id FROM invitations r JOIN guest_list_entries e ON e.id=r.guest_list_entry_id " +
            "JOIN guest_lists l ON l.id=e.guest_list_id WHERE CAST(r.id AS VARCHAR) = ?",
    ),
    MUSIC_ITEM("SELECT r.club_id FROM music_items r WHERE CAST(r.id AS VARCHAR) = ?"),
    MUSIC_PLAYLIST("SELECT r.club_id FROM music_playlists r WHERE CAST(r.id AS VARCHAR) = ?"),
    MUSIC_BATTLE("SELECT r.club_id FROM music_battles r WHERE CAST(r.id AS VARCHAR) = ?"),
    SESSION("SELECT r.club_id FROM table_sessions r WHERE CAST(r.id AS VARCHAR) = ?"),
}

data class LegacyReference(
    val kind: LegacyResource,
    val id: String,
) {
    constructor(kind: LegacyResource, id: Long) : this(kind, id.toString())
}

fun interface LegacyClubAccess {
    /** Missing and unmapped resources are left to existing identity/ownership checks. Failures must propagate. */
    suspend fun containsMappedResource(references: Collection<LegacyReference>): Boolean
}

/** Reads only ownership columns. Mapping is durable, so successful checks need no mutable publication cache. */
class JdbcLegacyClubAccess(
    private val dataSource: DataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LegacyClubAccess {
    /** Ownership-only predicate for existing synchronous in-memory booking APIs. Never cached. */
    fun isLegacyClub(clubId: Long): Boolean =
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.prepareStatement("SELECT 1 FROM client_clubs WHERE club_id=?").use { statement ->
                statement.setLong(1, clubId)
                statement.executeQuery().use { !it.next() }
            }
        }

    override suspend fun containsMappedResource(references: Collection<LegacyReference>): Boolean {
        return withContext(ioDispatcher) {
            if (references.isEmpty()) return@withContext false
            dataSource.connection.use { connection ->
                connection.isReadOnly = true
                references.distinct().any { reference ->
                    val sql =
                        "SELECT 1 FROM client_clubs private_club WHERE private_club.club_id IN (" +
                            reference.kind.ownership + ")"
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, reference.id.toLongOrNull()?.toString() ?: reference.id.lowercase())
                        statement.executeQuery().use { it.next() }
                    }
                }
            }
        }
    }
}
