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
import java.security.MessageDigest
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

val dependencyCheckDataDirPath =
    providers
        .gradleProperty("dependencyCheckDataDir")
        .orElse(providers.environmentVariable("DEPENDENCY_CHECK_DATA_DIR"))
        .orElse("${rootProject.projectDir}/.gradle/dependency-check-data")
val dependencyCheckDataDir = File(dependencyCheckDataDirPath.get())
val dependencyCheckWarmMarker = dependencyCheckDataDir.resolve("cache-warm.marker")
val dependencyCheckWarmManifest = dependencyCheckDataDir.resolve("cache-warm.manifest")
val scaCacheMaxAgeHours = 168L

data class ScaPayloadEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class ScaPayloadManifest(
    val payloadFileCount: Long,
    val payloadTotalBytes: Long,
    val payloadDigest: String,
    val entries: List<ScaPayloadEntry>,
)

data class ScaPayloadActual(
    val entries: List<ScaPayloadEntry>,
    val payloadFileCount: Long,
    val payloadTotalBytes: Long,
    val payloadDigest: String,
)

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun File.sha256HexStreaming(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun collectScaPayloadEntries(
    dataDir: File,
    marker: File,
    manifest: File,
): List<ScaPayloadEntry> =
    dataDir
        .walkTopDown()
        .filter { it.isFile }
        .filterNot { it == marker || it == manifest }
        .map { file ->
            val relativePath = file.relativeTo(dataDir).invariantSeparatorsPath
            ScaPayloadEntry(
                path = relativePath,
                size = file.length(),
                sha256 = file.sha256HexStreaming(),
            )
        }.sortedBy { it.path }
        .toList()

fun aggregateScaPayloadDigest(entries: List<ScaPayloadEntry>): String =
    sha256Hex(entries.joinToString("\n") { "${it.path}:${it.size}:${it.sha256}" }.toByteArray())

fun serializeScaPayloadManifest(entries: List<ScaPayloadEntry>): String {
    val totalBytes = entries.sumOf { it.size }
    val aggregateDigest = aggregateScaPayloadDigest(entries)
    val payloadLines = entries.joinToString(separator = "\n") { "file=${it.path}|${it.size}|${it.sha256}" }
    return buildString {
        append("payloadFileCount=${entries.size}\n")
        append("payloadTotalBytes=$totalBytes\n")
        append("payloadDigest=$aggregateDigest\n")
        if (payloadLines.isNotEmpty()) {
            append(payloadLines)
            append('\n')
        }
    }
}

fun parseScaPayloadManifest(file: File): ScaPayloadManifest {
    val fields = mutableMapOf<String, String>()
    val entries = mutableListOf<ScaPayloadEntry>()
    file.readLines().forEach { line ->
        if (line.startsWith("file=")) {
            val payload = line.removePrefix("file=")
            val parts = payload.split('|', limit = 3)
            if (parts.size != 3) throw GradleException("scaCheck warm manifest is invalid (malformed file entry)")
            entries +=
                ScaPayloadEntry(
                    path = parts[0],
                    size =
                        parts[1].toLongOrNull()
                            ?: throw GradleException("scaCheck warm manifest is invalid (malformed file size)"),
                    sha256 = parts[2],
                )
        } else {
            val idx = line.indexOf('=')
            if (idx > 0) {
                fields[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }
    }
    return ScaPayloadManifest(
        payloadFileCount =
            fields["payloadFileCount"]?.toLongOrNull()
                ?: throw GradleException("scaCheck warm manifest is invalid (missing payloadFileCount)"),
        payloadTotalBytes =
            fields["payloadTotalBytes"]?.toLongOrNull()
                ?: throw GradleException("scaCheck warm manifest is invalid (missing payloadTotalBytes)"),
        payloadDigest =
            fields["payloadDigest"]?.takeIf { it.isNotBlank() }
                ?: throw GradleException("scaCheck warm manifest is invalid (missing payloadDigest)"),
        entries = entries.sortedBy { it.path },
    )
}

fun collectScaPayloadActual(
    dataDir: File,
    marker: File,
    manifest: File,
): ScaPayloadActual {
    val entries = collectScaPayloadEntries(dataDir, marker, manifest)
    return ScaPayloadActual(
        entries = entries,
        payloadFileCount = entries.size.toLong(),
        payloadTotalBytes = entries.sumOf { it.size },
        payloadDigest = aggregateScaPayloadDigest(entries),
    )
}

fun validateWarmManifestContractOrThrow(
    expected: ScaPayloadManifest,
    actual: ScaPayloadActual,
    errorMessage: String,
    emptyPayloadErrorMessage: String = errorMessage,
) {
    if (expected.payloadFileCount <= 0L || expected.payloadTotalBytes <= 0L || expected.entries.isEmpty()) {
        throw GradleException(emptyPayloadErrorMessage)
    }
    if (
        expected.payloadFileCount != actual.payloadFileCount ||
        expected.payloadTotalBytes != actual.payloadTotalBytes ||
        expected.payloadDigest != actual.payloadDigest ||
        expected.entries != actual.entries
    ) {
        throw GradleException(errorMessage)
    }
}

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

        if (hasApiKey) return@doLast

        if (!dependencyCheckWarmMarker.exists() || !dependencyCheckWarmManifest.exists()) {
            val markerPath = dependencyCheckWarmMarker.path
            val manifestPath = dependencyCheckWarmManifest.path
            throw GradleException(
                "scaCheck requires NVD_API_KEY or warmed local cache. " +
                    "Warm marker/manifest not found at $markerPath and $manifestPath. " +
                    "Run ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark with NVD_API_KEY.",
            )
        }

        val markerFields =
            dependencyCheckWarmMarker
                .readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }.toMap()

        val warmedAt =
            markerFields["warmedAt"]?.toLongOrNull()
                ?: throw GradleException(
                    "scaCheck warm marker is invalid (missing warmedAt). " +
                        "Re-warm cache: ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark",
                )

        val manifest =
            try {
                parseScaPayloadManifest(dependencyCheckWarmManifest)
            } catch (e: GradleException) {
                throw GradleException(
                    "${e.message}. Re-warm cache: ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark",
                )
            }
        val actualPayload =
            collectScaPayloadActual(dependencyCheckDataDir, dependencyCheckWarmMarker, dependencyCheckWarmManifest)
        validateWarmManifestContractOrThrow(
            expected = manifest,
            actual = actualPayload,
            errorMessage =
                "scaCheck warm manifest does not match cache payload. " +
                    "Re-warm cache: ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark",
            emptyPayloadErrorMessage =
                "scaCheck warm manifest reports empty payload (marker-only/junk state). " +
                    "Re-warm cache: ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark",
        )

        val nowMillis = System.currentTimeMillis()
        val maxAgeMillis = scaCacheMaxAgeHours * 60L * 60L * 1000L
        if (nowMillis - warmedAt > maxAgeMillis) {
            throw GradleException(
                "scaCheck local cache is stale (older than ${scaCacheMaxAgeHours}h). " +
                    "Re-warm cache: ./gradlew --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark",
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
        val payloadEntries =
            collectScaPayloadEntries(dependencyCheckDataDir, dependencyCheckWarmMarker, dependencyCheckWarmManifest)

        if (payloadEntries.isEmpty()) {
            throw GradleException(
                "scaWarmCacheMark failed: dependencyCheckUpdate produced empty/invalid payload. " +
                    "Warm marker/manifest will not be updated.",
            )
        }

        val manifestText = serializeScaPayloadManifest(payloadEntries)
        val tempManifest =
            kotlin.io.path
                .createTempFile("sca-warm-manifest", ".tmp")
                .toFile()
        val parsedManifest =
            try {
                tempManifest.writeText(manifestText)
                parseScaPayloadManifest(tempManifest)
            } finally {
                tempManifest.delete()
            }

        validateWarmManifestContractOrThrow(
            expected = parsedManifest,
            actual =
                ScaPayloadActual(
                    entries = payloadEntries,
                    payloadFileCount = payloadEntries.size.toLong(),
                    payloadTotalBytes = payloadEntries.sumOf { it.size },
                    payloadDigest = aggregateScaPayloadDigest(payloadEntries),
                ),
            errorMessage =
                "scaWarmCacheMark failed: generated warm manifest contract is invalid. " +
                    "Warm marker/manifest will not be updated.",
        )

        dependencyCheckWarmMarker.writeText(
            "warmedAt=${System.currentTimeMillis()}\n" +
                "maxAgeHours=$scaCacheMaxAgeHours\n",
        )
        dependencyCheckWarmManifest.writeText(manifestText)
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
