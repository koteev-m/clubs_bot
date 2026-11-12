package com.example.bot.data.client.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import javax.sql.DataSource

/**
 * Every mutation, command result, and audit event uses one connection and transaction.
 * The actor row serializes idempotency; the client row serializes all authority/lifecycle changes.
 */
class JdbcClientOnboardingRepository(
    private val dataSource: DataSource,
    private val configuredOwnerId: Long?,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClientOnboardingRepository {
    override suspend fun read(
        actor: OnboardingActor,
        operation: OnboardingRead,
        resourceId: Long?,
        body: JsonObject,
    ): JsonObject =
        withContext(dispatcher) {
            validateActor(actor)
            transaction(
                readOnly =
                    operation !in
                        setOf(
                            OnboardingRead.CLUB_PROFILE_GET,
                            OnboardingRead.CLUB_TEAM_GET,
                            OnboardingRead.CLUB_PREPARATION_GET,
                            OnboardingRead.INVITATION_RESOLVE,
                            OnboardingRead.INVITATION_STATUS,
                            OnboardingRead.CLUB_LAYOUT_GET,
                            OnboardingRead.CLUB_FINANCE_GET,
                            OnboardingRead.CLUB_MODULE_SETTINGS_GET,
                            OnboardingRead.CLUB_PLAN_GET,
                            OnboardingRead.CLUB_CALENDAR_GET,
                            OnboardingRead.CLUB_CALENDAR_COMMAND_RESOLVE,
                            OnboardingRead.CLUB_NIGHT_EVENT_CANDIDATE,
                            OnboardingRead.CLUB_BOOKING_RULES_GET,
                            OnboardingRead.CLUB_BOOKING_PREVIEW_GET,
                        ),
            ) { sql ->
                val snapshotAt = if (operation == OnboardingRead.CLUB_TABLE_AVAILABILITY_GET) clock.instant() else null
                val session =
                    OnboardingSession(
                        sql,
                        actor,
                        sql.user(actor.telegramUserId)?.long("id"),
                        configuredOwnerId,
                        clock,
                    )
                val invitations = InvitationOperations(session)
                val applications = ApplicationOperations(session)
                val clients = ClientAccessOperations(session)
                when (operation) {
                    OnboardingRead.INVITATION_RESOLVE -> invitations.resolve(body)
                    OnboardingRead.INVITATION_STATUS -> invitations.get(required(resourceId), platform = false)
                    OnboardingRead.CLIENT_ONBOARDING -> clients.onboarding(required(resourceId))
                    OnboardingRead.CLIENT_APPLICATION_SEARCH ->
                        applications.search(
                            required(resourceId),
                            platform = false,
                            body,
                        )
                    OnboardingRead.CLIENT_APPLICATION_GET -> applications.get(required(resourceId), platform = false)
                    OnboardingRead.CLUB_PREPARATION_GET -> ClubPreparationOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_TEAM_GET -> ClubTeamOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_PROFILE_GET -> ClubOnboardingOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_BOOKING_RULES_GET ->
                        ClubBookingRulesOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_BOOKING_PREVIEW_GET -> ClubBookingPreview(session).get(required(resourceId))
                    OnboardingRead.CLUB_TABLE_AVAILABILITY_GET ->
                        ClubTableAvailability(session).get(
                            required(resourceId),
                            body.id("nightId"),
                            body.id("hallId"),
                            checkNotNull(snapshotAt),
                        )
                    OnboardingRead.CLUB_CALENDAR_GET -> ClubCalendarOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_CALENDAR_COMMAND_RESOLVE ->
                        resolveCalendarCommand(session, required(resourceId), body)
                    OnboardingRead.CLUB_NIGHT_EVENT_CANDIDATE ->
                        ClubNightEventLinks(
                            session,
                        ).classify(required(resourceId))
                    OnboardingRead.CLUB_MODULE_SETTINGS_GET ->
                        ClubModuleSettingsOperations(
                            session,
                        ).get(required(resourceId))
                    OnboardingRead.CLUB_FINANCE_GET -> ClubFinanceOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_LAYOUT_GET -> ClubLayoutOperations(session).get(required(resourceId))
                    OnboardingRead.CLUB_PLAN_GET -> ClubLayoutOperations(session).plan(required(resourceId), body)
                    OnboardingRead.PLATFORM_INVITATION_GET -> invitations.get(required(resourceId), platform = true)
                    OnboardingRead.PLATFORM_CLIENT_SEARCH -> clients.search(body)
                    OnboardingRead.PLATFORM_CLUB_SEARCH -> clients.searchClubs(body)
                    OnboardingRead.PLATFORM_SUMMARY -> clients.platformSummary()
                    OnboardingRead.PLATFORM_CLIENT_GET -> clients.get(required(resourceId))
                    OnboardingRead.PLATFORM_APPLICATION_SEARCH -> applications.search(null, platform = true, body)
                    OnboardingRead.PLATFORM_APPLICATION_GET -> applications.get(required(resourceId), platform = true)
                }
            }
        }

    override suspend fun mutate(
        actor: OnboardingActor,
        operation: OnboardingMutation,
        resourceId: Long?,
        body: JsonObject,
        idempotencyKey: String,
    ): JsonObject =
        withContext(dispatcher) {
            validateActor(actor)
            if (!Regex("[A-Za-z0-9._:-]{8,128}").matches(idempotencyKey)) fail("validation_error")
            val requestHash = commandRequestHash(operation, resourceId, body)
            val keyHash = digest(idempotencyKey)
            val mutationContext = currentCoroutineContext()
            val memberMutation =
                operation == OnboardingMutation.MEMBER_REVOKE || operation == OnboardingMutation.MEMBER_RESTORE
            var attempt = 0
            while (true) {
                try {
                    return@withContext transaction(
                        readOnly = false,
                        beforeCommit = { if (memberMutation) mutationContext.ensureActive() },
                    ) { sql ->
                        val ownerOperation = operation in OWNER_OPERATIONS
                        if (ownerOperation && configuredOwnerId?.takeIf { it > 0 } != actor.telegramUserId) {
                            fail("forbidden")
                        }
                        val userId =
                            if (ownerOperation || operation == OnboardingMutation.INVITATION_CLAIM) {
                                sql.provision(actor)
                            } else {
                                sql.user(actor.telegramUserId)?.long("id") ?: fail("not_found")
                            }
                        sql.lockActor(userId)
                        val session =
                            OnboardingSession(sql, actor, userId, configuredOwnerId, clock, keyHash)
                        val existing =
                            sql.row(
                                "SELECT * FROM client_commands WHERE actor_user_id=? AND " +
                                    "connection_key=? AND key_hash=?",
                                userId,
                                actor.connectionKey,
                                keyHash,
                            )
                        if (existing != null) {
                            val current = replay(session, existing, ownerOperation)
                            if (existing["request_hash"] != requestHash) fail("idempotency_conflict")
                            current
                        } else {
                            val result = execute(session, operation, resourceId, body)
                            sql.update(
                                "INSERT INTO client_commands(actor_user_id,connection_key," +
                                    "key_hash,request_hash,result_type,result_id,client_id,created_at) " +
                                    "VALUES (?,?,?,?,?,?,?,?)",
                                userId,
                                actor.connectionKey,
                                keyHash,
                                requestHash,
                                result.type,
                                result.id,
                                result.clientId,
                                session.now,
                            )
                            result.response
                        }
                    }
                } catch (failure: SQLException) {
                    if (failure.sqlState !in setOf("40001", "40P01") || attempt >= 2) throw failure
                    attempt++
                    delay(10L * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable transaction retry")
        }

    /** Read-only business resolution: absence never executes or cancels a delayed command. */
    private fun resolveCalendarCommand(
        session: OnboardingSession,
        clubId: Long,
        body: JsonObject,
    ): JsonObject {
        body.only(listOf("request", "key"))
        val request = body["request"] as? JsonObject ?: fail("validation_error")
        val key = body.string("key")?.takeIf { Regex("[A-Za-z0-9._:-]{8,128}").matches(it) } ?: fail("validation_error")
        val clientId = session.requireClubGrant(clubId, WRITE_CALENDAR)
        val userId = session.userId ?: fail("not_found")
        // The client lock establishes fresh authority first. Unlike mutations,
        // resolution never waits for the actor lock, so this order cannot cycle.
        if (!session.sql.tryLockActor(userId)) {
            return buildJsonObject { put("status", "indeterminate") }
        }
        val existing =
            session.sql.row(
                "SELECT * FROM client_commands WHERE actor_user_id=? AND connection_key=? AND key_hash=?",
                userId,
                session.actor.connectionKey,
                digest(key),
            )
        if (existing != null) {
            val sameScope =
                existing["result_type"] == "club_calendar" &&
                    existing.long("result_id") == clubId &&
                    existing.long("client_id") == clientId
            val expectedHash = commandRequestHash(OnboardingMutation.CLUB_CALENDAR_UPDATE, clubId, request)
            if (!sameScope || existing["request_hash"] != expectedHash) fail("idempotency_conflict")
        }
        return buildJsonObject {
            put("status", if (existing == null) "not_committed_at_snapshot" else "completed")
            // client_commands stores only the resource pointer, not historical JSON.
            put("calendar", ClubCalendarOperations(session).get(clubId, write = true))
        }
    }

    private fun commandRequestHash(
        operation: OnboardingMutation,
        resourceId: Long?,
        body: JsonObject,
    ): String = digest(operation.name + ":" + resourceId + ":" + canonical(body).toString())

    private fun execute(
        session: OnboardingSession,
        operation: OnboardingMutation,
        resourceId: Long?,
        body: JsonObject,
    ): MutationResult {
        val invitations = InvitationOperations(session)
        val applications = ApplicationOperations(session)
        return when (operation) {
            OnboardingMutation.INVITATION_ISSUE -> invitations.issue(body)
            OnboardingMutation.INVITATION_CLAIM -> invitations.claim(required(resourceId), body)
            OnboardingMutation.INVITATION_CONFIRM, OnboardingMutation.INVITATION_REVOKE,
            OnboardingMutation.INVITATION_REISSUE,
            -> invitations.action(required(resourceId), operation, body)
            OnboardingMutation.MEMBER_REVOKE, OnboardingMutation.MEMBER_RESTORE ->
                ClientAccessOperations(session).change(required(resourceId), operation, body)
            OnboardingMutation.CLIENT_LIMIT_UPDATE -> ClientLimitOperations(session).update(required(resourceId), body)
            OnboardingMutation.CLIENT_ARCHIVE, OnboardingMutation.CLIENT_RESTORE ->
                ClientLifecycleOperations(session).change(required(resourceId), operation, body)
            OnboardingMutation.CLUB_CREATE -> DirectClubOperations(session).create(required(resourceId), body)
            OnboardingMutation.CLUB_BOOKING_RULES_UPDATE ->
                ClubBookingRulesOperations(session).update(required(resourceId), body)
            OnboardingMutation.CLUB_CALENDAR_UPDATE ->
                ClubCalendarOperations(
                    session,
                ).update(required(resourceId), body)
            OnboardingMutation.CLUB_NIGHT_EVENT_LINK -> ClubNightEventLinks(session).link(required(resourceId), body)
            OnboardingMutation.CLUB_MODULE_SETTINGS_UPDATE ->
                ClubModuleSettingsOperations(session).update(required(resourceId), body)
            OnboardingMutation.CLUB_FINANCE_UPDATE -> ClubFinanceOperations(session).update(required(resourceId), body)
            OnboardingMutation.CLUB_ZONE_CREATE, OnboardingMutation.CLUB_ZONE_UPDATE,
            OnboardingMutation.CLUB_ZONE_REMOVE,
            OnboardingMutation.CLUB_HALL_CREATE, OnboardingMutation.CLUB_HALL_UPDATE,
            OnboardingMutation.CLUB_TABLE_CREATE, OnboardingMutation.CLUB_TABLE_BATCH_CREATE,
            OnboardingMutation.CLUB_TABLE_UPDATE,
            OnboardingMutation.CLUB_PLAN_UPLOAD, OnboardingMutation.CLUB_PLAN_REMOVE,
            -> ClubLayoutOperations(session).mutate(required(resourceId), operation, body)
            OnboardingMutation.APPLICATION_CREATE -> applications.create(required(resourceId), body)
            OnboardingMutation.APPLICATION_UPDATE -> applications.update(required(resourceId), body)
            OnboardingMutation.APPLICATION_CREATE_CLUB ->
                ClubOnboardingOperations(
                    session,
                ).create(required(resourceId), body)
            OnboardingMutation.CLUB_PREPARATION_UPDATE ->
                ClubPreparationOperations(
                    session,
                ).update(required(resourceId), body)
            OnboardingMutation.CLUB_TEAM_UPDATE -> ClubTeamOperations(session).update(required(resourceId), body)
            OnboardingMutation.CLUB_PROFILE_UPDATE ->
                ClubOnboardingOperations(
                    session,
                ).update(required(resourceId), body)
            else -> applications.transition(required(resourceId), operation, body)
        }
    }

    private fun replay(
        session: OnboardingSession,
        command: DbRow,
        platform: Boolean,
    ): JsonObject {
        // Re-read under current authority; a lost response cannot restore access or leak a stored secret.
        session.client(command.long("client_id"), lock = true)
        return when (command["result_type"]) {
            "invitation" -> InvitationOperations(session).get(command.long("result_id"), platform)
            "application" -> ApplicationOperations(session).get(command.long("result_id"), platform)
            "client_limit", "client_lifecycle" -> ClientAccessOperations(session).get(command.long("client_id"))
            "member" -> ClientAccessOperations(session).memberView(command.long("client_id"), command.long("result_id"))
            "club_create" -> ClubOnboardingOperations(session).replayCreate(command.long("result_id"))
            "club_profile" -> ClubOnboardingOperations(session).get(command.long("result_id"), write = true)
            "club_booking_rules" -> ClubBookingRulesOperations(session).get(command.long("result_id"), write = true)
            "club_calendar" -> ClubCalendarOperations(session).get(command.long("result_id"), write = true)
            "club_night_event_link" -> ClubNightEventLinks(session).receipt(command.long("result_id"))
            "club_module_settings" -> ClubModuleSettingsOperations(session).get(command.long("result_id"), write = true)
            "staff_invitation" -> ClubTeamOperations(session).receipt(command.long("result_id"))
            "club_team" -> ClubTeamOperations(session).get(command.long("result_id"))
            "club_preparation", "prep_invite", "prep_submit" ->
                ClubPreparationOperations(session).receipt(
                    command.long("result_id"),
                    command["result_type"] == "prep_submit",
                )
            "club_finance" -> ClubFinanceOperations(session).get(command.long("result_id"), write = true)
            "club_layout" -> ClubLayoutOperations(session).get(command.long("result_id"), write = true)
            else -> error("Invalid stored command result")
        }
    }

    private fun <T> transaction(
        readOnly: Boolean,
        beforeCommit: () -> Unit = {},
        block: (OnboardingSql) -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.isReadOnly = readOnly
            connection.transactionIsolation =
                if (readOnly) Connection.TRANSACTION_REPEATABLE_READ else Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                val result = block(OnboardingSql(connection))
                beforeCommit()
                connection.commit()
                result
            } catch (cancelled: CancellationException) {
                connection.rollback()
                throw cancelled
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }

    private fun validateActor(actor: OnboardingActor) {
        if (actor.telegramUserId <= 0 || actor.connectionKey != "platform") fail("forbidden")
    }

    private fun required(id: Long?): Long = id?.takeIf { it > 0 } ?: fail("validation_error")

    private companion object {
        val OWNER_OPERATIONS =
            setOf(
                OnboardingMutation.CLIENT_LIMIT_UPDATE,
                OnboardingMutation.CLIENT_ARCHIVE,
                OnboardingMutation.CLIENT_RESTORE,
                OnboardingMutation.INVITATION_ISSUE,
                OnboardingMutation.INVITATION_CONFIRM,
                OnboardingMutation.INVITATION_REVOKE,
                OnboardingMutation.INVITATION_REISSUE,
                OnboardingMutation.MEMBER_REVOKE,
                OnboardingMutation.MEMBER_RESTORE,
                OnboardingMutation.APPLICATION_REVIEW,
                OnboardingMutation.APPLICATION_APPROVE,
                OnboardingMutation.APPLICATION_REJECT,
                OnboardingMutation.PLATFORM_APPLICATION_CANCEL,
            )
    }
}
