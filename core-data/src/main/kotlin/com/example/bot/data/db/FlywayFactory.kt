package com.example.bot.data.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import javax.sql.DataSource

@Suppress("SpreadOperator")
fun configureFlyway(
    dataSource: DataSource,
    cfg: FlywayConfig,
): Flyway = configureFlyway(Flyway.configure().dataSource(dataSource), cfg)

@Suppress("SpreadOperator")
fun configureFlyway(
    configuration: FluentConfiguration,
    cfg: FlywayConfig,
): Flyway {
    val builder =
        configuration
            .locations(*cfg.locations.toTypedArray())
            .baselineOnMigrate(cfg.baselineOnMigrate)
            .validateOnMigrate(true)
            .outOfOrder(cfg.outOfOrderEnabled)

    if (cfg.schemas.isNotEmpty()) {
        builder.schemas(*cfg.schemas.toTypedArray())
    }

    return builder.load()
}
