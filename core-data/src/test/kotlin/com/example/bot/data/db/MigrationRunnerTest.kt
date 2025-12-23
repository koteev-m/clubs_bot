package com.example.bot.data.db

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationInfoService
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.output.MigrateResult
import org.flywaydb.core.api.output.ValidateResult
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

class MigrationRunnerTest {
    @Test
    fun `prod validate mode fails on pending migrations without migrating`() {
        val flyway = mockFlyway()
        val info = mockk<MigrationInfoService>()
        val pendingMigration = mockk<MigrationInfo>()
        every { pendingMigration.version } returns MigrationVersion.fromVersion("1.2")
        val pending = arrayOf(pendingMigration)
        val validateResult = ValidateResult("11.14.0", "jdbc:postgresql://localhost/test", null, true, 1, emptyList(), emptyList())

        every { flyway.validateWithResult() } returns validateResult
        every { flyway.info() } returns info
        every { info.all() } returns emptyArray()
        every { info.pending() } returns pending
        every { flyway.migrate() } returns mockk()

        val runner =
            MigrationRunner(
                dataSource = mockDataSource(),
                cfg = FlywayConfig(mode = FlywayMode.VALIDATE, appEnv = AppEnvironment.PROD),
                flywayFactory = { _, _ -> flyway },
            )

        assertThrows<IllegalStateException> { runner.run() }
        verify(exactly = 1) { flyway.validateWithResult() }
        verify(exactly = 0) { flyway.migrate() }
    }

    @Test
    fun `local migrate-and-validate runs migrate`() {
        val flyway = mockFlyway()
        val info = mockk<MigrationInfoService>()
        val validateResult = ValidateResult("11.14.0", "jdbc:postgresql://localhost/test", null, true, 1, emptyList(), emptyList())
        val migrateResult = MigrateResult("11.14.0", "jdbc:postgresql://localhost/test", "public", "1").apply {
            migrationsExecuted = 2
        }

        every { flyway.validateWithResult() } returns validateResult
        every { flyway.migrate() } returns migrateResult
        every { flyway.info() } returns info
        every { info.all() } returns emptyArray()
        every { info.pending() } returns emptyArray()

        val runner =
            MigrationRunner(
                dataSource = mockDataSource(),
                cfg = FlywayConfig(mode = FlywayMode.MIGRATE_AND_VALIDATE, appEnv = AppEnvironment.LOCAL),
                flywayFactory = { _, _ -> flyway },
            )

        val result = runner.run()

        assertTrue(result is MigrationRunner.Result.Migrated)
        verify(exactly = 1) { flyway.migrate() }
        verify(exactly = 1) { flyway.validateWithResult() }
    }

    private fun mockDataSource(): DataSource {
        val meta = mockk<DatabaseMetaData>()
        every { meta.url } returns "jdbc:postgresql://localhost/test"

        val connection = mockk<Connection>()
        every { connection.schema } returns "public"
        every { connection.catalog } returns "postgres"
        every { connection.metaData } returns meta
        justRun { connection.close() }

        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        return dataSource
    }

    private fun mockFlyway(): Flyway {
        val configuration = mockk<Configuration>()
        every { configuration.locations } returns arrayOf(Location("classpath:db/migration/postgresql"))
        every { configuration.schemas } returns emptyArray()

        val flyway = mockk<Flyway>()
        every { flyway.configuration } returns configuration
        return flyway
    }
}
