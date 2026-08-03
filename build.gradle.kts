import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    alias(libs.plugins.versionsPlugin)
}

configure<KtlintExtension> {
    version.set("1.3.1")
    ignoreFailures.set(false)
    android.set(false)
    verbose.set(true)
    outputToConsole.set(true)
    baseline.set(file("config/ktlint/baseline-build-scripts.xml"))
    filter {
        include("**/*.kts")
        include("buildSrc/src/**/*.kt")
        exclude("build/**", "*/build/**", "tools/*/build/**")
    }
}

tasks.named<KtLintCheckTask>("runKtlintCheckOverKotlinScripts") {
    include("buildSrc/src/**/*.kt")
    setSource(
        fileTree(rootDir) {
            include("**/*.kts")
            exclude(".gradle/**", ".idea/**", "build/**", "*/build/**", "tools/*/build/**")
        },
    )
    source(
        fileTree(rootDir.resolve("buildSrc/src/main/kotlin")) {
            include("**/*.kt")
        },
    )
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
val dependencyGuardConfigurationNames =
    listOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
    )

abstract class DependencyGuard : DefaultTask() {
    @get:Inject
    protected abstract val providerFactory: ProviderFactory

    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Input
    val configurationNames: ListProperty<String> =
        objects.listProperty<String>()

    @get:Input
    val artifactCoordinates: ListProperty<String> =
        objects.listProperty<String>()

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
            val allArtifacts = artifactCoordinates.get().toSet()
            if (allArtifacts.isEmpty()) {
                throw GradleException(
                    "DependencyGuard $path: dependency resolution returned 0 artifacts",
                )
            }

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

            logger.lifecycle("DependencyGuard $path: OK (${allArtifacts.size} artifacts checked)")
        }
    }
}

abstract class ResolvedProjectDependencyGraph : DefaultTask() {
    @get:Input
    abstract val graphJson: Property<String>

