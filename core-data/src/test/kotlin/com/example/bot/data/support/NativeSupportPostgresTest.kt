package com.example.bot.data.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.EngineFilter.includeEngines
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

internal class NativeSupportPostgresTest {
    @Test
    fun `ordinary test discovery skips native adapters before their constructors run`() {
        assumeFalse(NativeSupportPostgres.requested())
        val listener = SummaryGeneratingListener()
        val request =
            LauncherDiscoveryRequestBuilder
                .request()
                .selectors(
                    selectClass(SupportTicketCreationNativePostgresIT::class.java),
                    selectClass(NativeSupportPostgresLifecycleIT::class.java),
                ).filters(includeEngines("junit-jupiter"))
                .build()
        LauncherFactory.create().execute(request, listener)
        assertTrue(listener.summary.failures.isEmpty())
        assertTrue(listener.summary.testsSkippedCount > 0)
        assertEquals(0, listener.summary.testsStartedCount)
    }

    @Test
    fun `every external URL including syntactically valid loopback is rejected before side effects`() {
        listOf("", "\n", "jdbc:postgresql://db.example/shared", "jdbc:postgresql://127.0.0.1/clb_support_test")
            .forEach { url ->
                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        NativeSupportPostgres.configured("/not/executed", url)
                    }
                assertTrue(failure.message.orEmpty().contains("External PostgreSQL URLs are forbidden"))
            }
    }

    @Test
    fun `invalid explicit binaries fail instead of skipping or creating resources`() {
        listOf(null, "", "\n", "relative", "/does-not-exist-clb-pg").forEach { binaries ->
            assertThrows(IllegalArgumentException::class.java) { NativeSupportPostgres.configured(binaries, null) }
        }
    }
}
