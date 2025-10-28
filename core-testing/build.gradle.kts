import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(projects.coreDomain)
    implementation(projects.coreData)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.junit.jupiter)
    implementation(libs.testcontainers.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(projects.appBot)
    testImplementation(libs.pengrad.telegram)
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.micrometer.prometheus)
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.logstash.encoder)
    testImplementation(libs.logback)
    testImplementation(libs.ktor.server.call.logging)
    testImplementation(libs.ktor.server.call.id)
    testImplementation(libs.ktor.server.metrics.micrometer)
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.hikari)
    testImplementation(libs.postgres)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.pg)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.server.content.negotiation)
}

tasks.test {
    useJUnitPlatform()
    if (project.hasProperty("runIT")) {
        systemProperty("junit.jupiter.tags.include", "it")
    } else {
        systemProperty("junit.jupiter.tags.exclude", "it")
    }
    systemProperty("FLYWAY_LOCATIONS", "classpath:db/migration,classpath:db/migration/postgresql")
}
