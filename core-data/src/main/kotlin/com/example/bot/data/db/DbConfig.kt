package com.example.bot.data.db

import java.util.Locale

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun fromEnv(): DbConfig =
            DbConfig(
                url = envRequired("DATABASE_URL"),
                user = envRequired("DATABASE_USER"),
                password = envRequired("DATABASE_PASSWORD"),
            )
    }
}

enum class FlywayMode {
    VALIDATE,
    MIGRATE_AND_VALIDATE,
    OFF,
}

enum class AppEnvironment(val raw: String) {
    PROD("prod"),
    STAGE("stage"),
    DEV("dev"),
    LOCAL("local"),
    OTHER("other"),
    ;

    val isProdLike: Boolean
        get() = this == PROD || this == STAGE

    val allowsOutOfOrder: Boolean
        get() = this == DEV || this == LOCAL

    companion object {
        fun from(raw: String?): AppEnvironment =
            when (raw?.lowercase(Locale.ROOT)) {
                "prod", "production" -> PROD
                "stage", "staging" -> STAGE
                "dev" -> DEV
                "local" -> LOCAL
                else -> OTHER
            }
    }
}

data class FlywayConfig(
    val enabled: Boolean = true,
    val locations: List<String> = listOf("classpath:db/migration/postgresql"),
    val schemas: List<String> = emptyList(),
    val baselineOnMigrate: Boolean = true,
    val mode: FlywayMode = FlywayMode.MIGRATE_AND_VALIDATE,
    val appEnv: AppEnvironment = AppEnvironment.LOCAL,
    val outOfOrderRequested: Boolean = false,
    val rawAppEnv: String? = null,
) {
    val effectiveMode: FlywayMode = computeEffectiveMode()

    val outOfOrderEnabled: Boolean = computeOutOfOrderEnabled()

    private fun computeEffectiveMode(): FlywayMode =
        if (appEnv.isProdLike && mode == FlywayMode.MIGRATE_AND_VALIDATE) {
            FlywayMode.VALIDATE
        } else {
            mode
        }

    private fun computeOutOfOrderEnabled(): Boolean =
        outOfOrderRequested && appEnv.allowsOutOfOrder

    companion object {
        private const val DEFAULT_LOCATION = "classpath:db/migration"
        private const val POSTGRES_VENDOR = "postgresql"
        private const val H2_VENDOR = "h2"

        fun fromEnv(
            envProvider: (String) -> String? = System::getenv,
            propertyProvider: (String) -> String? = System::getProperty,
            locationsOverride: String? = null,
        ): FlywayConfig {
            val rawAppEnv =
                propertyProvider("APP_ENV")
                    ?: envProvider("APP_ENV")
                    ?: propertyProvider("APP_PROFILE")
                    ?: envProvider("APP_PROFILE")
            val appEnv = AppEnvironment.from(rawAppEnv)
            val url = propertyProvider("DATABASE_URL") ?: envProvider("DATABASE_URL")
            val vendor =
                when {
                    url?.startsWith("jdbc:postgresql", ignoreCase = true) == true -> POSTGRES_VENDOR
                    url?.startsWith("jdbc:h2", ignoreCase = true) == true -> H2_VENDOR
                    else -> null
                } ?: POSTGRES_VENDOR

            val rawLocations = locationsOverride ?: propertyProvider("FLYWAY_LOCATIONS") ?: envProvider("FLYWAY_LOCATIONS")
            val resolvedLocations = resolveLocations(rawLocations, vendor)

            val enabled =
                propertyProvider("FLYWAY_ENABLED")?.toBooleanStrictOrNull()
                    ?: envProvider("FLYWAY_ENABLED")?.toBooleanStrictOrNull()
                    ?: true
            val schemas =
                (propertyProvider("FLYWAY_SCHEMAS") ?: envProvider("FLYWAY_SCHEMAS"))
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            val baselineOnMigrate =
                propertyProvider("FLYWAY_BASELINE_ON_MIGRATE")?.toBooleanStrictOrNull()
                    ?: envProvider("FLYWAY_BASELINE_ON_MIGRATE")?.toBooleanStrictOrNull()
                    ?: true
            val validateOnly =
                propertyProvider("FLYWAY_VALIDATE_ONLY")?.toBooleanStrictOrNull()
                    ?: envProvider("FLYWAY_VALIDATE_ONLY")?.toBooleanStrictOrNull()
                    ?: false
            val requestedMode =
                parseMode(
                    propertyProvider("FLYWAY_MODE") ?: envProvider("FLYWAY_MODE"),
                    defaultMode(appEnv, validateOnly),
                )
            val outOfOrderRequested =
                propertyProvider("FLYWAY_OUT_OF_ORDER")?.toBooleanStrictOrNull()
                    ?: envProvider("FLYWAY_OUT_OF_ORDER")?.toBooleanStrictOrNull()
                    ?: false

            return FlywayConfig(
                enabled = enabled,
                locations = resolvedLocations,
                schemas = schemas,
                baselineOnMigrate = baselineOnMigrate,
                mode = if (validateOnly) FlywayMode.VALIDATE else requestedMode,
                appEnv = appEnv,
                outOfOrderRequested = outOfOrderRequested,
                rawAppEnv = rawAppEnv,
            )
        }

        private fun defaultMode(
            appEnv: AppEnvironment,
            validateOnly: Boolean,
        ): FlywayMode =
            if (validateOnly) {
                FlywayMode.VALIDATE
            } else if (appEnv.isProdLike) {
                FlywayMode.VALIDATE
            } else {
                FlywayMode.MIGRATE_AND_VALIDATE
            }

        private fun parseMode(
            raw: String?,
            default: FlywayMode,
        ): FlywayMode =
            when (raw?.lowercase(Locale.ROOT)) {
                "validate" -> FlywayMode.VALIDATE
                "migrate-and-validate" -> FlywayMode.MIGRATE_AND_VALIDATE
                "off" -> FlywayMode.OFF
                else -> default
            }

        private fun resolveLocations(
            rawLocations: String?,
            vendor: String,
        ): List<String> {
            val trimmedLocations =
                rawLocations
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            if (trimmedLocations.isEmpty()) {
                return listOf("$DEFAULT_LOCATION/$vendor")
            }

            val prioritized = prioritizeVendorLocations(trimmedLocations, vendor)
            val normalized = LinkedHashSet<String>()
            val hasRoot = prioritized.any { isRootLocation(it) }
            val vendorLocations = prioritized.filter { isVendorLocation(it, vendor) }

            if (vendorLocations.isNotEmpty()) {
                normalized.addAll(vendorLocations)
            } else if (hasRoot) {
                normalized.add("$DEFAULT_LOCATION/$vendor")
            }

            prioritized.forEach { normalized.add(it) }

            return normalized.toList()
        }

        private fun prioritizeVendorLocations(
            parsed: List<String>,
            vendor: String,
        ): List<String> {
            val normalized = LinkedHashSet<String>()
            val vendorLocations = parsed.filter { isVendorLocation(it, vendor) }
            if (vendorLocations.isNotEmpty()) {
                normalized.addAll(vendorLocations)
            }
            parsed.forEach { normalized.add(it) }

            return normalized.toList()
        }

        private fun isVendorLocation(
            location: String,
            vendor: String,
        ): Boolean = location.endsWith("/$vendor") || location.contains("/$vendor/")

        private fun isRootLocation(location: String): Boolean =
            location.endsWith("db/migration") || location.endsWith("db/migration/")
    }
}

/** utils */
private fun envRequired(name: String): String = System.getenv(name) ?: error("ENV $name is required")

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (this.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
