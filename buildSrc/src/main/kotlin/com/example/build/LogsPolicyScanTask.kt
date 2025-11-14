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
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * CC-friendly реализация: не обращается к `project` в @TaskAction,
 * использует ExecOperations/ProjectLayout, объявляет inputs/outputs.
 */
@CacheableTask
abstract class LogsPolicyScanTask @Inject constructor(
    private val execOps: ExecOperations,
    layout: ProjectLayout,
    objects: ObjectFactory,
) : DefaultTask() {

    /** Где искать (как правило — директория модуля). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sourceDirs: ConfigurableFileCollection = objects.fileCollection().from(layout.projectDirectory)

    /** Какие globs включать в поиск rg -g <glob>. */
    @get:Input
    val includeGlobs: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Какие globs исключать rg -g !<glob>. */
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

    /** Регулярки для -e <pattern>. */
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
        val args = mutableListOf("-n", "--hidden", "-P")

        includeGlobs.get().forEach { args += listOf("-g", it) }
        excludeGlobs.get().forEach { args += listOf("-g", "!$it") }
        patterns.get().forEach { args += listOf("-e", it) }

        // Каталоги/файлы для поиска
        sourceDirs.files.forEach { args += it.absolutePath }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOps.exec {
            executable = ripgrepExecutable.get()
            args(args)
            isIgnoreExitValue = true // rg: 0=есть совпадения, 1=совпадений нет, >1=ошибка
            standardOutput = stdout
            errorOutput = stderr
        }
        val output = buildString {
            append(stdout.toString(Charsets.UTF_8))
            val err = stderr.toString(Charsets.UTF_8)
            if (err.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(err)
            }
        }

        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(output)

        when (result.exitValue) {
            0 -> {
                logger.error("SEC-02: обнаружены совпадения. См. отчёт: {}", out)
                throw GradleException("Logs policy check failed. See $out")
            }
            1 -> {
                logger.lifecycle("SEC-02: совпадений не найдено (rg exit=1).")
            }
            else -> {
                logger.error("SEC-02: ripgrep завершился с кодом {}. См. {}", result.exitValue, out)
                throw GradleException("ripgrep failed with exit code ${result.exitValue}. See $out")
            }
        }
    }
}
