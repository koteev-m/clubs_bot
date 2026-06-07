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

    @Test fun `legacy route isolated and no client provided identity trust`() {
        val legacy = Files.readString(root.resolve("com/example/bot/deprecated/legacy/web/BookingWebAppRoutes.kt"))
        val violations = clientProvidedTelegramIdentityPatterns().filter { it.containsMatchIn(legacy) }

        assertTrue(violations.isEmpty(), "Legacy route must not trust client-provided Telegram identity: $violations")
        assertTrue(legacy.contains("com.example.bot.deprecated.legacy"))
    }

    @Test fun `legacy identity guard detects common spoofing accessors`() {
        val snippets =
            listOf(
                "call.request.header(\"X-TG-User-Id\")",
                "call.request.headers[\"X-TG-User-Id\"]",
                "call.request.queryParameters[\"tgUserId\"]",
                "call.receiveParameters()[\"tgUserId\"]",
                "formParameters[\"tgUserId\"]",
                "formParameters.get(\"tgUserId\")",
                "formParameters.getAll(\"tgUserId\")",
                "jsonObject[\"tgUserId\"]",
                "@Serializable private data class Request(val tgUserId: Long)",
                "json.decodeFromString<Request>(raw).tgUserId",
            )

        snippets.forEach { snippet ->
            assertTrue(
                clientProvidedTelegramIdentityPatterns().any { it.containsMatchIn(snippet) },
                "Legacy identity guard did not catch spoofing accessor: $snippet",
            )
        }
    }

    private fun clientProvidedTelegramIdentityPatterns(): List<Regex> =
        listOf(
            Regex("\\bheader\\s*\\(\\s*\"X-TG-User-Id\""),
            Regex("\\bheaders\\s*\\[\\s*\"X-TG-User-Id\"\\s*]"),
            Regex("\\bqueryParameters\\s*\\[\\s*\"tgUserId\"\\s*]"),
            Regex("\\b(?:queryParameters|parameters|formParameters|receiveParameters\\s*\\([^)]*\\))\\s*\\[\\s*\"tgUserId\"\\s*]"),
            Regex("\\b(?:queryParameters|parameters|formParameters|receiveParameters\\s*\\([^)]*\\))\\s*\\.\\s*(?:get|getAll)\\s*\\(\\s*\"tgUserId\""),
            Regex("\\bjsonObject\\s*\\[\\s*\"tgUserId\"\\s*]"),
            Regex("@Serializable\\s+[^\\n]*(?:data\\s+)?class\\s+\\w+[^\\n]*\\btgUserId\\b"),
            Regex("decodeFromString<[^>]+>\\([^)]*\\)\\s*\\.\\s*tgUserId"),
        )
}
