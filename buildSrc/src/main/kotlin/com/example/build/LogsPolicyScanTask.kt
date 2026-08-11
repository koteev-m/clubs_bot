package com.example.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ProcessExecutionException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject

private const val MATCH_FOUND_EXIT_CODE = 0
private const val NO_MATCH_EXIT_CODE = 1

private data class LogsPolicyScanResult(
    val exitValue: Int,
    val output: String,
    val engine: String,
)

/**
 * CC-friendly реализация: не обращается к `project` в @TaskAction,
 * использует ExecOperations/ProjectLayout, объявляет inputs/outputs.
 */
@CacheableTask
abstract class LogsPolicyScanTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
        layout: ProjectLayout,
        objects: ObjectFactory,
    ) : DefaultTask() {
        /** Где искать (как правило — директория модуля). */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val sourceDirs: ConfigurableFileCollection = objects.fileCollection().from(layout.projectDirectory)

        /** Какие globs включать в проверку. */
        @get:Input
        val includeGlobs: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())

        /** Какие globs исключать из проверки. */
        @get:Input
        val excludeGlobs: ListProperty<String> =
            objects.listProperty(String::class.java).convention(
                listOf(
                    "**/build/**",
                    "**/.gradle/**",
                    "**/.idea/**",
                    "**/.git/**",
                    "**/*.iml",
                    "**/src/test/**",
                    "**/test/**",
                    "**/fixtures/**",
                    "**/resources/**",
                ),
            )

        /** Регулярки для проверки (должны поддерживаться PCRE2 и java.util.regex). */
        @get:Input
        val patterns: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())

        /** Путь к бинарю rg (по умолчанию просто 'rg' из PATH). */
        @get:Input
        val ripgrepExecutable: Property<String> =
            objects.property(String::class.java).convention("rg")

        /** Куда сложить отчёт по результатам. */
        @get:OutputFile
        val reportFile: RegularFileProperty =
            objects.fileProperty().convention(layout.buildDirectory.file("reports/logs-policy/scan.txt"))

        @TaskAction
        fun run() {
            val configuredPatterns = patterns.get()
            val compiledPatterns = compilePatterns(configuredPatterns)
            val candidateFiles = collectCandidateFiles()
            val result =
                if (candidateFiles.isEmpty()) {
                    LogsPolicyScanResult(
                        exitValue = NO_MATCH_EXIT_CODE,
                        output = "",
                        engine = "shared file selector",
                    )
                } else {
                    try {
                        runRipgrep(candidateFiles, configuredPatterns)
                    } catch (_: ProcessExecutionException) {
                        logger.lifecycle(
                            "SEC-02: ripgrep '{}' could not be started; using repository-native JVM fallback.",
                            ripgrepExecutable.get(),
                        )
                        runJvmFallback(candidateFiles, compiledPatterns)
                    }
                }

            val out = reportFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText(result.output, Charsets.UTF_8)

            when (result.exitValue) {
                MATCH_FOUND_EXIT_CODE -> {
                    logger.error("SEC-02: обнаружены совпадения. См. отчёт: {}", out)
                    throw GradleException("Logs policy check failed. See $out")
                }
                NO_MATCH_EXIT_CODE -> {
                    logger.lifecycle("SEC-02: совпадений не найдено ({} exit=1).", result.engine)
                }
                else -> {
                    logger.error("SEC-02: ripgrep завершился с кодом {}. См. {}", result.exitValue, out)
                    throw GradleException("ripgrep failed with exit code ${result.exitValue}. See $out")
                }
            }
        }

        private fun collectCandidateFiles(): List<File> {
            val missingSources = sourceDirs.files.filterNot { it.exists() }
            if (missingSources.isNotEmpty()) {
                throw GradleException(
                    "Logs policy source paths do not exist: " +
                        missingSources.joinToString { it.absolutePath },
                )
            }

            return sourceDirs.asFileTree
                .matching {
                    includeGlobs.get().forEach { include(it) }
                    excludeGlobs.get().forEach { exclude(it) }
                }.files
                .asSequence()
                .filter { it.isFile }
                .distinctBy { it.absoluteFile.normalize().path }
                .sortedBy { it.absolutePath }
                .toList()
        }

        private fun compilePatterns(configuredPatterns: List<String>): List<Pattern> {
            if (configuredPatterns.isEmpty()) {
                throw GradleException("Logs policy scan requires at least one pattern")
            }

            return try {
                configuredPatterns.map {
                    Pattern.compile(it, Pattern.UNICODE_CHARACTER_CLASS)
                }
            } catch (failure: PatternSyntaxException) {
                throw GradleException("Logs policy pattern is not supported by the JVM fallback", failure)
            }
        }

        private fun runRipgrep(
            candidateFiles: List<File>,
            configuredPatterns: List<String>,
        ): LogsPolicyScanResult {
            val args = mutableListOf("-n", "--hidden", "-P")
            configuredPatterns.forEach { args += listOf("-e", it) }
            candidateFiles.forEach { args += it.absolutePath }

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val result =
                execOps.exec {
                    executable = ripgrepExecutable.get()
                    args(args)
                    isIgnoreExitValue = true // rg: 0=есть совпадения, 1=совпадений нет, >1=ошибка
                    standardOutput = stdout
                    errorOutput = stderr
                }
            val output =
                buildString {
                    append(stdout.toString(Charsets.UTF_8))
                    val err = stderr.toString(Charsets.UTF_8)
                    if (err.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(err)
                    }
                }
            return LogsPolicyScanResult(
                exitValue = result.exitValue,
                output = output,
                engine = "rg",
            )
        }

        private fun runJvmFallback(
            candidateFiles: List<File>,
            compiledPatterns: List<Pattern>,
        ): LogsPolicyScanResult {
            val findings = mutableListOf<String>()
            try {
                candidateFiles.forEach { file ->
                    Files
                        .newBufferedReader(file.toPath(), StandardCharsets.UTF_8)
                        .use { reader ->
                            reader.lineSequence().forEachIndexed { index, line ->
                                if (compiledPatterns.any { it.matcher(line).find() }) {
                                    findings += "${file.absolutePath}:${index + 1}:$line"
                                }
                            }
                        }
                }
            } catch (failure: IOException) {
                throw GradleException("JVM logs policy fallback could not read a source file", failure)
            }

            return LogsPolicyScanResult(
                exitValue = if (findings.isEmpty()) NO_MATCH_EXIT_CODE else MATCH_FOUND_EXIT_CODE,
                output = findings.joinToString(separator = "\n", postfix = if (findings.isEmpty()) "" else "\n"),
                engine = "JVM fallback",
            )
        }
    }
