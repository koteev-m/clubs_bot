import net.ltgt.gradle.flyway.FlywayExtension
import net.ltgt.gradle.flyway.tasks.FlywayTask
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.ltgt.flyway") version "0.2.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

/**
 * Настройки источников БД для flyway plugin (использует ENV или Gradle properties).
 */
val databaseUrlProvider =
    providers.environmentVariable("DATABASE_URL")
        .orElse(providers.gradleProperty("DATABASE_URL"))
val databaseUserProvider =
    providers.environmentVariable("DATABASE_USER")
        .orElse(providers.gradleProperty("DATABASE_USER"))
val databasePasswordProvider =
    providers.environmentVariable("DATABASE_PASSWORD")
        .orElse(providers.gradleProperty("DATABASE_PASSWORD"))

/**
 * Выбор вендора (для layout миграций): common + vendor.
 */
val dbUrl = databaseUrlProvider.orNull
val dbVendor =
    (providers.gradleProperty("dbVendor").orNull)
        ?: when {
            dbUrl?.startsWith("jdbc:h2", ignoreCase = true) == true -> "h2"
            dbUrl?.startsWith("jdbc:postgresql", ignoreCase = true) == true -> "postgresql"
            else -> "postgresql"
        }

val migrationLocationDirs =
    listOf(
        layout.projectDirectory.dir("src/main/resources/db/migration/common"),
        layout.projectDirectory.dir("src/main/resources/db/migration/$dbVendor"),
    )

/**
 * Конфигурация net.ltgt.flyway плагина.
 * Обрати внимание: flyway.locations указываем на filesystem:… для predictable CI.
 */
flyway {
    databaseUrlProvider.orNull?.let { url.set(it) }
    databaseUserProvider.orNull?.let { user.set(it) }
    databasePasswordProvider.orNull?.let { password.set(it) }
    val migrationLocations =
        migrationLocationDirs.joinToString(",") { "filesystem:${it.asFile.absolutePath}" }
    configuration.putAll(mapOf("flyway.locations" to migrationLocations))
}

val flywayExtension = extensions.getByType<FlywayExtension>()
flywayExtension.migrationLocations.setFrom(migrationLocationDirs.map { it.asFile })

/**
 * Pre-flight валидация наличия переменных для задач flyway.
 */
tasks.withType<FlywayTask>().configureEach {
    doFirst("validateFlywayDatabaseConfiguration") {
        val missing =
            buildList {
                if (flywayExtension.url.orNull.isNullOrBlank()) add("DATABASE_URL")
                if (flywayExtension.user.orNull.isNullOrBlank()) add("DATABASE_USER")
                if (flywayExtension.password.orNull == null) add("DATABASE_PASSWORD")
            }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing required environment variables for Flyway migrations: " +
                    missing.joinToString(", ") +
                    ". Provide them via environment variables or Gradle properties.",
            )
        }
    }
}

dependencies {
    val exposedVersion = libs.versions.exposed.get()

    // Доменные
    implementation(projects.coreDomain)

    // Exposed + JDBC pool
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation(libs.hikari)

    // Flyway (runtime приложения) — core + модуль Postgres (ВАЖНО для PG16+)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pg)
    implementation(libs.postgres)

    // JSON
    implementation(libs.kotlinx.serialization.json)

    // Тесты
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(projects.coreTesting)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)

    // Зависимости для Gradle flyway-плагина (задач flywayMigrate/flywayInfo/etc.)
    flyway(libs.flyway.core)
    flyway(libs.flyway.pg)
    flyway(libs.postgres)
    flyway(libs.h2)
}

tasks.test {
    useJUnitPlatform()
    if (project.hasProperty("runIT")) {
        systemProperty("junit.jupiter.tags.include", "it")
    } else {
        systemProperty("junit.jupiter.tags.exclude", "it")
    }
    systemProperty("FLYWAY_LOCATIONS", "classpath:db/migration,classpath:db/migration/postgresql")
}
