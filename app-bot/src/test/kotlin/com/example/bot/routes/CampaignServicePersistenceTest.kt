package com.example.bot.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database

class CampaignServicePersistenceTest : StringSpec({
    "campaign state is restored after service restart" {
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

        val firstInstance = DbCampaignService(database)
        val created = firstInstance.create(CampaignCreateRequest(title = "t", text = "hello"))
        firstInstance.setStatus(created.id, CampaignStatus.PAUSED)

        val secondInstance = DbCampaignService(database)
        val restored = secondInstance.find(created.id)

        restored?.status shouldBe CampaignStatus.PAUSED
        restored?.title shouldBe "t"
        restored?.text shouldBe "hello"
    }
})
