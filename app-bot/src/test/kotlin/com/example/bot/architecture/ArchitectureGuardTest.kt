package com.example.bot.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureGuardTest {
    private val root = Path.of("src/main/kotlin")

    @Test fun `no io ktor namespace in project code`() {
        val bad = Files.walk(root).filter { it.toString().endsWith(".kt") && it.pathString.contains("/io/ktor/") && !it.pathString.contains("requestsize") }.toList()
        assertTrue(bad.isEmpty(), "Forbidden io.ktor namespace: $bad")
    }

    @Test fun `no reflection koin loading`() {
        val app = Files.readString(root.resolve("com/example/bot/Application.kt"))
        assertFalse(app.contains("loadKoinModulesReflectively"))
    }

    @Test fun `legacy route isolated and no header trust auth`() {
        val legacy = Files.readString(root.resolve("com/example/bot/deprecated/legacy/web/BookingWebAppRoutes.kt"))
        assertFalse(legacy.contains("X-TG-User-Id\"]?.toLongOrNull()"))
        assertTrue(legacy.contains("com.example.bot.deprecated.legacy"))
    }
}