    @get:Classpath
    abstract val runtimeArtifactFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun writeGraph() {
        val parsed =
            JsonSlurper().parseText(graphJson.get()) as? Map<*, *>
                ?: throw GradleException("Resolved project dependency graph must be a JSON object")
        if (parsed["projectPath"]?.toString().isNullOrBlank()) {
            throw GradleException("Resolved project dependency graph is missing projectPath")
        }
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".runtime-dependencies.", ".tmp")
        try {
            Files.writeString(
                temporary,
                JsonOutput.prettyPrint(JsonOutput.toJson(parsed)) + "\n",
                StandardCharsets.UTF_8,
            )
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

abstract class ResolvedProductionDependencyGraph : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectGraphFiles: ConfigurableFileCollection

    @get:Input
    abstract val expectedProjectPaths: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyAndWrite() {
        val parsedProjects =
            projectGraphFiles.files
                .map { graphFile ->
                    @Suppress("UNCHECKED_CAST")
                    JsonSlurper().parse(graphFile) as? Map<String, Any?>
                        ?: throw GradleException("Resolved production dependency graph entry must be a JSON object")
                }.sortedBy { entry -> entry["projectPath"].toString() }

        if (parsedProjects.isEmpty()) {
            throw GradleException("Resolved production dependency graph contains 0 JVM projects")
        }

        val projectPaths = parsedProjects.map { entry -> entry["projectPath"].toString() }
        if (projectPaths.any { it.isBlank() } || projectPaths.size != projectPaths.distinct().size) {
            throw GradleException(
                "Resolved production dependency graph contains invalid or duplicate JVM project paths",
            )
        }
        val expectedProjects = expectedProjectPaths.get().sorted()
        if (expectedProjects.isEmpty()) {
            throw GradleException("Resolved production dependency graph expects 0 JVM projects")
        }
        if (expectedProjects.size != expectedProjects.distinct().size) {
            throw GradleException(
                "Resolved production dependency graph has duplicate expected JVM projects: $expectedProjects",
            )
        }
        if (projectPaths != expectedProjects) {
            val missing = expectedProjects.toSet() - projectPaths.toSet()
            val unexpected = projectPaths.toSet() - expectedProjects.toSet()
            throw GradleException(
                "Resolved production dependency graph project inventory mismatch: " +
                    "missing=${missing.sorted()}, unexpected=${unexpected.sorted()}",
            )
        }

        fun dependencyList(
            entry: Map<String, Any?>,
            key: String,
        ): List<String> =
            (entry[key] as? List<*>)
                ?.map { value -> value as? String ?: throw GradleException("$key must contain strings") }
                ?.sorted()
                ?: throw GradleException("Resolved production dependency graph entry is missing $key")

        var totalDirect = 0
        var totalTransitive = 0
        var totalResolvedModules = 0
        var totalResolvedArtifacts = 0
        parsedProjects.forEach { entry ->
            val projectPath = entry["projectPath"].toString()
            val directDependencies = dependencyList(entry, "directDependencies")
            val resolvedModules = dependencyList(entry, "resolvedModules")
            val transitiveModules = dependencyList(entry, "transitiveModules")
            val resolvedArtifacts =
                (entry["resolvedArtifacts"] as? Number)?.toInt()
                    ?: throw GradleException(
                        "Resolved production dependency graph entry is missing resolvedArtifacts",
                    )
            if (directDependencies.isEmpty()) {
                throw GradleException("Resolved production dependency graph is empty for $projectPath")
            }
            if (resolvedModules.isEmpty()) {
                throw GradleException("Resolved production module graph is empty for $projectPath")
            }
            if (resolvedArtifacts <= 0) {
                throw GradleException("Resolved production artifact set is empty for $projectPath")
            }
            totalDirect += directDependencies.size
            totalTransitive += transitiveModules.size
            totalResolvedModules += resolvedModules.size
            totalResolvedArtifacts += resolvedArtifacts
        }

        if (
            totalDirect <= 0 ||
            totalTransitive <= 0 ||
            totalResolvedModules <= 0 ||
            totalResolvedArtifacts <= 0
        ) {
            throw GradleException(
                "Resolved production dependency graph must contain direct/transitive dependencies and artifacts",
            )
        }

        val report =
            linkedMapOf<String, Any>(
                "schema" to "clubs-bot/resolved-production-dependency-graph",
                "version" to 1,
                "projects" to parsedProjects,
                "totals" to
                    linkedMapOf(
                        "projects" to parsedProjects.size,
                        "directDependencies" to totalDirect,
                        "transitiveModules" to totalTransitive,
                        "resolvedModules" to totalResolvedModules,
                        "resolvedArtifacts" to totalResolvedArtifacts,
                    ),
            )
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".resolved-production-dependencies.", ".tmp")
        try {
            Files.writeString(
                temporary,
                JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n",
                StandardCharsets.UTF_8,
            )
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.lifecycle(
            "Resolved production dependency graph: OK " +
                "(${parsedProjects.size} JVM projects, $totalDirect direct, " +
                "$totalTransitive transitive, $totalResolvedModules resolved modules, " +
                "$totalResolvedArtifacts artifacts)",
        )
    }
}

class DetektSarifIdentity private constructor() {
    companion object {
        fun validateTaskName(taskName: String) {
            if (
                taskName.isEmpty() ||
                taskName == "." ||
                taskName == ".." ||
                taskName.contains("..") ||
                taskName.any { it == '/' || it == '\\' || it == ':' }
            ) {
                throw GradleException("Invalid Detekt task name: $taskName")
            }
        }

        fun modulePath(projectPath: String): String {
            if (projectPath == ":") return ""
            if (!projectPath.startsWith(":")) {
                throw GradleException("Invalid Gradle project path: $projectPath")
            }
            val parts = projectPath.removePrefix(":").split(':')
            if (
                parts.any {
                    it.isEmpty() ||
                        it == "." ||
                        it == ".." ||
                        it.contains("..") ||
                        it.any { character -> character == '/' || character == '\\' }
                }
            ) {
                throw GradleException("Invalid Gradle project path: $projectPath")
            }
            return parts.joinToString("/")
        }

        fun taskIdentity(taskName: String): String {
            validateTaskName(taskName)
            val detektPrefix = "detekt"
            val suffix = taskName.removePrefix(detektPrefix)
            return if (taskName.startsWith(detektPrefix) && suffix.isNotEmpty()) {
                suffix.replaceFirstChar { it.lowercase() }
            } else {
                taskName
            }
        }

        fun reportPath(
            projectPath: String,
            taskName: String,
        ): String {
            val modulePath = modulePath(projectPath)
            val prefix = if (modulePath.isEmpty()) "" else "$modulePath/"
            validateTaskName(taskName)
            return "${prefix}build/reports/detekt/$taskName/detekt.sarif"
        }

        fun statusPath(
            projectPath: String,
            taskName: String,
        ): String {
            val modulePath = modulePath(projectPath)
            val prefix = if (modulePath.isEmpty()) "" else "$modulePath/"
            validateTaskName(taskName)
            return "${prefix}build/reports/detekt/status/$taskName.json"
        }

        fun category(
            projectPath: String,
            taskName: String,
        ): String {
            val modulePath = modulePath(projectPath).ifEmpty { "root" }
            return "detekt/$modulePath/${taskIdentity(taskName)}"
        }

        fun taskPath(
            projectPath: String,
            taskName: String,
        ): String = if (projectPath == ":") ":$taskName" else "$projectPath:$taskName"

        fun validateNoSymlinkComponents(
            repositoryRoot: Path,
            target: Path,
            description: String,
            allowMissing: Boolean,
        ) {
            val normalizedRoot = repositoryRoot.toAbsolutePath().normalize()
            val normalizedTarget = target.toAbsolutePath().normalize()
            if (!normalizedTarget.startsWith(normalizedRoot)) {
                throw GradleException("$description is outside the repository: $normalizedTarget")
            }

            var current = normalizedRoot
            val relativeParts = normalizedRoot.relativize(normalizedTarget).toList()
            relativeParts.forEachIndexed { index, part ->
                current = current.resolve(part)
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (allowMissing) return
                    throw GradleException("$description is missing: $normalizedTarget")
                }
                if (Files.isSymbolicLink(current)) {
                    throw GradleException("$description contains a symbolic link: $current")
                }
                if (
                    index < relativeParts.lastIndex &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw GradleException("$description parent is not a directory: $current")
                }
            }
        }
    }
}

