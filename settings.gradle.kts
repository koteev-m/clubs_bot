import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "bot_for_add_prod"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Fallback на mirror Google, если repo.maven.apache.org даёт 5xx
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                // Kotlin Gradle Plugin и родственные артефакты
                includeGroupByRegex("org\\.jetbrains(\\..+)?")
                // Если потребуется, сюда можно добавить другие используемые плагины
                includeGroup("io.ktor")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Тот же fallback на mirror Google — ограничиваем контентом, чтобы не «ловить» чужие группы
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                includeGroupByRegex("org\\.jetbrains(\\..+)?") // Kotlin, kotlinx, compose и т.д.
                includeGroup("io.ktor")
            }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    "app-bot",
    "core-domain",
    "core-data",
    "core-telemetry",
    "core-security",
    "core-testing",
    "tools:perf"
)
