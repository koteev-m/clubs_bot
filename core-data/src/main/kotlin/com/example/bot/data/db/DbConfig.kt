package com.example.bot.data.db

import java.util.Locale

data class DbConfig(val url: String, val user: String, val password: String) {
    companion object {
        fun fromEnv(): DbConfig {
            return DbConfig(
                url = envRequired("DATABASE_URL"),
                user = envRequired("DATABASE_USER"),
                password = envRequired("DATABASE_PASSWORD"),
            )
        }
    }
}

data class FlywayConfig(
    val enabled: Boolean = true,
    val locations: List<String> = listOf("classpath:db/migration"),
    val schemas: List<String> = emptyList(),
    val baselineOnMigrate: Boolean = true,
    val validateOnly: Boolean = false,
) {
    companion object {
        fun fromEnv(): FlywayConfig {
            val baseLocations = mutableListOf(DEFAULT_LOCATION)
            val url = prop("DATABASE_URL") ?: env("DATABASE_URL")
            val vendor =
                when {
                    url?.startsWith("jdbc:postgresql", ignoreCase = true) == true -> "postgresql"
                    url?.startsWith("jdbc:h2", ignoreCase = true) == true -> "h2"
                    else -> null
                }
            if (vendor != null) {
                baseLocations += "$DEFAULT_LOCATION/$vendor"
            }

            val locationsOverride = prop("FLYWAY_LOCATIONS") ?: env("FLYWAY_LOCATIONS")
            val resolvedLocations =
                locationsOverride
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: baseLocations.toList()

            val enabled =
                prop("FLYWAY_ENABLED")?.toBooleanStrictOrNull()
                    ?: env("FLYWAY_ENABLED")?.toBooleanStrictOrNull()
                    ?: true
            val schemas =
                (prop("FLYWAY_SCHEMAS") ?: env("FLYWAY_SCHEMAS"))
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            val baselineOnMigrate =
                prop("FLYWAY_BASELINE_ON_MIGRATE")?.toBooleanStrictOrNull()
                    ?: env("FLYWAY_BASELINE_ON_MIGRATE")?.toBooleanStrictOrNull()
                    ?: true
            val validateOnly =
                prop("FLYWAY_VALIDATE_ONLY")?.toBooleanStrictOrNull()
                    ?: env("FLYWAY_VALIDATE_ONLY")?.toBooleanStrictOrNull()
                    ?: false

            return FlywayConfig(
                enabled = enabled,
                locations = resolvedLocations,
                schemas = schemas,
                baselineOnMigrate = baselineOnMigrate,
                validateOnly = validateOnly,
            )
        }

        private const val DEFAULT_LOCATION = "classpath:db/migration"
    }
}

/** utils */
private fun env(name: String): String? = System.getenv(name)

private fun prop(name: String): String? = System.getProperty(name)

private fun envRequired(name: String): String = env(name) ?: error("ENV $name is required")

private fun String.toBooleanStrictOrNull(): Boolean? {
    return when (this.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
