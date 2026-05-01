import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
    id("org.owasp.dependencycheck") version "12.1.8"
    alias(libs.plugins.versionsPlugin)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Управляемые через -PkotlinVersion / env KOTLIN_VERSION
val kotlinVersionProperty =
    providers
        .gradleProperty("kotlinVersion")
        .orElse(providers.environmentVariable("KOTLIN_VERSION"))
        .orElse("2.2.20")
val kotlinVersion = kotlinVersionProperty.get()

// Управляемые через -Pslf4jVersion / env SLF4J_VERSION
val slf4jVersionProperty =
    providers
        .gradleProperty("slf4jVersion")
        .orElse(providers.environmentVariable("SLF4J_VERSION"))
        .orElse("2.0.17")
val slf4jVersion = slf4jVersionProperty.get()

allprojects {
    // Глобальная стратегия: схлопываем legacy stdlib и выравниваем SLF4J
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedGroup = requested.group
            val requestedName = requested.name

            // kotlin-stdlib-jdk7/8 → kotlin-stdlib одной версии
            if (
                requestedGroup == "org.jetbrains.kotlin" &&
                (requestedName == "kotlin-stdlib-jdk7" || requestedName == "kotlin-stdlib-jdk8")
            ) {
                useTarget("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                because(
                    "kotlin-stdlib-jdk7/8 объединены в kotlin-stdlib в Kotlin 1.8+; используем единый stdlib $kotlinVersion",
                )
            }

            // Выравниваем слои логирования
            if (requestedGroup == "org.slf4j" && requestedName == "slf4j-api") {
                useVersion(slf4jVersion)
                because("Единая версия SLF4J ($slf4jVersion) для согласованной политики логирования")
            }
        }
    }
}

// -------------------------
// Кастомная проверка зависимостей
// -------------------------
abstract class DependencyGuard : DefaultTask() {
    @get:Inject
    protected abstract val configurationContainer: ConfigurationContainer

    @get:Inject
    protected abstract val providerFactory: ProviderFactory

    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Input
    val configurationNames: ListProperty<String> =
        objects.listProperty<String>().convention(
            listOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
            ),
        )

    @get:Input
    val bannedArtifacts: ListProperty<String> =
        objects.listProperty<String>().convention(
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
            ),
        )

    @get:Input
    val enforcedKtorVersion: Property<String> =
        objects.property<String>().convention(
            providerFactory
                .gradleProperty("ktorEnforcedVersion")
                .orElse(providerFactory.environmentVariable("KTOR_VERSION"))
                .orElse("3.3.1"),
        )

    @TaskAction
    fun run() {
        val banned = bannedArtifacts.get()
        the@ run {
            val enforcedKtor = enforcedKtorVersion.get()

            val configs =
                configurationNames
                    .get()
                    .mapNotNull { name -> configurationContainer.findByName(name) }

            val allArtifacts: Set<String> =
                configs
                    .flatMap { cfg ->
                        cfg.resolvedConfiguration.lenientConfiguration.allModuleDependencies.flatMap { dep ->
                            sequenceOf("${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}") +
                                dep.children.map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
                        }
                    }.toSet()

            val legacyStdlib = allArtifacts.filter { line -> banned.any { line.startsWith(it) } }
            if (legacyStdlib.isNotEmpty()) {
                error(
                    "DependencyGuard: legacy Kotlin stdlib артефакты обнаружены:\n" +
                        legacyStdlib.joinToString("\n"),
                )
            }

            val ktorArtifacts = allArtifacts.filter { it.startsWith("io.ktor:") }
            val mismatchedKtor = ktorArtifacts.filterNot { it.endsWith(":$enforcedKtor") }
            if (mismatchedKtor.isNotEmpty()) {
                error(
                    "DependencyGuard: несовпадение версий Ktor (ожидается $enforcedKtor):\n" +
                        mismatchedKtor.joinToString("\n"),
                )
            }

            val dynamic =
                allArtifacts.filter {
                    it.endsWith(":latest.release") ||
                        it.endsWith(":latest.integration") ||
                        it.contains("SNAPSHOT")
                }
            if (dynamic.isNotEmpty()) {
                error(
                    "DependencyGuard: обнаружены динамические/SNAPSHOT зависимости:\n" +
                        dynamic.joinToString("\n"),
                )
            }

            println("DependencyGuard: OK (${allArtifacts.size} artifacts checked)")
        }
    }
}

tasks.register<DependencyGuard>("dependencyGuard") {
    group = "verification"
    description = "Fail build if dependency rules are violated"
}

val dependencyCheckDataDir = File("${rootProject.projectDir}/.gradle/dependency-check-data")
val dependencyCheckWarmMarker = dependencyCheckDataDir.resolve("cache-warm.marker")

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0F
    suppressionFile = "${rootProject.projectDir}/config/dependency-check/suppressions.xml"
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
    data.directory = dependencyCheckDataDir.path
    val nvdApiKey = providers.environmentVariable("NVD_API_KEY").orNull
    nvd.apiKey = nvdApiKey
    autoUpdate = !nvdApiKey.isNullOrBlank()
}

tasks.named("dependencyCheckAggregate") {
    group = "verification"
    description = "SCA gate: OWASP Dependency-Check aggregate scan (fails on HIGH/CRITICAL CVEs)"
    dependsOn("scaPreflight")
    notCompatibleWithConfigurationCache("dependency-check tasks are not configuration-cache safe on Gradle 9")
}

tasks.register("scaCheck") {
    group = "verification"
    description = "Run aggregate JVM SCA policy gate (OWASP Dependency-Check)"
    dependsOn("dependencyCheckAggregate")
}

