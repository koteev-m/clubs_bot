package com.example.bot.security

import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.Role
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object UserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("it")
class RbacIntegrationTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }
    }

    private val postgres = PostgreSQLContainer("postgres:16-alpine")

    private lateinit var database: Database

    @BeforeAll
    fun setUp() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
            .load()
            .migrate()
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )
    }

    @AfterAll
    fun tearDown() {
        postgres.stop()
    }

    @BeforeEach
    fun cleanTables() {
        transaction(database) {
            AuditLogTable.deleteAll()
            UserRolesTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @Test
    fun `returns 401 when principal missing`() =
        testApplication {
            application { testModule() }
            val response = client.get("/clubs/1/manage")
            response.status shouldBe HttpStatusCode.Unauthorized
            verifyAudit(result = "access_denied", clubId = null, userId = null)
        }

    @Test
    fun `returns 403 when role missing`() =
        testApplication {
            val userId = registerUser(telegramId = 10L, roles = emptySet(), clubs = emptySet())
            application { testModule() }
            val response = client.get("/clubs/1/manage") { header("X-Telegram-Id", "10") }
            response.status shouldBe HttpStatusCode.Forbidden
            verifyAudit(result = "access_denied", clubId = null, userId = userId)
        }

    @Test
    fun `returns 403 when club scope violated`() =
        testApplication {
            val userId = registerUser(telegramId = 20L, roles = setOf(Role.MANAGER), clubs = setOf(2L))
            application { testModule() }
            val response = client.get("/clubs/5/manage") { header("X-Telegram-Id", "20") }
            response.status shouldBe HttpStatusCode.Forbidden
            verifyAudit(result = "access_denied", clubId = 5L, userId = userId)
        }

    @Test
    fun `returns 200 when access granted`() =
        testApplication {
            val userId = registerUser(telegramId = 30L, roles = setOf(Role.MANAGER), clubs = setOf(7L))
            application { testModule() }
            val response = client.get("/clubs/7/manage") { header("X-Telegram-Id", "30") }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "ok"
            verifyAudit(result = "access_granted", clubId = 7L, userId = userId)
        }

    private fun Application.testModule() {
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        DataSourceHolder.dataSource = dataSource
        configureSecurity()
        routing {
            authorize(Role.MANAGER) {
                clubScoped(ClubScope.Own) {
                    get("/clubs/{clubId}/manage") { call.respondText("ok") }
                }
            }
        }
    }

    private fun registerUser(
        telegramId: Long,
        roles: Set<Role>,
        clubs: Set<Long>,
    ): Long {
        return transaction(database) {
            val userId =
                UsersTable.insert {
                    it[telegramUserId] = telegramId
                    it[username] = "user$telegramId"
                } get UsersTable.id
            if (roles.isNotEmpty()) {
                if (clubs.isEmpty()) {
                    roles.forEach { role ->
                        UserRolesTable.insert {
                            it[this.userId] = userId
                            it[roleCode] = role.name
                            it[scopeType] = "GLOBAL"
                            it[scopeClubId] = null
                        }
                    }
                } else {
                    roles.forEach { role ->
                        clubs.forEach { clubId ->
                            UserRolesTable.insert {
                                it[this.userId] = userId
                                it[roleCode] = role.name
                                it[scopeType] = "CLUB"
                                it[scopeClubId] = clubId
                            }
                        }
                    }
                }
            }
            userId
        }
    }

    private fun verifyAudit(
        result: String,
        clubId: Long?,
        userId: Long?,
    ) {
        transaction(database) {
            val record = AuditLogTable.selectAll().single()
            record[AuditLogTable.result] shouldBe result
            record[AuditLogTable.clubId] shouldBe clubId
            record[AuditLogTable.userId] shouldBe userId
        }
    }
}
