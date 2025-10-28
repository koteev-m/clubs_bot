import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import java.io.File

val ktlintVersion = "1.3.1"

configurations.maybeCreate("ktlintCli")

dependencies {
    add("ktlintCli", "com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
}

fun Project.ktlintInputs(): List<File> =
    listOf(
        layout.projectDirectory.dir("src/main/kotlin").asFile,
        layout.projectDirectory.dir("src/test/kotlin").asFile,
        layout.projectDirectory.file("build.gradle.kts").asFile
    ).filter { it.exists() }

tasks.register<JavaExec>("ktlintCheckCli") {
    group = "verification"
    description = "Run ktlint CLI (no Gradle plugin) on this module"
    mainClass.set("com.pinterest.ktlint.Main")
    classpath = configurations.getByName("ktlintCli")

    val patterns = listOf(
        "src/**/*.kt",
        "**/*.kts"
    )
    workingDir = project.projectDir
    args(
        "--reporter=plain",
        "--reporter=checkstyle,output=${layout.buildDirectory.file("reports/ktlint/ktlint-checkstyle.xml").get().asFile}",
    )
    args(patterns)

    onlyIf { ktlintInputs().isNotEmpty() }
    isIgnoreExitValue = false
}

tasks.register<JavaExec>("ktlintFormatCli") {
    group = "formatting"
    description = "Run ktlint CLI with -F (format) on this module"
    mainClass.set("com.pinterest.ktlint.Main")
    classpath = configurations.getByName("ktlintCli")
    workingDir = project.projectDir
    val patterns = listOf(
        "src/**/*.kt",
        "**/*.kts"
    )
    args("-F")
    args(patterns)
    onlyIf { ktlintInputs().isNotEmpty() }
    isIgnoreExitValue = false
}
