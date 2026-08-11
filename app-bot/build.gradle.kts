import com.example.build.LogsPolicyScanTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.jar.JarFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    jacoco
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Для Kotest: явно не глушим stdout
    systemProperty("kotest.framework.dump.test.stdout", "true")

    // Нужен для Mini App авторизации в тестах
    environment("TELEGRAM_BOT_TOKEN", "111111:TEST_BOT_TOKEN")
    environment("NOTIFICATIONS_ENABLED", "false")

    // Локации миграций: unit → H2, интеграционные (-PrunIT) → PostgreSQL
    if (project.hasProperty("runIT")) {
        systemProperty("FLYWAY_LOCATIONS", "classpath:db/migration/postgresql")
    } else {
        systemProperty("FLYWAY_LOCATIONS", "classpath:db/migration/h2")
    }
}

dependencies {
    // Ktor
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.request.size.limit)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    // Модули проекта
    implementation(projects.coreDomain)
    implementation(projects.coreData)
    implementation(projects.coreTelemetry)
    implementation(projects.coreSecurity)

    // DB (Exposed в core-data; тут подстрахуем драйвер PG на runtime)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.postgres)

    // Миграции — НУЖНО в main (иначе org.flywaydb.core.Flyway не резолвится)
    implementation(platform(libs.jackson.bom))
    implementation(libs.flyway.core)
    implementation(libs.flyway.pg)

    // Observability / logging
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.slf4j.api)
    implementation(libs.logback)
    implementation(libs.logstash.encoder)
    implementation(libs.janino)
    implementation(libs.kotlinx.coroutines.slf4j)

    // Telegram
    implementation(libs.pengrad.telegram)

    // DI
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Tests
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2)
    testImplementation(libs.postgres)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(projects.coreTesting)
    testImplementation(libs.opentelemetry.sdk.testing)
}

// =========================
// Mini App assets → resources
// =========================

val miniAppDistDir = rootProject.layout.projectDirectory.dir("miniapp/dist")
val miniAppStaticDir = rootProject.layout.projectDirectory.dir("miniapp/src/main/resources/miniapp")

tasks.register<Copy>("copyMiniAppDist") {
    description = "Copy Mini App compiled assets into resources so Ktor can serve them from the JAR."
    group = "build"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(miniAppDistDir) { include("**/*") }
    from(miniAppStaticDir) { include("**/*") }
    into(layout.buildDirectory.dir("generated/miniapp/webapp/app"))
    inputs.dir(miniAppDistDir).optional()
    inputs.dir(miniAppStaticDir).optional()
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("copyMiniAppDist")
    from(layout.buildDirectory.dir("generated/miniapp")) {
        into("")
    }
}

val ktorEngineMainClass = "io.ktor.server.netty.EngineMain"
val quiescedMigrationMainClass = "com.example.bot.tools.QuiescedMigrateMainKt"
val quiescedMigrationLogConfig = "quiesced-migration-logback.xml"

application {
    // EngineMain + application.conf (modules = [ com.example.bot.ApplicationKt.module ])
    mainClass.set(ktorEngineMainClass)
    applicationDefaultJvmArgs =
        listOf(
            "-Dfile.encoding=UTF-8",
            "-XX:+ExitOnOutOfMemoryError",
        )
}

val quiescedMigrationStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "app-bot-migrate-java"
    mainClass.set(quiescedMigrationMainClass)
    classpath =
        tasks
            .named<CreateStartScripts>("startScripts")
            .get()
            .classpath
    outputDir =
        layout.buildDirectory
            .dir("quiesced-migration-start-scripts")
            .get()
            .asFile
    defaultJvmOpts =
        application.applicationDefaultJvmArgs +
        listOf(
            "-Dlogback.configurationFile=$quiescedMigrationLogConfig",
            "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
        )
    doFirst {
        checkNotNull(outputDir).deleteRecursively()
    }
}