abstract class PrepareDetektSarif : DefaultTask() {
    @get:Internal
    abstract val reportDirectories: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val preparedMarker: RegularFileProperty

    @TaskAction
    fun prepare() {
        val rootPath =
            repositoryRoot
                .get()
                .asFile
                .toPath()
                .toAbsolutePath()
                .normalize()

        fun deleteGeneratedReports(reportDirectory: File) {
            val reportPath = reportDirectory.toPath().toAbsolutePath().normalize()
            if (!reportPath.startsWith(rootPath)) {
                throw GradleException(
                    "Refusing to remove Detekt reports outside the repository: $reportPath",
                )
            }
            val relativeParts = rootPath.relativize(reportPath).map { it.toString() }
            if (relativeParts.takeLast(3) != listOf("build", "reports", "detekt")) {
                throw GradleException(
                    "Refusing to remove an unapproved Detekt report directory: $reportPath",
                )
            }

            DetektSarifIdentity.validateNoSymlinkComponents(
                rootPath,
                reportPath,
                "Detekt report cleanup path",
                allowMissing = true,
            )

            if (!Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS)) return
            if (!Files.isDirectory(reportPath, LinkOption.NOFOLLOW_LINKS)) {
                throw GradleException("Detekt report path is not a directory: $reportPath")
            }
            Files.walkFileTree(
                reportPath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        directory: Path,
                        error: java.io.IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        val rootReportDirectory =
            preparedMarker
                .get()
                .asFile
                .parentFile
        deleteGeneratedReports(rootReportDirectory)
        reportDirectories.files
            .map { it.toPath().toAbsolutePath().normalize() }
            .filterNot { it == rootReportDirectory.toPath().toAbsolutePath().normalize() }
            .sortedBy { it.toString() }
            .map(Path::toFile)
            .forEach(::deleteGeneratedReports)

        val marker =
            linkedMapOf<String, Any>(
                "schema" to "clubs-bot/detekt-sarif-prepared",
                "version" to 1,
            )
        val target = preparedMarker.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".prepared-sarif.", ".tmp")
        try {
            val content = JsonOutput.prettyPrint(JsonOutput.toJson(marker)) + "\n"
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.lifecycle("Detekt SARIF workspace prepared")
    }
}

abstract class RecordDetektSarifStatus : DefaultTask() {
    @get:Input
    abstract val taskPathValue: Property<String>

    @get:Input
    abstract val projectPathValue: Property<String>

    @get:Input
    abstract val taskNameValue: Property<String>

    @get:Input
    abstract val expectedReportPath: Property<String>

    @get:Input
    abstract val expectedCategory: Property<String>

    @get:Internal
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val reportFile: RegularFileProperty

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val statusFile: RegularFileProperty

    @get:Internal
    lateinit var analyzedTask: Task

    @TaskAction
    fun record() {
        val projectPath = projectPathValue.get()
        val taskName = taskNameValue.get()
        val taskPath = taskPathValue.get()
        val reportPath = expectedReportPath.get()
        val category = expectedCategory.get()
        if (taskPath != DetektSarifIdentity.taskPath(projectPath, taskName)) {
            throw GradleException("Detekt status task identity is inconsistent: $taskPath")
        }
        if (reportPath != DetektSarifIdentity.reportPath(projectPath, taskName)) {
            throw GradleException("Detekt status report path is inconsistent: $taskPath")
        }
        if (category != DetektSarifIdentity.category(projectPath, taskName)) {
            throw GradleException("Detekt status category is inconsistent: $taskPath")
        }

        val rootPath =
            repositoryRoot
                .get()
                .asFile
                .toPath()
                .toAbsolutePath()
                .normalize()
        val actualReportPath =
            reportFile
                .get()
                .asFile
                .toPath()
                .toAbsolutePath()
                .normalize()
        if (!actualReportPath.startsWith(rootPath)) {
            throw GradleException("Detekt SARIF output is outside the repository: $taskPath")
        }
        val actualRelativeReport = rootPath.relativize(actualReportPath).joinToString("/")
        if (actualRelativeReport != reportPath) {
            throw GradleException("Detekt SARIF output does not match task identity: $taskPath")
        }

        val sourceCount =
            sourceFiles.files
                .asSequence()
                .filter { it.isFile }
                .map { it.canonicalPath }
                .distinct()
                .count()
        val taskState = analyzedTask.state
        val taskSkipMessage = taskState.skipMessage.orEmpty()
        val reportExists = reportFile.get().asFile.isFile
        val validIncrementalSkip =
            taskState.skipped &&
                (
                    taskSkipMessage == "FROM-CACHE" ||
                        (taskSkipMessage == "UP-TO-DATE" && reportExists)
                )
        val state =
            when {
                taskState.executed &&
                    taskState.skipped &&
                    taskState.noSource &&
                    taskState.failure == null &&
                    sourceCount == 0 -> "NO_SOURCE"
                taskState.executed &&
                    !taskState.noSource &&
                    sourceCount > 0 &&
                    (!taskState.skipped || validIncrementalSkip) -> "REPORT_REQUIRED"
                else -> "INCOMPLETE"
            }
        val status =
            linkedMapOf<String, Any>(
                "schema" to "clubs-bot/detekt-sarif-status",
                "version" to 1,
                "taskPath" to taskPath,
                "projectPath" to projectPath,
                "taskName" to taskName,
                "reportPath" to reportPath,
                "category" to category,
                "sourceCount" to sourceCount,
                "state" to state,
                "taskExecuted" to taskState.executed,
                "taskSkipped" to taskState.skipped,
                "taskSkipMessage" to taskSkipMessage,
                "taskNoSource" to taskState.noSource,
                "taskFailed" to (taskState.failure != null),
                "reportExists" to reportExists,
            )
        val target = statusFile.get().asFile.toPath()
        DetektSarifIdentity.validateNoSymlinkComponents(
            rootPath,
            target,
            "Detekt status output",
            allowMissing = true,
        )
        Files.createDirectories(target.parent)
        DetektSarifIdentity.validateNoSymlinkComponents(
            rootPath,
            target.parent,
            "Detekt status output parent",
            allowMissing = false,
        )
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            val content = JsonOutput.prettyPrint(JsonOutput.toJson(status)) + "\n"
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.lifecycle("Detekt SARIF status $taskPath: $state ($sourceCount sources)")
    }
}

abstract class FinalizeDetektSarifManifest : DefaultTask() {
    @get:Input
    abstract val expectedTaskDescriptors: ListProperty<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Internal
    abstract val preparedMarker: RegularFileProperty

