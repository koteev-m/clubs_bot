import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val kotlinVersionProperty = providers.gradleProperty("kotlinVersion")
    .orElse(providers.environmentVariable("KOTLIN_VERSION"))
    .orElse("2.2.10")
val kotlinVersion = kotlinVersionProperty.get()

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedGroup = requested.group
            val requestedName = requested.name
            if (
                requestedGroup == "org.jetbrains.kotlin" &&
                (requestedName == "kotlin-stdlib-jdk7" || requestedName == "kotlin-stdlib-jdk8")
            ) {
                useTarget("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                because("Collapse legacy kotlin-stdlib-jdk7/jdk8 to kotlin-stdlib for Kotlin $kotlinVersion")
            }
        }
    }
}

abstract class DependencyGuard : DefaultTask() {
    @get:Inject
    protected abstract val configurationContainer: ConfigurationContainer

    @get:Inject
    protected abstract val providerFactory: ProviderFactory

    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Input
    val configurationNames: ListProperty<String> = objects.listProperty<String>().convention(
        listOf(
            "compileClasspath",
            "runtimeClasspath",
            "testCompileClasspath",
            "testRuntimeClasspath",
        )
    )

    @get:Input
    val bannedArtifacts: ListProperty<String> = objects.listProperty<String>().convention(
        listOf(
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        )
    )

    @get:Input
    val enforcedKtorVersion: Property<String> = objects.property<String>().convention(
        providerFactory.gradleProperty("ktorEnforcedVersion")
            .orElse(providerFactory.environmentVariable("KTOR_VERSION"))
            .orElse("3.3.0")
    )

    @TaskAction
    fun run() {
        val banned = bannedArtifacts.get()
        val enforcedKtor = enforcedKtorVersion.get()

        val configs = configurationNames.get().mapNotNull { configurationContainer.findByName(it) }

        val all = configs.flatMap { cfg ->
            cfg.resolvedConfiguration.lenientConfiguration.allModuleDependencies.flatMap { dep ->
                sequenceOf("${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}") +
                    dep.children.map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
            }
        }.toSet()

        val legacy = all.filter { line -> banned.any { line.startsWith(it) } }
        if (legacy.isNotEmpty()) {
            error("DependencyGuard: legacy kotlin stdlib artifacts detected:\n${legacy.joinToString("\n")}")
        }

        val ktor = all.filter { it.startsWith("io.ktor:") }
        val wrongKtor = ktor.filterNot { it.endsWith(":$enforcedKtor") }
        if (wrongKtor.isNotEmpty()) {
            error("DependencyGuard: mismatched Ktor versions (expected $enforcedKtor):\n${wrongKtor.joinToString("\n")}")
        }

        val dynamic = all.filter {
            it.endsWith(":latest.release") ||
                it.endsWith(":latest.integration") ||
                it.contains("SNAPSHOT")
        }
        if (dynamic.isNotEmpty()) {
            error("DependencyGuard: dynamic/SNAPSHOT dependencies detected:\n${dynamic.joinToString("\n")}")
        }

        println("DependencyGuard: OK (${all.size} artifacts checked)")
    }
}

tasks.register<DependencyGuard>("dependencyGuard") {
    group = "verification"
    description = "Fail build if dependency rules are violated"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

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
        baseline = rootProject.file("config/detekt/baseline.xml")
    }

    tasks.withType<Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            md.required.set(false)
            val reportsDir = project.layout.buildDirectory
            html.outputLocation.set(reportsDir.file("reports/detekt/detekt.html"))
            sarif.outputLocation.set(reportsDir.file("reports/detekt/detekt.sarif"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(from = rootProject.file("gradle/detekt-cli.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint-cli.gradle.kts"))
    }

    tasks.withType<Test>().configureEach {
        val runIt = project.findProperty("runIT")?.toString()?.equals("true", ignoreCase = true) == true
        useJUnitPlatform {
            if (!runIt) {
                excludeTags("it")
            }
        }
    }
}

tasks.register("staticCheck") {
    group = "verification"
    description = "Run detekt CLI and ktlint CLI across all Kotlin modules"
    dependsOn(
        subprojects.flatMap { sp ->
            listOfNotNull(
                sp.tasks.findByName("detektCli"),
                sp.tasks.findByName("ktlintCheckCli")
            )
        }
    )
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Run ktlint format for all Kotlin modules"
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("ktlintFormatCli") }
    )
}

tasks.register("flywayMigrate") {
    group = "database"
    description = "Run Flyway migrations via :core-data module"
    dependsOn(":core-data:flywayMigrate")
}
