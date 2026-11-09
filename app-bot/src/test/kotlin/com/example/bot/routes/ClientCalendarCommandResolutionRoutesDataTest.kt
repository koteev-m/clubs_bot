package com.example.bot.routes

import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Calendar resolution reads the existing command ledger; it never re-executes a missing command. */
open class ClientCalendarCommandResolutionRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `a committed no op resolves across restart midnight and reversible timezone changes without writing`() =
        database { source ->
            lateinit var path: String
            lateinit var original: JsonObject
            lateinit var clubId: String
            withApp(source, CLOCK) {
                clubId = club().string("id")
                path = "$C/clubs/$clubId/calendar"
                val initial = ok(path, RECIPIENT)
                original = body(initial, reconcile = true)
                val completed = patch(path, original, NOOP_KEY)
                assertEquals(initial, completed)
                assertEquals("1", completed.string("version"))
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
            }
            withApp(source, NEXT_DAY) {
                for (timezone in listOf("Europe/Berlin", "Europe/London")) {
                    timezone(clubId, timezone)
                    val current = ok(path, RECIPIENT)
                    val before = source.fingerprint()
                    val resolution = resolve(path, original, NOOP_KEY)
                    assertEquals("completed", resolution.string("status"))
                    assertEquals(current, resolution.getValue("calendar").jsonObject)
                    assertEquals(timezone, resolution.getValue("calendar").jsonObject.string("timezone"))
                    assertEquals(
                        "2027-08-02",
                        resolution
                            .getValue("calendar")
                            .jsonObject["reconciliation"]!!
                            .jsonObject
                            .string("businessDate"),
                    )
                    assertEquals(before, source.fingerprint())
                }
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
            }
        }

    @Test
    fun `completed returns the current authorized calendar rather than a fabricated historical response`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${club().string("id")}/calendar"
                val original = body(ok(path, RECIPIENT), exceptions = JsonArray(listOf(dateRule("2027-10-30"))))
                val first = patch(path, original, SAVE_KEY)
                val current = patch(path, body(first, exceptions = JsonArray(listOf(dateRule("2027-11-06")))))
                val before = source.fingerprint()
                val resolution = resolve(path, original, SAVE_KEY)
                assertEquals("completed", resolution.string("status"))
                assertEquals("3", resolution.getValue("calendar").jsonObject.string("version"))
                assertEquals(current, resolution.getValue("calendar").jsonObject)
                assertEquals(before, source.fingerprint())
            }
        }

    @Test
    fun `absence is only a snapshot and delayed original plus same key retry executes once`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${club().string("id")}/calendar"
                val initial = ok(path, RECIPIENT)
                val original = body(initial, exceptions = JsonArray(listOf(dateRule("2027-10-30"))))
                val before = source.fingerprint()
                val absent = resolve(path, original, SAVE_KEY)
                assertEquals("not_committed_at_snapshot", absent.string("status"))
                assertEquals(initial, absent.getValue("calendar").jsonObject)
                assertEquals(before, source.fingerprint())
                val writes =
                    coroutineScope {
                        List(2) { async { patch(path, original, SAVE_KEY) } }.map { it.await() }
                    }
                assertEquals(writes[0], writes[1])
                val after = source.fingerprint()
                assertEquals("completed", resolve(path, original, SAVE_KEY).string("status"))
                assertEquals(after, source.fingerprint())
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
            }
        }

    @Test
    fun `resolution binds exact original body actor club client operation and fresh write grants`() =
        database { source ->
            withApp(source, CLOCK) {
                val own = club()
                val other = club()
                val foreign = club(FOREIGN)
                val path = "$C/clubs/${own.string("id")}/calendar"
                val original = body(ok(path, RECIPIENT), reconcile = true)
                patch(path, original, NOOP_KEY)
                val before = source.fingerprint()
                requestAs("$path/commands/resolve", FOREIGN, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    "$C/clubs/${foreign.string("id")}/calendar/commands/resolve",
                    RECIPIENT,
                    original,
                    key = NOOP_KEY,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    "$C/clubs/${other.string("id")}/calendar/commands/resolve",
                    RECIPIENT,
                    original,
                    key = NOOP_KEY,
                ).assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                val changedBody = JsonObject(original + ("timezone" to JsonPrimitive("Europe/Berlin")))
                requestAs("$path/commands/resolve", RECIPIENT, changedBody, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                assertEquals("not_committed_at_snapshot", resolve(path, original, "other-unknown-key").string("status"))
                assertEquals(before, source.fingerprint())
                val profilePath = "$C/clubs/${own.string("id")}/profile"
                val profile = ok(profilePath, RECIPIENT)
                ok(
                    profilePath,
                    RECIPIENT,
                    obj("expectedVersion" to profile.string("version"), "name" to "Updated"),
                    HttpMethod.Patch,
                    PROFILE_KEY,
                )
                requestAs("$path/commands/resolve", RECIPIENT, original, key = PROFILE_KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                assertEquals("completed", resolve(path, original, NOOP_KEY).string("status"))
                source.disableGrant(own.string("id"), "client.club.calendar.write")
                val readOnly = source.fingerprint()
                requestAs("$path/commands/resolve", RECIPIENT, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals(readOnly, source.fingerprint())
                source.disableGrant(own.string("id"), "client.club.read")
                requestAs("$path/commands/resolve", RECIPIENT, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `resolution shares signature header id validation and the calendar body bound`() =
        database { source ->
            withApp(source, CLOCK) {
                val id = club().string("id")
                val path = "$C/clubs/$id/calendar"
                val original = body(ok(path, RECIPIENT), reconcile = true)
                client.post("$path/commands/resolve").assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                requestAs("$path/commands/resolve", RECIPIENT, original, key = "short")
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                requestAs("$C/clubs/0$id/calendar/commands/resolve", RECIPIENT, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                requestAs("$path/commands/resolve?initData=synthetic", RECIPIENT, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                val expired =
                    WebAppInitDataTestHelper.createInitData(
                        TEST_BOT_TOKEN,
                        mapOf(
                            "user" to WebAppInitDataTestHelper.encodeUser(RECIPIENT),
                            "auth_date" to (Instant.now().epochSecond - 259200).toString(),
                            "start_param" to "ci_" + "s".repeat(43),
                        ),
                    )
                client
                    .post("$path/commands/resolve") {
                        header("X-Telegram-Init-Data", expired)
                        header("Idempotency-Key", NOOP_KEY)
                        header("Content-Type", "application/json")
                        setBody(original.toString())
                    }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                client
                    .post("$path/commands/resolve") {
                        header("X-Telegram-Init-Data", signed(RECIPIENT))
                        header("Content-Type", "application/json")
                        setBody(original.toString())
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
                client
                    .post("$path/commands/resolve") {
                        header("X-Telegram-Init-Data", signed(RECIPIENT))
                        header("Idempotency-Key", NOOP_KEY)
                        header("Idempotency-Key", SAVE_KEY)
                        header("Content-Type", "application/json")
                        setBody(original.toString())
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
                val oversized = JsonObject(original + ("padding" to JsonPrimitive("x".repeat(65536))))
                requestAs("$path/commands/resolve", RECIPIENT, oversized, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                val many =
                    body(
                        ok(path, RECIPIENT),
                        exceptions =
                            JsonArray(
                                (0L..365).map { dateRule(LocalDate.parse("2028-01-01").plusDays(it).toString()) },
                            ),
                    )
                assertTrue(many.toString().length > 16384)
                val before = source.fingerprint()
                assertEquals("not_committed_at_snapshot", resolve(path, many, "large-calendar-body").string("status"))
                assertEquals(before, source.fingerprint())
            }
        }

    @Test
    fun `an unexpected receipt read failure rolls back locks and leaves commands audit and authority untouched`() =
        database { source ->
            val inject = AtomicBoolean(false)
            val wrapped =
                ResolutionProbeSource(source, beforeReceiptRead = { connection ->
                    if (inject.compareAndSet(true, false)) {
                        connection.createStatement().use { it.executeQuery("SELECT 1 / 0").close() }
                    }
                })
            withApp(wrapped, CLOCK) {
                val path = "$C/clubs/${club().string("id")}/calendar"
                val original = body(ok(path, RECIPIENT), reconcile = true)
                patch(path, original, NOOP_KEY)
                val before = source.fingerprint()
                inject.set(true)
                requestAs("$path/commands/resolve", RECIPIENT, original, key = NOOP_KEY)
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(before, source.fingerprint())
                assertEquals("completed", withTimeout(5000) { resolve(path, original, NOOP_KEY) }.string("status"))
                assertEquals(before, source.fingerprint())
            }
        }

    @Test
    fun `actor contention is indeterminate and savepoint rollback retains the authorized client lock`() =
        database { source ->
            val inspect = AtomicBoolean(false)
            val retained = AtomicBoolean(false)
            var clientId = ""
            val wrapped =
                ResolutionProbeSource(source, beforeCommit = { state ->
                    if (inspect.get() && state.actorProbe) {
                        source.connection.use { check ->
                            check.autoCommit = false
                            try {
                                check.actorOrClientLock("clients", clientId)
                                error("Resolver lost its client authority lock after rollback to savepoint")
                            } catch (failure: SQLException) {
                                assertTrue(check.lockBusy(failure))
                                retained.set(true)
                            } finally {
                                check.rollback()
                            }
                        }
                    }
                })
            withApp(wrapped, CLOCK) {
                val club = club()
                clientId = club.string("clientId")
                val path = "$C/clubs/${club.string("id")}/calendar"
                val original = body(ok(path, RECIPIENT), reconcile = true)
                val userId = source.scalar("SELECT id FROM users WHERE telegram_user_id=$RECIPIENT")
                source.connection.use { locked ->
                    locked.autoCommit = false
                    locked.actorOrClientLock("users", userId)
                    try {
                        val before = source.fingerprint()
                        inspect.set(true)
                        val response = withTimeout(5000) { resolve(path, original, NOOP_KEY) }
                        assertEquals(obj("status" to "indeterminate"), response)
                        assertTrue(retained.get())
                        assertEquals(before, source.fingerprint())
                    } finally {
                        inspect.set(false)
                        locked.rollback()
                    }
                }
                assertEquals("not_committed_at_snapshot", resolve(path, original, NOOP_KEY).string("status"))
            }
        }

    @Test
    fun `a resolver waits for an original transaction then observes its committed receipt without duplicate effects`() =
        database { source ->
            val gate = AtomicBoolean(false)
            val gated = AtomicBoolean(false)
            val held = CountDownLatch(1)
            val attempting = CountDownLatch(1)
            val release = CountDownLatch(1)
            val wrapped =
                ResolutionProbeSource(
                    source,
                    beforeCommit = { state ->
                        if (gate.get() && state.commandInsert && gated.compareAndSet(false, true)) {
                            held.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    },
                    beforeClientLock = {
                        if (gate.get() && held.count == 0L) attempting.countDown()
                    },
                )
            withApp(wrapped, CLOCK) {
                val path = "$C/clubs/${club().string("id")}/calendar"
                val original = body(ok(path, RECIPIENT), exceptions = JsonArray(listOf(dateRule("2027-10-30"))))
                gate.set(true)
                try {
                    coroutineScope {
                        val mutation = async { patch(path, original, SAVE_KEY) }
                        await(held)
                        val resolution = async { resolve(path, original, SAVE_KEY) }
                        await(attempting)
                        assertFalse(resolution.isCompleted)
                        release.countDown()
                        val saved = mutation.await()
                        val resolved = resolution.await()
                        assertEquals("completed", resolved.string("status"))
                        assertEquals(saved, resolved.getValue("calendar").jsonObject)
                    }
                } finally {
                    release.countDown()
                    gate.set(false)
                }
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
            }
        }

    private suspend fun await(
        latch: CountDownLatch,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(dispatcher) { assertTrue(latch.await(10, TimeUnit.SECONDS)) }

    private suspend fun ApplicationTestBuilder.club(user: Long = RECIPIENT): JsonObject {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Calendar $user", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            user,
            obj(
                "token" to invite.string("token"),
                "expectedVersion" to invite.string("version"),
            ),
        )
        val club =
            ok(
                "$C/clients/${invite.string("clientId")}/clubs",
                user,
                obj(
                    "venueName" to "Resolver club",
                    "countryCode" to "RU",
                    "cityId" to "524901",
                    "address" to "Street",
                ),
            )
        timezone(club.string("id"), "Europe/London", user)
        return club
    }

    private suspend fun ApplicationTestBuilder.timezone(
        clubId: String,
        timezone: String,
        user: Long = RECIPIENT,
    ) {
        val path = "$C/clubs/$clubId/profile"
        val profile = ok(path, user)
        ok(path, user, obj("expectedVersion" to profile.string("version"), "timezone" to timezone), HttpMethod.Patch)
    }

    private suspend fun ApplicationTestBuilder.patch(
        path: String,
        request: JsonObject,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ) = ok(path, RECIPIENT, request, HttpMethod.Patch, key)

    private suspend fun ApplicationTestBuilder.resolve(
        path: String,
        request: JsonObject,
        key: String,
    ) = ok("$path/commands/resolve", RECIPIENT, request, HttpMethod.Post, key)

    private fun body(
        view: JsonObject,
        exceptions: JsonArray = JsonArray(emptyList()),
        reconcile: Boolean = false,
    ) = buildJsonObject {
        put("expectedVersion", view.getValue("version"))
        put("timezone", view.getValue("timezone"))
        put("reconciliationDate", view.getValue("reconciliation").jsonObject.getValue("businessDate"))
        put("weekly", JsonArray(emptyList()))
        put("holidays", JsonArray(emptyList()))
        put("exceptions", exceptions)
        if (reconcile) put("reconcile", true)
    }

    private fun dateRule(date: String) =
        buildJsonObject {
            put("date", date)
            put("mode", "open")
            put("open", "00:30")
            put("close", "08:00")
            put("nextDay", false)
        }

    private fun DataSource.disableGrant(
        clubId: String,
        permission: String,
    ) = connection.use { connection ->
        connection.createStatement().use {
            it.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$clubId AND permission_code='$permission'",
            )
        }
    }

    private fun Connection.actorOrClientLock(
        table: String,
        id: String,
    ) {
        val mode = if (metaData.databaseProductName == "PostgreSQL" && table == "users") "NO KEY UPDATE" else "UPDATE"
        createStatement().use {
            it.executeQuery("SELECT id FROM $table WHERE id=$id FOR $mode NOWAIT").use { rows ->
                check(rows.next())
            }
        }
    }

    private fun Connection.lockBusy(error: SQLException): Boolean =
        if (metaData.databaseProductName == "PostgreSQL") error.sqlState == "55P03" else error.errorCode == 50200

    private fun DataSource.fingerprint(): String {
        val tables =
            listOf(
                "client_commands",
                "audit_log",
                "clubs",
                "club_hours",
                "club_holidays",
                "club_exceptions",
                "club_operational_nights",
                "client_club_grants",
                "client_memberships",
                "client_membership_grants",
                "clients",
                "users",
            )
        val digest = MessageDigest.getInstance("SHA-256")
        connection.use { connection ->
            for (table in tables) {
                digest.update((table + connection.tableRows(table)).toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Connection.tableRows(table: String): String =
        createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $table").use { rows ->
                val values = mutableListOf<String>()
                while (rows.next()) {
                    values += (1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) ?: "NULL" }
                }
                values.sorted().joinToString("\n")
            }
        }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2027-08-01T12:00:00Z"), ZoneOffset.UTC)
        val NEXT_DAY: Clock = Clock.fixed(Instant.parse("2027-08-02T12:00:00Z"), ZoneOffset.UTC)
        const val NOOP_KEY = "resolver-noop-command"
        const val SAVE_KEY = "resolver-saved-command"
        const val PROFILE_KEY = "resolver-profile-command"
    }
}

private class ResolutionProbeState {
    var actorProbe = false
    var commandInsert = false
}

/** Test-only JDBC observation/gating leaves all lock and transaction operations on the real engine. */
private class ResolutionProbeSource(
    private val source: DataSource,
    private val beforeCommit: (ResolutionProbeState) -> Unit = {},
    private val beforeClientLock: () -> Unit = {},
    private val beforeReceiptRead: (Connection) -> Unit = {},
) : DataSource by source {
    override fun getConnection(): Connection = wrap(source.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = wrap(source.getConnection(username, password))

    private fun wrap(connection: Connection): Connection {
        val state = ResolutionProbeState()
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement") {
                val sql = (args?.firstOrNull() as? String).orEmpty()
                if ("FROM users" in sql && "NOWAIT" in sql) state.actorProbe = true
                if ("INSERT INTO client_commands" in sql) state.commandInsert = true
                if ("SELECT * FROM client_commands" in sql) beforeReceiptRead(connection)
                if ("FROM clients" in sql && "FOR UPDATE" in sql) beforeClientLock()
            }
            if (method.name == "commit") beforeCommit(state)
            try {
                method.invoke(connection, *args.orEmpty())
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
    }
}
