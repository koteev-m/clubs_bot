package com.example.bot.data.privacy

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.CustomAuditAction
import com.example.bot.audit.CustomAuditEntityType
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.club.GuestListEntriesTable
import com.example.bot.data.db.withTxRetry
import com.example.bot.data.security.UsersTable
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class PrivacyService(
    private val db: Database,
    private val phoneCipher: PhoneCipher,
    private val retentionConfig: PrivacyRetentionConfig,
    private val auditLogRepository: AuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun backfillPhoneProtection(): Int =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                var updated = 0
                updated += backfillUsers()
                updated += backfillBookings()
                updated += backfillGuestListEntries()
                updated
            }
        }

    suspend fun anonymizeUser(
        userId: Long,
        actor: PrivacyAdminActor,
        reason: String,
    ): PrivacyAnonymizeResult {
        require(reason.isNotBlank()) { "reason must not be blank" }
        val result =
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    val now = clock.instant().atOffset(ZoneOffset.UTC)
                    val usersUpdated =
                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[phoneE164] = null
                            it[encryptedPhone] = null
                            it[phoneHash] = null
                            it[anonymizedAt] = now
                        }
                    val bookingsUpdated =
                        BookingsTable.update({ BookingsTable.guestUserId eq userId }) {
                            it[phoneE164] = null
                            it[encryptedPhone] = null
                            it[phoneHash] = null
                            it[anonymizedAt] = now
                        }
                    val guestListEntriesUpdated =
                        GuestListEntriesTable.update({ GuestListEntriesTable.telegramUserId eq userId }) {
                            it[phone] = null
                            it[encryptedPhone] = null
                            it[phoneHash] = null
                            it[anonymizedAt] = now
                        }
                    PrivacyAnonymizeResult(usersUpdated, bookingsUpdated, guestListEntriesUpdated)
                }
            }
        auditLogRepository.append(
            AuditLogEvent(
                clubId = null,
                nightId = null,
                actorUserId = actor.userId,
                actorRole = actor.role,
                subjectUserId = userId,
                entityType = CustomAuditEntityType("PRIVACY_USER"),
                entityId = userId,
                action = CustomAuditAction("ANONYMIZE"),
                fingerprint = privacyFingerprint("anonymize", actor.userId, userId, reason),
                metadata = buildJsonObject {
                    put("reason", reason)
                    put("usersUpdated", result.usersUpdated)
                    put("bookingsUpdated", result.bookingsUpdated)
                    put("guestListEntriesUpdated", result.guestListEntriesUpdated)
                },
            ),
        )
        return result
    }

    suspend fun runRetention(actor: PrivacyAdminActor? = null): PrivacyRetentionResult {
        val cutoff = clock.instant().minus(retentionConfig.guestListPhoneRetention).atOffset(ZoneOffset.UTC)
        val scrubbed =
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    GuestListEntriesTable.update({
                        (GuestListEntriesTable.createdAt less cutoff) and GuestListEntriesTable.phoneHash.isNotNull()
                    }) {
                        it[phone] = null
                        it[encryptedPhone] = null
                        it[phoneHash] = null
                        it[anonymizedAt] = clock.instant().atOffset(ZoneOffset.UTC)
                    }
                }
            }
        auditLogRepository.append(
            AuditLogEvent(
                clubId = null,
                nightId = null,
                actorUserId = actor?.userId,
                actorRole = actor?.role,
                subjectUserId = null,
                entityType = CustomAuditEntityType("PRIVACY_RETENTION"),
                entityId = null,
                action = CustomAuditAction("SCRUB"),
                fingerprint = privacyFingerprint("retention", actor?.userId ?: 0L, scrubbed.toLong(), cutoff.toInstant().toString()),
                metadata = buildJsonObject {
                    put("guestListEntriesScrubbed", scrubbed)
                    put("cutoff", cutoff.toInstant().toString())
                },
            ),
        )
        return PrivacyRetentionResult(scrubbed)
    }

    private fun backfillUsers(): Int = backfillTable(
        select = UsersTable.selectAll().where { UsersTable.phoneE164.isNotNull() and UsersTable.encryptedPhone.isNull() },
        apply = { rowId, protected ->
            UsersTable.update({ UsersTable.id eq rowId }) {
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[phoneE164] = null
            }
        },
        idExtractor = { it[UsersTable.id] },
        phoneExtractor = { it[UsersTable.phoneE164]!! },
    )

    private fun backfillBookings(): Int = backfillTable(
        select = BookingsTable.selectAll().where { BookingsTable.phoneE164.isNotNull() and BookingsTable.encryptedPhone.isNull() },
        apply = { rowId, protected ->
            BookingsTable.update({ BookingsTable.id eq rowId }) {
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[phoneE164] = null
            }
        },
        idExtractor = { it[BookingsTable.id] },
        phoneExtractor = { it[BookingsTable.phoneE164]!! },
    )

    private fun backfillGuestListEntries(): Int = backfillTable(
        select = GuestListEntriesTable.selectAll().where { GuestListEntriesTable.phone.isNotNull() and GuestListEntriesTable.encryptedPhone.isNull() },
        apply = { rowId, protected ->
            GuestListEntriesTable.update({ GuestListEntriesTable.id eq rowId }) {
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[phone] = null
            }
        },
        idExtractor = { it[GuestListEntriesTable.id] },
        phoneExtractor = { it[GuestListEntriesTable.phone]!! },
    )

    private fun <ID> backfillTable(
        select: org.jetbrains.exposed.sql.Query,
        apply: (ID, ProtectedPhone) -> Int,
        idExtractor: (org.jetbrains.exposed.sql.ResultRow) -> ID,
        phoneExtractor: (org.jetbrains.exposed.sql.ResultRow) -> String,
    ): Int {
        var count = 0
        select.forEach { row ->
            val protected = phoneCipher.protect(phoneExtractor(row))
            apply(idExtractor(row), protected)
            count += 1
        }
        return count
    }

    private fun privacyFingerprint(vararg values: Any): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { digest.update(it.toString().toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