distributions {
    named("main") {
        contents {
            from(quiescedMigrationStartScripts) {
                into("bin")
            }
        }
    }
}

tasks.named("installDist") {
    inputs.property("expectedRuntimeMainClass", ktorEngineMainClass)
    inputs.property("expectedQuiescedMigrationMainClass", quiescedMigrationMainClass)
    inputs.property("expectedQuiescedMigrationLogConfig", quiescedMigrationLogConfig)

    doLast {
        val expectedRuntimeMainClass = inputs.properties.getValue("expectedRuntimeMainClass") as String
        val expectedQuiescedMigrationMainClass =
            inputs.properties.getValue("expectedQuiescedMigrationMainClass") as String
        val expectedQuiescedMigrationLogConfig =
            inputs.properties.getValue("expectedQuiescedMigrationLogConfig") as String
        val launcher = outputs.files.singleFile.resolve("bin/app-bot")
        check(launcher.isFile) {
            "installDist launcher was not generated: ${launcher.absolutePath}"
        }

        val launcherText = launcher.readText()
        check(launcherText.contains(expectedRuntimeMainClass)) {
            "installDist launcher must use $expectedRuntimeMainClass"
        }
        check(!launcherText.contains("com.example.bot.ApplicationKt")) {
            "installDist launcher must not use ApplicationKt without a main function"
        }
        check(!launcherText.contains(expectedQuiescedMigrationLogConfig)) {
            "application launcher must not use the migration-only logging configuration"
        }

        val publicMigrationLauncher = launcher.parentFile.resolve("app-bot-migrate")
        check(publicMigrationLauncher.isFile && publicMigrationLauncher.canExecute()) {
            "fixed quiesced migration boundary was not packaged: ${publicMigrationLauncher.absolutePath}"
        }
        val publicMigrationLauncherText = publicMigrationLauncher.readText()
        val requiredBoundaryContracts =
            listOf(
                "unset JAVA_TOOL_OPTIONS",
                "unset JDK_JAVA_OPTIONS",
                "unset _JAVA_OPTIONS",
                "unset JAVA_OPTS",
                "unset APP_BOT_MIGRATE_JAVA_OPTS",
                "JAVA_HOME=/opt/java/openjdk",
                "exec \"\$private_launcher\"",
            )
        for (requiredBoundaryContract in requiredBoundaryContracts) {
            check(publicMigrationLauncherText.contains(requiredBoundaryContract)) {
                "fixed quiesced migration boundary lacks: $requiredBoundaryContract"
            }
        }
        check(!publicMigrationLauncherText.contains(expectedQuiescedMigrationMainClass)) {
            "public migration boundary must delegate only to the private launcher"
        }

        val migrationLaunchers =
            listOf(
                launcher.parentFile.resolve("app-bot-migrate-java"),
                launcher.parentFile.resolve("app-bot-migrate-java.bat"),
            )
        migrationLaunchers.forEach { migrationLauncher ->
            check(migrationLauncher.isFile) {
                "quiesced migration launcher was not generated: ${migrationLauncher.absolutePath}"
            }
            if (migrationLauncher.extension != "bat") {
                check(migrationLauncher.canExecute()) {
                    "quiesced migration launcher is not executable: ${migrationLauncher.absolutePath}"
                }
            }
            val migrationLauncherText = migrationLauncher.readText()
            check(migrationLauncherText.contains(expectedQuiescedMigrationMainClass)) {
                "quiesced migration launcher must use $expectedQuiescedMigrationMainClass"
            }
            check(!migrationLauncherText.contains(expectedRuntimeMainClass)) {
                "quiesced migration launcher must not start Ktor EngineMain"
            }
            check(migrationLauncherText.contains("-Dlogback.configurationFile=$expectedQuiescedMigrationLogConfig")) {
                "quiesced migration launcher must use the packaged safe logging configuration"
            }
        }

        val appJar = outputs.files.singleFile.resolve("lib/app-bot.jar")
        JarFile(appJar).use { jar ->
            check(jar.getEntry(expectedQuiescedMigrationLogConfig) != null) {
                "migration-only logging configuration is absent from app-bot.jar"
            }
        }
    }
}

