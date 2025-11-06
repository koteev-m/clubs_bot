package com.example.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * SEC-02: ripgrep-скан опасных паттернов с потоковой печатью результатов.
 *
 * Exit-коды rg:
 *   0 — совпадения НАЙДЕНЫ (для нас это нарушение → fail)
 *   1 — совпадений НЕТ (ok)
 *   2 — ошибка выполнения rg (отсутствует или иная проблема → fail)
 */
abstract class LogsPolicyScanTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {

    private companion object {
        private const val LOG_CALL_PATTERN =
            """(?:(?<!\\w)(?:logger|log|LOG|LOGGER)|environment\\.log|application\\.log)\\.(?:trace|debug|info|warn|error|critical|fatal)"""
    }

    @get:Input
    val timeoutSeconds: Property<Int> = objects.property(Int::class.java).convention(120)

    @get:Input
    val includeArgs: ListProperty<String> = objects.listProperty(String::class.java).convention(
        listOf(
            "-n", "--hidden",
            "-g", "!**/build/**",
            "-g", "!**/.gradle/**",
            "-g", "!**/.idea/**",
            "-g", "!**/.git/**",
            "-g", "!**/*.iml",
            "-g", "!**/src/test/**",
            "-g", "!**/src/main/resources/**",
            "-g", "!**/docs/**",
            "-g", "!**/*.md",
            "-g", "!**/test/fixtures/**"
        )
    )

    @get:Input
    val patterns: ListProperty<String> = objects.listProperty(String::class.java).convention(
        listOf(
            """qr=""",
            """start_param=""",
            """idempotencyKey""",
            // «голые» Telegram-токены бота
            """\\b\\d{6,12}:[A-Za-z0-9_-]{30,}\\b""",
            // сырые телефоны
            """\\+?\\d[\\d \\-\\(\\)]{8,}\\d""",
            // попытки логировать ФИО/имя
            """\\b(ФИО|fullName|fio|name)\\s*="""
        )
    )

    @get:Internal
    val workingDirectory = objects.directoryProperty().convention(project.layout.projectDirectory)

    @TaskAction
    fun runScan() {
        val args = mutableListOf("rg")
        args += includeArgs.get()
        args += "-P"
        patterns.get().forEach { pattern ->
            val combinedPattern = "$LOG_CALL_PATTERN[^\\n]*$pattern"
            args += listOf("-e", combinedPattern)
        }
        args += "."

        logger.lifecycle("SEC-02: running ripgrep: {}", args.joinToString(" "))

        val processBuilder = ProcessBuilder(args)
            .directory(workingDirectory.asFile.get())
            .redirectErrorStream(false)

        val process = try {
            processBuilder.start()
        } catch (exception: Exception) {
            throw GradleException(
                "ripgrep (rg) не найден в PATH. Установите rg и повторите. Оригинальная ошибка: ${exception.message}",
                exception
            )
        }

        val stdoutThread = thread(name = "rg-stdout-${'$'}{project.path}", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> logger.lifecycle("[rg] {}", line) }
            }
        }
        val stderrThread = thread(name = "rg-stderr-${'$'}{project.path}", isDaemon = true) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> logger.warn("[rg:err] {}", line) }
            }
        }

        val finished = process.waitFor(timeoutSeconds.get().toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)
            throw GradleException("ripgrep завис и был остановлен по таймауту ${timeoutSeconds.get()}s")
        }

        stdoutThread.join()
        stderrThread.join()

        when (val exitCode = process.exitValue()) {
            1 -> logger.lifecycle("SEC-02: ripgrep найденных нарушений нет (exit=1).")
            0 -> throw GradleException(
                "SEC-02: найдены потенциально сырые данные в лог-вызовах (exit=0). См. строки выше."
            )
            2 -> throw GradleException(
                "SEC-02: ripgrep завершился с ошибкой (exit=2). Установите ripgrep (rg) и повторите."
            )
            else -> throw GradleException(
                "SEC-02: ripgrep завершился с ошибкой (exit=${'$'}exitCode). См. лог выше."
            )
        }
    }
}
