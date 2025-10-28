import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "bot_for_add_prod"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/maven-central")
        maven("https://repo1.maven.org/maven2")
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/maven-central")
        maven("https://repo1.maven.org/maven2")
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenLocal()
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
