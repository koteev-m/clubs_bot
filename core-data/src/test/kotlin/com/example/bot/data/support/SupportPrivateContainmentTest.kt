package com.example.bot.data.support

import com.example.bot.data.client.onboarding.OnboardingFixture
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.data.client.onboarding.id
import com.example.bot.data.club.ClubOpsChatConfigRepositoryImpl
import com.example.bot.opschat.ClubOpsChatConfigUpsert
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketTopic
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.sql.DriverManager
import java.util.UUID

internal abstract class SupportPrivateContainmentContract {
    private lateinit var fixture: OnboardingFixture
    private lateinit var repository: SupportRepository
    private lateinit var service: SupportServiceImpl
    private var privateClub = 0L
    private val guest = 880063501L
    private val staff = 880063502L
    private val legacyClub = 880063503L
    private val privateTicket = 880063504L
    private val legacyTicket = 880063505L
    private val privateReply = 880063506L
    private val delivery = 880063507L

    protected abstract fun newFixture(): OnboardingFixture

    @BeforeEach
    fun setup() =
        runBlocking {
            fixture = newFixture()
            val accepted = fixture.accept()
            var application = fixture.draft(accepted.id("clientId"))
            application = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, application)
            application = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, application)
            application =
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_APPROVE,
                    application,
                    OnboardingFixture.approval(),
                )
            privateClub =
                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CREATE_CLUB, application).id("id")
            fixture.execute("INSERT INTO users(id,telegram_user_id) VALUES ($guest,880063501),($staff,880063502)")
            fixture.execute(
                "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($legacyClub,'Synthetic legacy','UTC',true)",
            )
            fixture.execute(
                "INSERT INTO tickets(id,club_id,user_id,topic,status) VALUES " +
                    "($privateTicket,$privateClub,$guest,'booking','answered')," +
                    "($legacyTicket,$legacyClub,$guest,'booking','new')",
            )
            fixture.execute(
                "INSERT INTO ticket_messages(id,ticket_id,sender_type,text) VALUES " +
                    "($privateReply,$privateTicket,'agent','PRIVATE-SUPPORT-CONTENT')," +
                    "(880063508,$legacyTicket,'guest','Synthetic legacy question')",
            )
            fixture.execute(
                "INSERT INTO user_roles(id,user_id,role_code,scope_type,scope_club_id) VALUES " +
                    "(880063509,$staff,'MANAGER','CLUB',$privateClub)," +
                    "(880063510,$staff,'MANAGER','CLUB',$legacyClub)",
            )
            fixture.execute(
                "INSERT INTO user_role_permissions(user_role_id,permission_code) VALUES " +
                    "(880063509,'support.reply'),(880063509,'support.status.manage')," +
                    "(880063510,'support.reply'),(880063510,'support.status.manage')",
            )
            fixture.execute(
                "INSERT INTO support_reply_deliveries" +
                    "(id,reply_message_id,ticket_id,recipient_user_id," +
                    "acting_staff_user_id,acting_role,status) VALUES " +
                    "($delivery,$privateReply,$privateTicket,$guest,$staff,'MANAGER','pending')",
            )
            repository = SupportRepository(Database.connect(fixture.source))
            service = SupportServiceImpl(repository)
        }

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `private descendant tickets and messages are absent from guest and staff reads`() =
        runBlocking {
            assertNull(repository.findTicket(privateTicket))
            assertNull(repository.findTicketThreadByUser(privateTicket, guest))
            assertNull(repository.findStaffTicketThread(privateTicket, setOf(privateClub)))
            assertNull(repository.findTicketClubIdInClubs(privateTicket, setOf(privateClub)))
            assertTrue(repository.listTicketsByClub(privateClub, null).isEmpty())
            assertEquals(listOf(legacyTicket), repository.listTicketsByUser(guest).map { it.id })
            assertNotNull(repository.findTicketThreadByUser(legacyTicket, guest))
            assertFalse(repository.listTicketsByUser(guest).toString().contains("PRIVATE-SUPPORT-CONTENT"))
        }

    @Test
    fun `private creation rating guest reply and staff mutation make no writes`() =
        runBlocking {
            val before = snapshot()
            assertEquals(
                SupportServiceResult.Failure(SupportServiceError.TicketNotFound),
                service.createTicket(privateClub, guest, null, null, TicketTopic.BOOKING, "Synthetic denied", null),
            )
            assertEquals(
                AddGuestMessageResult.Failure(AddGuestMessageFailure.NotFound),
                repository.addGuestMessage(privateTicket, guest, "Synthetic denied", null),
            )
            assertEquals(
                SetResolutionRatingResult.Failure(SetResolutionRatingFailure.NotFound),
                repository.setResolutionRating(privateTicket, guest, 1),
            )
            assertEquals(
                StaffMutationResult.Failure(StaffMutationFailure.NotFound),
                repository.assign(privateTicket, staff),
            )
            assertEquals(
                StaffMutationResult.Failure(StaffMutationFailure.NotFound),
                repository.reply(privateTicket, staff, "Synthetic denied", null),
            )
            assertEquals(before, snapshot())
            assertTrue(
                repository.addGuestMessage(
                    legacyTicket,
                    guest,
                    "Synthetic legacy reply",
                    null,
                ) is AddGuestMessageResult.Success,
            )
            assertTrue(repository.assign(legacyTicket, staff) is StaffMutationResult.Success)
        }

    @Test
    fun `private pending reply is never claimed and content is not delivered`() =
        runBlocking {
            val before = snapshot()
            assertNull(repository.findReplyDelivery(delivery))
            assertEquals(SupportReplyDeliveryClaimResult.NotClaimed, repository.claimReplyDelivery(delivery))
            assertEquals(before, snapshot())
            val legacyReply = repository.reply(legacyTicket, staff, "Synthetic legacy staff reply", null)
            assertTrue(legacyReply is StaffMutationResult.Success)
            val legacyDelivery = (legacyReply as StaffMutationResult.Success).value.deliveryId
            assertTrue(repository.claimReplyDelivery(legacyDelivery) is SupportReplyDeliveryClaimResult.Claimed)
        }

    @Test
    fun `operational queue cannot resolve private destination even with synthetic config`() =
        runBlocking {
            fixture.execute(
                "INSERT INTO club_ops_chat_config(club_id,chat_id) VALUES " +
                    "($privateClub,-880063501),($legacyClub,-880063502)",
            )
            val config = ClubOpsChatConfigRepositoryImpl(Database.connect(fixture.source))
            assertNull(config.getByClubId(privateClub))
            assertNotNull(config.getByClubId(legacyClub))
            val before = fixture.rows("SELECT * FROM club_ops_chat_config ORDER BY club_id")
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    config.upsert(ClubOpsChatConfigUpsert(privateClub, -880063503, null, null, null, null, null))
                }
            }
            assertEquals(before, fixture.rows("SELECT * FROM club_ops_chat_config ORDER BY club_id"))
        }

    private fun snapshot() =
        listOf("tickets", "ticket_messages", "support_reply_deliveries", "audit_log").associateWith {
            fixture.rows("SELECT * FROM $it ORDER BY id")
        }
}

internal class SupportPrivateContainmentTest : SupportPrivateContainmentContract() {
    override fun newFixture(): OnboardingFixture = OnboardingFixture.h2()
}

/** Native disposable PostgreSQL 16, with no container discovery or live database fallback. */
@Tag("it")
internal class SupportPrivateContainmentPostgresIT : SupportPrivateContainmentContract() {
    override fun newFixture(): OnboardingFixture {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        val ci = supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
        val url = if (ci) "jdbc:postgresql://127.0.0.1:5432/botdb" else checkNotNull(supplied)
        val parsed = URI(url.removePrefix("jdbc:"))
        require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1")
        require(parsed.path == if (ci) "/botdb" else "/clb_client_test")
        require(parsed.query == null && parsed.userInfo == null)
        val user = if (ci) "botuser" else "clb_client_test"
        val password = if (ci) "botpass" else ""
        val schema = "support_private_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            assertEquals(16, connection.metaData.databaseMajorVersion)
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
                }
            }
        }
        return OnboardingFixture(
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = user
                    this.password = password
                    connectionInitSql = "SET search_path TO $schema, public"
                    maximumPoolSize = 3
                },
            ),
            "postgresql",
        )
    }
}
