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
 * SEC-02: ripgrep-скан опасных паттернов с потоковой печатью.
 * Exit-коды rg:
 *   0 — совпадения НАЙДЕНЫ (для нас это нарушение → fail)
 *   1 — совпадений НЕТ (ok)
 *   2 — ошибка (нет rg и т.п.) → fail
 */
abstract class LogsPolicyScanTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {

    @get:Input
    val timeoutSeconds: Property<Int> = objects.property(Int::class.java).convention(120)

    @get:Input
    val includeArgs: ListProperty<String> = objects.listProperty(String::class.java).convention(
        listOf("-n", "--hidden")
    )

    @get:Input
    val patterns: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    @get:Internal
    val workingDirectory = objects.directoryProperty().convention(project.layout.projectDirectory)

    @TaskAction
    fun runScan() {
        val args = mutableListOf("rg")
        args += includeArgs.get()
        patterns.get().forEach { p -> args += listOf("-e", p) }
        args += "."

        logger.lifecycle("SEC-02: ripgrep {}", args.joinToString(" "))

        val pb = ProcessBuilder(args)
            .directory(workingDirectory.asFile.get())
            .redirectErrorStream(false)

        val process = try { pb.start() } catch (e: Exception) {
            throw GradleException("ripgrep (rg) не найден в PATH. Установите rg. ${e.message}", e)
        }

        val outT = thread(name = "rg-stdout-$name", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.lifecycle("[rg] {}", it) }
            }
        }
        val errT = thread(name = "rg-stderr-$name", isDaemon = true) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.warn("[rg:err] {}", it) }
            }
        }

        val finished = process.waitFor(timeoutSeconds.get().toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            outT.join(1000); errT.join(1000)
            throw GradleException("ripgrep завис и был остановлен по таймауту ${timeoutSeconds.get()}s")
        }

        outT.join(); errT.join()
        when (val exit = process.exitValue()) {
            1 -> logger.lifecycle("SEC-02: совпадений не найдено (rg exit=1).")
            0 -> throw GradleException("SEC-02: найдены потенциальные нарушения (rg exit=0). См. строки [rg] выше.")
            else -> throw GradleException("SEC-02: ошибка выполнения ripgrep (exit=$exit). Установите/проверьте rg.")
        }
    }
}