/**
 * Утилита для запуска миграций из рантайма приложения.
 */
val runMigrations by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Flyway migrations using app runtime"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.bot.tools.MigrateMainKt")
}

// ----------------------------------------------------------------------
// SEC-02: quality gate — CC-friendly LogsPolicyScanTask
// ----------------------------------------------------------------------
tasks.register<LogsPolicyScanTask>("checkLogsPolicy") {
    group = "verification"
    description = "SEC-02: scan sources for sensitive logging patterns (ripgrep with JVM fallback)"

    // Сначала тесты (содержат проверки MDC)
    dependsOn("test")

    // КЛЮЧЕВОЕ: сузить inputs до исходников — без build/*
    // ConfigurableFileCollection → используем setFrom(...)
    sourceDirs.setFrom(
        // исходники текущего модуля
        layout.projectDirectory.dir("src"),
        // фронтовые исходники; если у вас другая структура, поменяйте путь
        rootProject.layout.projectDirectory.dir("miniapp"),
    )

    // Ищем только исходные файлы (паттерны применяются внутри каждого sourceDir)
    includeGlobs.set(
        listOf(
            "**/*.kt",
            "**/*.kts",
            "**/*.java",
            "**/*.ts",
            "**/*.tsx",
            "**/*.js",
        ),
    )

    // Исключаем артефакты и «тяжёлые» директории
    excludeGlobs.set(
        listOf(
            "**/build/**",
            "dist/**",
            "**/node_modules/**",
            "**/.gradle/**",
            "**/.idea/**",
            "**/.git/**",
            "**/*.iml",
            "**/test/**",
            "**/src/test/**",
            "**/fixtures/**",
            "**/resources/**",
        ),
    )

    // Полные PCRE2‑паттерны (те же, что раньше)
    patterns.set(
        listOf(
            // logger.*( ... qr= ... )
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*qr=",
            // logger.*( ... start_param= ... )
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*start_param=",
            // logger.*( ... idempotencyKey ... )
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*\\bidempotencyKey\\b",
            // "голые" Telegram bot tokens
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*\\b\\d{6,12}:[A-Za-z0-9_-]{30,}\\b",
            // сырые телефоны
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*\\+?\\d[\\d \\-\\(\\)]{8,}\\d",
            // ключи с именами
            "(?<!\\w)(?:logger|log|LOG|LOGGER)\\." +
                "(?:trace|debug|info|warn|error)\\([^\\n]*\\b(ФИО|fullName|fio|name)\\s*=",
        ),
    )

    // При необходимости можно задать путь к rg:
    // ripgrepExecutable.set("/usr/local/bin/rg")

    // Можно сохранить отчёт:
    // reportFile.set(layout.buildDirectory.file("reports/logspolicy/scan.txt"))
}

// Включаем гейт в фазу проверки модуля
tasks.named("check") {
    dependsOn("checkLogsPolicy")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.40".toBigDecimal()
            }
        }

        rule {
            element = "CLASS"
            includes =
                listOf(
                    "com.example.bot.routes.BookingA3RoutesKt",
                    "com.example.bot.routes.SecuredBookingRoutesKt",
                    "com.example.bot.routes.CheckinRoutesKt",
                    "com.example.bot.routes.CheckinCompatRoutesKt",
                    "com.example.bot.routes.HostCheckinRoutesKt",
                    "com.example.bot.routes.PaymentsCancelRefundRoutesKt",
                    "com.example.bot.routes.PaymentsFinalizeRoutesKt",
                    "com.example.bot.booking.a3.BookingState",
                    "com.example.bot.telemetry.PaymentsObservability",
                    "com.example.bot.metrics.UiCheckinMetrics",
                )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