tasks.register("scaPreflight") {
    group = "verification"
    description = "Validate SCA prerequisites (NVD API key or warmed/fresh local cache)"
    notCompatibleWithConfigurationCache("sca preflight reads runtime environment and local cache state")
    doLast {
        val nvdApiKey = providers.environmentVariable("NVD_API_KEY").orNull
        val hasApiKey = !nvdApiKey.isNullOrBlank()
        val hasWarmMarker = dependencyCheckWarmMarker.exists()
        val hasCacheData =
            dependencyCheckDataDir.exists() &&
                (dependencyCheckDataDir.listFiles()?.any { it.isFile || it.isDirectory } == true)

        if (!hasApiKey && !(hasWarmMarker && hasCacheData)) {
            throw GradleException(
                "scaCheck requires NVD_API_KEY or an explicitly warmed Dependency-Check cache. " +
                    "Warm local cache via ./gradlew dependencyCheckUpdate scaWarmCacheMark with NVD_API_KEY, " +
                    "then re-run scaCheck. Expected marker: ${dependencyCheckWarmMarker.path}",
            )
        }
    }
}

tasks.register("scaWarmCacheMark") {
    group = "verification"
    description = "Write explicit marker that local Dependency-Check cache was warmed via dependencyCheckUpdate"
    dependsOn("dependencyCheckUpdate")
    doLast {
        dependencyCheckDataDir.mkdirs()
        dependencyCheckWarmMarker.writeText("warmedAt=${System.currentTimeMillis()}\n")
        logger.lifecycle("Dependency-Check cache warm marker updated: ${dependencyCheckWarmMarker.path}")
    }
}

// -------------------------
// Настройки подмодулей
// -------------------------
subprojects {
    // Линтеры
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    val moduleBaselinePrefix = "config/detekt/baseline-${project.path.removePrefix(":").replace(':', '-')}"
    val moduleDetektBaseline = rootProject.file("$moduleBaselinePrefix.xml")
    val moduleDetektMainBaseline = rootProject.file("$moduleBaselinePrefix-main.xml")
    val moduleDetektTestBaseline = rootProject.file("$moduleBaselinePrefix-test.xml")
    val appBotMainBaseline = rootProject.file("config/detekt/baseline-main.xml")
    val appBotTestBaseline = rootProject.file("config/detekt/baseline-test.xml")

    configure<KtlintExtension> {
        ignoreFailures.set(false)
        android.set(false)
        verbose.set(true)
        outputToConsole.set(true)
        filter {
            include("**/src/**/*.kt")
        }
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files(rootProject.file("detekt.yml")))
        baseline =
            when (project.path) {
                ":app-bot" -> appBotMainBaseline
                else -> moduleDetektBaseline
            }
    }

    tasks.withType<Detekt>().configureEach {
        baseline =
            when {
                project.path == ":app-bot" && name == "detektTest" -> appBotTestBaseline
                project.path == ":app-bot" -> appBotMainBaseline
                name == "detektMain" -> moduleDetektMainBaseline
                name == "detektTest" -> moduleDetektTestBaseline
                else -> moduleDetektBaseline
            }
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            md.required.set(false)
            val out = project.layout.buildDirectory
            html.outputLocation.set(out.file("reports/detekt/detekt.html"))
            sarif.outputLocation.set(out.file("reports/detekt/detekt.sarif"))
            txt.required.set(true)
            txt.outputLocation.set(project.layout.buildDirectory.file("reports/detekt/detekt.txt"))
        }
    }

    // CLI-обёртки (если есть соответствующие файлы в репо)
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(from = rootProject.file("gradle/detekt-cli.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint-cli.gradle.kts"))
    }

    // ВАЖНО: CLI-таски не совместимы с конфигурационным кэшем — помечаем это явно
    tasks
        .matching { it.name in listOf("ktlintCheckCli", "ktlintFormatCli", "detektCli") }
        .configureEach {
            notCompatibleWithConfigurationCache(
                "CLI wrappers capture Project/Provider; use plugin tasks instead",
            )
        }

    // Тесты: -PrunIT=true для интеграционных
    tasks.withType<Test>().configureEach {
        val runIt =
            project.findProperty("runIT")?.toString()?.equals("true", ignoreCase = true) == true
        if (name != "itTest") {
            useJUnitPlatform {
                if (!runIt) {
                    excludeTags("it")
                }
            }
        }
    }
}

// Удобные агрегирующие команды (плагинные таски)
tasks.register("staticCheck") {
    group = "verification"
    description = "Run detekt and ktlint (plugin tasks) across all Kotlin modules"
    dependsOn(
        subprojects.flatMap { sp ->
            listOfNotNull(
                sp.tasks.findByName("detekt"),
                sp.tasks.findByName("detektTest"),
                sp.tasks.findByName("ktlintCheck"),
            )
        },
    )
}

tasks.register("detektGate") {
    group = "verification"
    description = "Run detekt across all Kotlin subprojects with baseline-aware strategy"
    dependsOn(
        subprojects.flatMap { project ->
            val baselineAwareTasks =
                listOfNotNull(
                    project.tasks.findByName("detektMain"),
                    project.tasks.findByName("detektTest"),
                )
            if (baselineAwareTasks.isNotEmpty()) {
                baselineAwareTasks
            } else {
                listOfNotNull(project.tasks.findByName("detekt"))
            }
        },
    )
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Run ktlint format (plugin task) for all Kotlin modules"
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("ktlintFormat") },
    )
}

tasks.register("flywayMigrate") {
    group = "database"
    description = "Run Flyway migrations via :core-data module"
    dependsOn(":core-data:flywayMigrate")
}

tasks.register("coverageGate") {
    group = "verification"
    description = "Run coverage report generation + verification gate"
    dependsOn(":app-bot:jacocoTestReport", ":app-bot:jacocoTestCoverageVerification")
}