    @get:Internal
    abstract val statusDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun finalizeManifest() {
        val rootPath =
            repositoryRoot
                .get()
                .asFile
                .toPath()
                .toAbsolutePath()
                .normalize()
        val manifestTarget =
            manifestFile
                .get()
                .asFile
                .toPath()
                .toAbsolutePath()
                .normalize()
        val expectedManifestTarget = rootPath.resolve("build/reports/detekt/expected-sarif.json")
        if (manifestTarget != expectedManifestTarget) {
            throw GradleException("Unexpected Detekt SARIF manifest output: $manifestTarget")
        }
        DetektSarifIdentity.validateNoSymlinkComponents(
            rootPath,
            manifestTarget,
            "Detekt SARIF manifest output",
            allowMissing = true,
        )
        if (
            Files.exists(manifestTarget, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(manifestTarget, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw GradleException("Detekt SARIF manifest output must be a regular file")
        }
        Files.deleteIfExists(manifestTarget)

        val markerFile = preparedMarker.get().asFile
        DetektSarifIdentity.validateNoSymlinkComponents(
            rootPath,
            markerFile.toPath(),
            "Detekt SARIF preparation marker",
            allowMissing = false,
        )
        if (!Files.isRegularFile(markerFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw GradleException("Detekt SARIF preparation marker must be a regular file")
        }
        val marker =
            try {
                JsonSlurper().parse(markerFile)
            } catch (error: Exception) {
                throw GradleException("Detekt SARIF preparation marker is missing or invalid", error)
            }
        if (
            marker !is Map<*, *> ||
            marker.keys != setOf("schema", "version") ||
            marker["schema"] != "clubs-bot/detekt-sarif-prepared" ||
            marker["version"] != 1
        ) {
            throw GradleException("Detekt SARIF preparation marker contract changed")
        }

        val descriptors =
            expectedTaskDescriptors.get().map { encodedDescriptor ->
                val raw =
                    try {
                        JsonSlurper().parseText(encodedDescriptor)
                    } catch (error: Exception) {
                        throw GradleException("Invalid Detekt task descriptor", error)
                    }
                if (raw !is Map<*, *>) {
                    throw GradleException("Detekt task descriptor must be an object")
                }
                raw
            }
        if (descriptors.isEmpty()) {
            throw GradleException("No enabled non-aggregate Detekt tasks found")
        }
        val sortedDescriptors = descriptors.sortedBy { it["taskPath"].toString() }
        val expectedStatusPaths = sortedDescriptors.map { it["statusPath"].toString() }
        val actualStatusPaths =
            statusDirectories.files
                .asSequence()
                .filter { it.isDirectory }
                .flatMap { directory ->
                    directory
                        .listFiles()
                        .orEmpty()
                        .asSequence()
                        .filter { it.isFile && it.extension == "json" }
                }.map { statusFile ->
                    val statusPath = statusFile.toPath().toAbsolutePath().normalize()
                    if (!statusPath.startsWith(rootPath)) {
                        throw GradleException("Detekt status is outside the repository: $statusFile")
                    }
                    rootPath.relativize(statusPath).joinToString("/")
                }.sorted()
                .toList()
        if (actualStatusPaths != expectedStatusPaths.sorted()) {
            throw GradleException(
                "Detekt status inventory is incomplete or unexpected: " +
                    "expected=${expectedStatusPaths.sorted()} actual=$actualStatusPaths",
            )
        }

        val entries =
            sortedDescriptors.mapNotNull { descriptor ->
                val requiredKeys =
                    setOf(
                        "taskPath",
                        "projectPath",
                        "taskName",
                        "reportPath",
                        "category",
                        "statusPath",
                    )
                if (descriptor.keys != requiredKeys) {
                    throw GradleException("Detekt task descriptor fields changed")
                }

                fun descriptorString(key: String): String =
                    descriptor[key] as? String
                        ?: throw GradleException("Detekt task descriptor $key is invalid")

                val taskPath = descriptorString("taskPath")
                val projectPath = descriptorString("projectPath")
                val taskName = descriptorString("taskName")
                val reportPath = descriptorString("reportPath")
                val category = descriptorString("category")
                val statusPath = descriptorString("statusPath")
                if (
                    taskPath != DetektSarifIdentity.taskPath(projectPath, taskName) ||
                    reportPath != DetektSarifIdentity.reportPath(projectPath, taskName) ||
                    category != DetektSarifIdentity.category(projectPath, taskName) ||
                    statusPath != DetektSarifIdentity.statusPath(projectPath, taskName)
                ) {
                    throw GradleException("Detekt task descriptor identity is inconsistent: $taskPath")
                }

                val statusFile = rootPath.resolve(statusPath).toFile()
                DetektSarifIdentity.validateNoSymlinkComponents(
                    rootPath,
                    statusFile.toPath(),
                    "Detekt task status",
                    allowMissing = false,
                )
                if (!Files.isRegularFile(statusFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw GradleException("Detekt task status must be a regular file: $taskPath")
                }
                val status =
                    try {
                        JsonSlurper().parse(statusFile)
                    } catch (error: Exception) {
                        throw GradleException("Missing or invalid Detekt status: $taskPath", error)
                    }
                val statusKeys =
                    setOf(
                        "schema",
                        "version",
                        "taskPath",
                        "projectPath",
                        "taskName",
                        "reportPath",
                        "category",
                        "sourceCount",
                        "state",
                        "taskExecuted",
                        "taskSkipped",
                        "taskSkipMessage",
                        "taskNoSource",
                        "taskFailed",
                        "reportExists",
                    )
                if (status !is Map<*, *> || status.keys != statusKeys) {
                    throw GradleException("Detekt status fields changed: $taskPath")
                }
                for (key in listOf("taskPath", "projectPath", "taskName", "reportPath", "category")) {
                    if (status[key] != descriptor[key]) {
                        throw GradleException("Detekt status $key is inconsistent: $taskPath")
                    }
                }
                if (
                    status["schema"] != "clubs-bot/detekt-sarif-status" ||
                    status["version"] != 1
                ) {
                    throw GradleException("Detekt status schema changed: $taskPath")
                }
                val sourceCount =
                    (status["sourceCount"] as? Number)
                        ?.toInt()
                        ?: throw GradleException("Detekt status sourceCount is invalid: $taskPath")
                val state =
                    status["state"] as? String
                        ?: throw GradleException("Detekt status state is invalid: $taskPath")

                fun statusBoolean(key: String): Boolean =
                    status[key] as? Boolean
                        ?: throw GradleException("Detekt status $key is invalid: $taskPath")
                val taskExecuted = statusBoolean("taskExecuted")
                val taskSkipped = statusBoolean("taskSkipped")
                val taskNoSource = statusBoolean("taskNoSource")
                val taskFailed = statusBoolean("taskFailed")
                val reportExists = statusBoolean("reportExists")
                val taskSkipMessage =
                    status["taskSkipMessage"] as? String
                        ?: throw GradleException("Detekt status taskSkipMessage is invalid: $taskPath")
                val reportFile = rootPath.resolve(reportPath).toFile()
                when (state) {
                    "NO_SOURCE" -> {
                        if (
                            sourceCount != 0 ||
                            !taskExecuted ||
                            !taskSkipped ||
                            !taskNoSource ||
                            taskFailed ||
                            reportExists ||
                            reportFile.exists()
                        ) {
                            throw GradleException("Inconsistent NO_SOURCE Detekt status: $taskPath")
                        }
                        null
                    }

                    "REPORT_REQUIRED" -> {
                        val validIncrementalSkip =
                            taskSkipped &&
                                (
                                    taskSkipMessage == "FROM-CACHE" ||
                                        (taskSkipMessage == "UP-TO-DATE" && reportExists)
                                )
                        if (
                            sourceCount <= 0 ||
                            !taskExecuted ||
                            taskNoSource ||
                            (taskSkipped && !validIncrementalSkip)
                        ) {
                            throw GradleException("Detekt sourceCount must be positive: $taskPath")
                        }
                        if (!reportFile.isFile || !reportExists) {
                            throw GradleException("Required Detekt SARIF report is missing: $taskPath")
                        }
                        linkedMapOf<String, Any>(
                            "taskPath" to taskPath,
                            "projectPath" to projectPath,
                            "taskName" to taskName,
                            "reportPath" to reportPath,
                            "category" to category,
                            "sourceCount" to sourceCount,
                        )
                    }

                    else -> throw GradleException("Incomplete Detekt execution status: $taskPath")
                }
            }
        if (entries.isEmpty()) {
            throw GradleException("No Detekt SARIF reports are required")
        }
        for (key in listOf("taskPath", "reportPath", "category")) {
            val values = entries.map { it.getValue(key) }
            if (values.size != values.distinct().size) {
                throw GradleException("Detekt SARIF manifest contains duplicate $key values")
            }
        }
        val taskPaths = entries.map { it.getValue("taskPath").toString() }
        if (taskPaths != taskPaths.sorted()) {
            throw GradleException("Detekt SARIF manifest entries are not taskPath-sorted")
        }

        val manifest =
            linkedMapOf<String, Any>(
                "schema" to "clubs-bot/detekt-sarif-manifest",
                "version" to 2,
                "entries" to entries,
            )
        Files.createDirectories(manifestTarget.parent)
        DetektSarifIdentity.validateNoSymlinkComponents(
            rootPath,
            manifestTarget.parent,
            "Detekt SARIF manifest parent",
            allowMissing = false,
        )
        val temporary = Files.createTempFile(manifestTarget.parent, ".expected-sarif.", ".tmp")
        try {
            val content = JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n"
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                manifestTarget,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.lifecycle("Detekt SARIF manifest finalized with ${entries.size} required reports")
    }
}

val rootDependencyGuard =
    tasks.register("dependencyGuard") {
        group = "verification"
        description = "Run dependency policy checks for all JVM subprojects"
    }

val resolvedProductionDependencyGraph =
    tasks.register<ResolvedProductionDependencyGraph>("verifyResolvedProductionDependencyGraph") {
        group = "verification"
        description = "Resolve and verify non-empty direct/transitive runtime dependency graphs for every JVM project"
        expectedProjectPaths.convention(emptyList())
        reportFile.set(
            layout.buildDirectory.file("reports/dependencies/resolved-production-dependencies.json"),
        )
        notCompatibleWithConfigurationCache(
            "The verification task resolves every JVM runtimeClasspath during execution",
        )
    }

// -------------------------
// Настройки подмодулей
// -------------------------
val prepareDetektSarif =
    tasks.register<PrepareDetektSarif>("prepareDetektSarif") {
        group = "verification"
        description = "Remove only managed Detekt SARIF lifecycle outputs before analysis"
        repositoryRoot.set(layout.projectDirectory)
        reportDirectories.from(
            (listOf(rootProject) + subprojects).map { candidateProject ->
                val modulePath = DetektSarifIdentity.modulePath(candidateProject.path)
                val prefix = if (modulePath.isEmpty()) "" else "$modulePath/"
                rootProject.layout.projectDirectory.dir("${prefix}build/reports/detekt")
            },
        )
        preparedMarker.set(
            layout.projectDirectory.file("build/reports/detekt/prepared.json"),
        )
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache(
            "The managed Detekt report workspace must be recreated for every analysis run",
        )
    }

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
    val moduleKtlintBaseline =
        rootProject.file(
            "config/ktlint/baseline-${project.path.removePrefix(":").replace(':', '-')}.xml",
        )

    configure<KtlintExtension> {
        version.set("1.3.1")
        ignoreFailures.set(false)
        android.set(false)
        verbose.set(true)
        outputToConsole.set(true)
        baseline.set(moduleKtlintBaseline)
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
            val sarifReportPath = DetektSarifIdentity.reportPath(project.path, name)
            val taskReportDirectory = sarifReportPath.removeSuffix("/detekt.sarif")
            html.outputLocation.set(
                rootProject.layout.projectDirectory.file("$taskReportDirectory/detekt.html"),
            )
            sarif.outputLocation.set(
                rootProject.layout.projectDirectory.file(sarifReportPath),
            )
            txt.required.set(true)
            txt.outputLocation.set(
                rootProject.layout.projectDirectory.file("$taskReportDirectory/detekt.txt"),
            )
        }

        if (name != "detekt") {
            DetektSarifIdentity.validateTaskName(name)
        }
    }

    tasks.withType<Detekt>().all(
        Action<Detekt> {
            val analysisTask = this
            if (analysisTask.name != "detekt") {
                DetektSarifIdentity.validateTaskName(analysisTask.name)
                val statusTaskName =
                    "record${analysisTask.name.replaceFirstChar { it.uppercase() }}SarifStatus"
                val statusTask =
                    tasks.register<RecordDetektSarifStatus>(statusTaskName) {
                        group = "verification"
                        description = "Record post-execution Detekt SARIF status for ${analysisTask.path}"
                        taskPathValue.set(analysisTask.path)
                        projectPathValue.set(project.path)
                        taskNameValue.set(analysisTask.name)
                        expectedReportPath.set(
                            DetektSarifIdentity.reportPath(project.path, analysisTask.name),
                        )
                        expectedCategory.set(
                            DetektSarifIdentity.category(project.path, analysisTask.name),
                        )
                        sourceFiles.from(analysisTask.source)
                        reportFile.set(analysisTask.reports.sarif.outputLocation)
                        repositoryRoot.set(rootProject.layout.projectDirectory)
                        statusFile.set(
                            rootProject.layout.projectDirectory.file(
                                DetektSarifIdentity.statusPath(project.path, analysisTask.name),
                            ),
                        )
                        analyzedTask = analysisTask
                        mustRunAfter(analysisTask)
                        outputs.upToDateWhen { false }
                        notCompatibleWithConfigurationCache(
                            "Records the finalized TaskState and execution-time Detekt source inventory",
                        )
                    }
                analysisTask.finalizedBy(statusTask)
            }
        },
    )

    // CLI-обёртки (если есть соответствующие файлы в репо)
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // The aggregate `detekt` task mixes main and test sources, so it cannot
        // apply their separate baselines correctly. Keep `check` blocking via
        // the source-set tasks used by detektGate.
        val sourceSetDetektTasks =
            tasks.withType<Detekt>().matching { detektTask ->
                detektTask.name != "detekt" && detektTask.enabled
            }
        tasks.named<Detekt>("detekt") {
            enabled = false
            dependsOn(sourceSetDetektTasks)
        }
        tasks.named("check") {
            dependsOn(sourceSetDetektTasks)
        }

        val guardedConfigurations =
            dependencyGuardConfigurationNames.map { configurationName ->
                configurations.named(configurationName)
            }
        val moduleDependencyGuard =
            tasks.register<DependencyGuard>("dependencyGuard") {
                group = "verification"
                description = "Fail build if dependency rules are violated in $path"
                configurationNames.set(dependencyGuardConfigurationNames)
                artifactCoordinates.set(
                    providers.provider {
                        guardedConfigurations
                            .flatMap { configurationProvider ->
                                configurationProvider
                                    .get()
                                    .incoming
                                    .resolutionResult
                                    .allComponents
                                    .mapNotNull { component ->
                                        val id = component.id as? ModuleComponentIdentifier
                                        id?.let { "${it.group}:${it.module}:${it.version}" }
                                    }
                            }.distinct()
                            .sorted()
                    },
                )
            }
        rootDependencyGuard.configure {
            dependsOn(moduleDependencyGuard)
        }

        apply(from = rootProject.file("gradle/detekt-cli.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint-cli.gradle.kts"))
    }

    pluginManager.withPlugin("java") {
        val jvmProjectPath = project.path
        val runtimeClasspath = configurations.named("runtimeClasspath")
        val resolvedProjectDependencyGraph =
            tasks.register<ResolvedProjectDependencyGraph>("verifyResolvedProductionDependencyGraph") {
                group = "verification"
                description = "Resolve the runtime dependency graph for ${project.path}"
                runtimeArtifactFiles.from(runtimeClasspath)
                graphJson.set(
                    providers.provider {
                        val runtimeConfiguration = runtimeClasspath.get()
                        val resolutionResult = runtimeConfiguration.incoming.resolutionResult
                        val unresolvedDependencies =
                            resolutionResult.allDependencies
                                .filterIsInstance<UnresolvedDependencyResult>()
                                .map { dependency -> dependency.attempted.displayName }
                                .distinct()
                                .sorted()
                        if (unresolvedDependencies.isNotEmpty()) {
                            throw GradleException(
                                "Unresolved runtime dependencies for ${project.path}: " +
                                    unresolvedDependencies.joinToString(),
                            )
                        }
                        val directComponentIds =
                            resolutionResult.root.dependencies
                                .filterIsInstance<ResolvedDependencyResult>()
                                .map { dependency -> dependency.selected.id }
                        val directDependencies =
                            directComponentIds
                                .map { componentId ->
                                    when (componentId) {
                                        is ModuleComponentIdentifier ->
                                            "module:${componentId.group}:${componentId.module}:${componentId.version}"

                                        is ProjectComponentIdentifier -> "project:${componentId.projectPath}"
                                        else -> "component:${componentId.displayName}"
                                    }
                                }.distinct()
                                .sorted()
                        val directModules =
                            directComponentIds
                                .filterIsInstance<ModuleComponentIdentifier>()
                                .map { componentId ->
                                    "${componentId.group}:${componentId.module}:${componentId.version}"
                                }.toSet()
                        val resolvedModules =
                            resolutionResult.allComponents
                                .mapNotNull { component ->
                                    val componentId = component.id as? ModuleComponentIdentifier
                                    componentId?.let {
                                        "${it.group}:${it.module}:${it.version}"
                                    }
                                }.distinct()
                                .sorted()
                        val resolvedArtifactFiles =
                            runtimeArtifactFiles.files
                                .sortedBy { artifact -> artifact.absolutePath }
                        val missingArtifactFiles =
                            resolvedArtifactFiles.filterNot { artifact -> artifact.isFile }
                        if (resolvedArtifactFiles.isEmpty()) {
                            throw GradleException(
                                "Resolved runtime artifact set is empty for ${project.path}",
                            )
                        }
                        if (missingArtifactFiles.isNotEmpty()) {
                            throw GradleException(
                                "Resolved runtime artifacts are missing for ${project.path}: " +
                                    missingArtifactFiles.joinToString { artifact -> artifact.name },
                            )
                        }
                        JsonOutput.toJson(
                            linkedMapOf(
                                "projectPath" to project.path,
                                "configuration" to "runtimeClasspath",
                                "directDependencies" to directDependencies,
                                "resolvedModules" to resolvedModules,
                                "transitiveModules" to resolvedModules.filterNot(directModules::contains),
                                "resolvedArtifacts" to resolvedArtifactFiles.size,
                            ),
                        )
                    },
                )
                reportFile.set(
                    layout.buildDirectory.file("reports/dependencies/runtime-dependencies.json"),
                )
                notCompatibleWithConfigurationCache(
                    "The verification task resolves this project's runtimeClasspath during execution",
                )
            }
        resolvedProductionDependencyGraph.configure {
            dependsOn(resolvedProjectDependencyGraph)
            projectGraphFiles.from(resolvedProjectDependencyGraph.flatMap { it.reportFile })
            expectedProjectPaths.add(jvmProjectPath)
        }
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

tasks.register<FinalizeDetektSarifManifest>("finalizeDetektSarifManifest") {
    group = "verification"
    description = "Finalize the complete expected Detekt SARIF manifest from execution statuses"
    expectedTaskDescriptors.set(
        providers.provider {
            (listOf(rootProject) + subprojects)
                .flatMap { candidateProject ->
                    val modulePath = DetektSarifIdentity.modulePath(candidateProject.path)
                    val expectedProjectDirectory =
                        if (modulePath.isEmpty()) rootDir else rootDir.resolve(modulePath)
                    if (candidateProject.projectDir.canonicalFile != expectedProjectDirectory.canonicalFile) {
                        throw GradleException(
                            "Detekt project directory does not match its Gradle path: ${candidateProject.path}",
                        )
                    }

                    candidateProject.tasks
                        .withType<Detekt>()
                        .matching { detektTask -> detektTask.name != "detekt" && detektTask.enabled }
                        .map { detektTask ->
                            val reportPath =
                                DetektSarifIdentity.reportPath(candidateProject.path, detektTask.name)
                            val expectedReportFile =
                                rootDir
                                    .resolve(reportPath)
                                    .toPath()
                                    .toAbsolutePath()
                                    .normalize()
                            val configuredReportFile =
                                detektTask.reports.sarif.outputLocation
                                    .get()
                                    .asFile
                                    .toPath()
                                    .toAbsolutePath()
                                    .normalize()
                            val sarifRequired =
                                detektTask.reports.sarif.required
                                    .get()
                            if (!sarifRequired) {
                                throw GradleException("Detekt SARIF is not required: ${detektTask.path}")
                            }
                            if (configuredReportFile != expectedReportFile) {
                                throw GradleException(
                                    "Detekt SARIF path does not match task identity: ${detektTask.path}",
                                )
                            }
                            JsonOutput.toJson(
                                linkedMapOf(
                                    "taskPath" to detektTask.path,
                                    "projectPath" to candidateProject.path,
                                    "taskName" to detektTask.name,
                                    "reportPath" to reportPath,
                                    "category" to
                                        DetektSarifIdentity.category(
                                            candidateProject.path,
                                            detektTask.name,
                                        ),
                                    "statusPath" to
                                        DetektSarifIdentity.statusPath(
                                            candidateProject.path,
                                            detektTask.name,
                                        ),
                                ),
                            )
                        }
                }.sorted()
        },
    )
    repositoryRoot.set(layout.projectDirectory)
    preparedMarker.set(
        layout.projectDirectory.file("build/reports/detekt/prepared.json"),
    )
    statusDirectories.from(
        (listOf(rootProject) + subprojects).map { candidateProject ->
            val modulePath = DetektSarifIdentity.modulePath(candidateProject.path)
            val prefix = if (modulePath.isEmpty()) "" else "$modulePath/"
            layout.projectDirectory.dir("${prefix}build/reports/detekt/status")
        },
    )
    manifestFile.set(
        layout.projectDirectory.file("build/reports/detekt/expected-sarif.json"),
    )
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache(
        "Finalization validates post-execution task status markers from a separate Gradle invocation",
    )
}

tasks.named("ktlintCheck") {
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("ktlintCheck") },
    )
}

// Удобные агрегирующие команды (плагинные таски)
tasks.register("staticCheck") {
    group = "verification"
    description = "Run detekt and ktlint (plugin tasks) across all Kotlin modules"
    dependsOn("detektGate", "ktlintCheck")
}

tasks.register("detektGate") {
    group = "verification"
    description = "Run detekt across all Kotlin subprojects with baseline-aware strategy"
    dependsOn(
        subprojects.map { candidateProject ->
            candidateProject.tasks.withType<Detekt>().matching {
                name != "detekt" && enabled
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
