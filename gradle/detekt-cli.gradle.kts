import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.*
import java.io.File

val detektVersion = "1.23.6"

configurations.maybeCreate("detektCli")

dependencies {
    add("detektCli", "io.gitlab.arturbosch.detekt:detekt-cli:$detektVersion")
}

fun Project.registerDetektCliTask() {
    tasks.register<JavaExec>("detektCli") {
        group = "verification"
        description = "Run detekt CLI (no Gradle plugin) on this module"
        mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
        classpath = configurations.getByName("detektCli")

        val inputs = listOf(
            project.layout.projectDirectory.dir("src/main/kotlin").asFile,
            project.layout.projectDirectory.dir("src/test/kotlin").asFile
        ).filter { it.exists() }

        onlyIf { inputs.isNotEmpty() }

        val reportsDir = project.layout.buildDirectory.dir("reports/detekt").get().asFile
        val configFile = rootProject.file("detekt.yml")
        if (configFile.exists()) {
            args("--config", configFile.absolutePath)
        }
        args(
            "--input", inputs.joinToString(",") { it.absolutePath },
            "--build-upon-default-config",
            "--report", "html:${File(reportsDir, "detekt.html").absolutePath}",
            "--report", "xml:${File(reportsDir, "detekt.xml").absolutePath}"
        )
    }

}

registerDetektCliTask()
