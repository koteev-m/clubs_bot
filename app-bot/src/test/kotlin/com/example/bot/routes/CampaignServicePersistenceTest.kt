package com.example.bot.routes

import com.example.bot.data.notifications.NotifyCampaignAudit
import com.example.bot.data.notifications.NotifyCampaigns
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Table

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()
    override val primaryKey = PrimaryKey(id)
}

class CampaignServicePersistenceTest : StringSpec({
    "campaign state is restored after service restart with actor attribution" {
        val ds =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:campaign_restart;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        val database = Database.connect(ds)

        val (creatorId, updaterId) =
            transaction(database) {
                val firstUserId =
                    UsersTable.insert {
                        it[telegramUserId] = 1001L
                        it[username] = "creator"
                    }[UsersTable.id]
                val secondUserId =
                    UsersTable.insert {
                        it[telegramUserId] = 1002L
                        it[username] = "updater"
                    }[UsersTable.id]
                firstUserId to secondUserId
            }

        val firstInstance = DbCampaignService(database)
        val created = firstInstance.create(CampaignCreateRequest(title = "t", text = "hello"), actorId = creatorId)
        firstInstance.setStatus(created.id, CampaignStatus.PAUSED, actorId = updaterId)

        val secondInstance = DbCampaignService(database)
        val restored = secondInstance.find(created.id)

        restored?.status shouldBe CampaignStatus.PAUSED
        restored?.title shouldBe "t"
        restored?.text shouldBe "hello"
        transaction(database) {
            val campaignRow =
                NotifyCampaigns
                    .selectAll()
                    .where { NotifyCampaigns.id eq created.id }
                    .single()
            campaignRow[NotifyCampaigns.createdBy] shouldBe creatorId

            val auditActors =
                NotifyCampaignAudit
                    .selectAll()
                    .where { NotifyCampaignAudit.campaignId eq created.id }
                    .orderBy(NotifyCampaignAudit.id to SortOrder.ASC)
                    .map { it[NotifyCampaignAudit.actor] }
            auditActors shouldBe listOf(creatorId.toString(), updaterId.toString())
        }
    }

    "concurrent campaign mutations keep optimistic version semantics" {
        val ds =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:campaign_concurrent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        val database = Database.connect(ds)

        val actorId =
            transaction(database) {
                UsersTable.insert {
                    it[telegramUserId] = 2001L
                    it[username] = "mutator"
                }[UsersTable.id]
            }

        val service = DbCampaignService(database)
        val created = service.create(CampaignCreateRequest(title = "t", text = "base"), actorId = actorId)
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first = pool.submit<CampaignDto?> {
                startGate.await()
                runBlocking {
                    service.update(
                        created.id,
                        CampaignUpdateRequest(text = "from-first"),
                        actorId = actorId,
                    )
                }
            }
            val second = pool.submit<CampaignDto?> {
                startGate.await()
                runBlocking {
                    service.setStatus(created.id, CampaignStatus.PAUSED, actorId = actorId)
                }
            }

            startGate.countDown()
            val firstResult = first.get()
            val secondResult = second.get()
            listOf(firstResult, secondResult).count { it != null } shouldBe 1

            val restored = service.find(created.id)!!
            restored.version shouldBe 1

            transaction(database) {
                val audits =
                    NotifyCampaignAudit
                        .selectAll()
                        .where { NotifyCampaignAudit.campaignId eq created.id }
                        .orderBy(NotifyCampaignAudit.id to SortOrder.ASC)
                        .map { it[NotifyCampaignAudit.auditAction] }
                audits.size shouldBe 2
                audits.first() shouldBe "CREATE"
            }
        } finally {
            pool.shutdownNow()
        }
    }

})
