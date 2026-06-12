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

    @Test fun `legacy package isolated and no client provided identity trust`() {
        val legacySources = legacyProductionSources()
        val violations = legacySources.flatMap { (path, source) ->
            clientProvidedTelegramIdentityPatterns()
                .filter { it.containsMatchIn(source) }
                .map { path to it }
        }

        assertTrue(violations.isEmpty(), "Legacy package must not trust client-provided Telegram identity: $violations")
        assertTrue(legacySources.values.any { it.contains("com.example.bot.deprecated.legacy") })
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
                """
                @Serializable
                private data class Request(
                    val clubId: Long,
                    val tgUserId: Long,
                )
                """.trimIndent(),
                "call.receive<Request>().tgUserId",
                "call.receive<Request>().payload.tgUserId",
                "json.decodeFromString<Request>(raw).tgUserId",
            )

        snippets.forEach { snippet ->
            assertTrue(
                clientProvidedTelegramIdentityPatterns().any { it.containsMatchIn(snippet) },
                "Legacy identity guard did not catch spoofing accessor: $snippet",
            )
        }
    }

    private fun legacyProductionSources(): Map<String, String> {
        val legacyRoot = root.resolve("com/example/bot/deprecated/legacy")
        return Files.walk(legacyRoot).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .sorted()
                .toList()
                .associate { root.relativize(it).pathString to Files.readString(it) }
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
            Regex("@Serializable\\s+(?:private\\s+|internal\\s+|public\\s+)?(?:data\\s+)?class\\s+\\w+\\s*\\([^)]*\\btgUserId\\b"),
            Regex("\\bcall\\s*\\.\\s*receive\\s*(?:<[^>]+>)?\\s*\\([^)]*\\)\\s*(?:\\.\\s*\\w+)*\\.\\s*tgUserId"),
            Regex("decodeFromString<[^>]+>\\([^)]*\\)\\s*\\.\\s*tgUserId"),
        )
}
