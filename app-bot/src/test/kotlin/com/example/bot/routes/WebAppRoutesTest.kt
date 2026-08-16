package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.testing.testApplication
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAppRoutesTest {
    @Test
    fun `serves packaged mini app without installing duplicate global plugins`() =
        testApplication {
            application {
                install(DefaultHeaders)
                install(Compression) { gzip() }
                webAppRoutes()
            }

            val packagedIndex =
                requireNotNull(WebAppRoutesTest::class.java.classLoader.getResource("webapp/app/index.html"))
                    .readText()

            listOf("/app", "/app/").forEach { path ->
                val indexResponse = client.get(path)
                val html = indexResponse.bodyAsText()

                assertEquals(HttpStatusCode.OK, indexResponse.status)
                assertTrue(indexResponse.contentType()?.match(ContentType.Text.Html) == true)
                assertEquals(packagedIndex, html)
                assertContains(html, "<title>Куда пойдём?</title>")
                assertEquals(listOf("/app/"), baseHrefPattern.findAll(html).map { it.groupValues[1] }.toList())
            }
        }

    @Test
    fun `packaged asset references resolve below app for both document urls`() =
        testApplication {
            application {
                install(DefaultHeaders)
                install(Compression) { gzip() }
                webAppRoutes()
            }

            listOf("https://example.com/app", "https://example.com/app/").forEach { documentUrl ->
                val documentUri = URI(documentUrl)
                val html = client.get(documentUri.rawPath).bodyAsText()
                val baseHref = requireNotNull(baseHrefPattern.find(html)?.groupValues?.get(1))
                val assetReferences = assetReferencePattern.findAll(html).map { it.groupValues[1] }.toList()
                val resolvedAssets = assetReferences.map { documentUri.resolve(baseHref).resolve(it) }

                assertEquals("/app/", baseHref)
                assertTrue(assetReferences.any { it.endsWith(".css") })
                assertTrue(assetReferences.any { it.endsWith(".js") })
                assertTrue(resolvedAssets.all { it.rawPath.startsWith("/app/assets/") })
                assertFalse(resolvedAssets.any { it.rawPath.startsWith("/assets/") })

                val cssPath = resolvedAssets.first { it.rawPath.endsWith(".css") }.rawPath
                val jsPath = resolvedAssets.first { it.rawPath.endsWith(".js") }.rawPath
                val cssResponse = client.get(cssPath)
                val jsResponse = client.get(jsPath)

                assertEquals(HttpStatusCode.OK, cssResponse.status)
                assertTrue(cssResponse.contentType()?.match(ContentType.Text.CSS) == true)
                assertEquals(HttpStatusCode.OK, jsResponse.status)
                assertEquals("javascript", jsResponse.contentType()?.contentSubtype)
            }
        }

    private companion object {
        val baseHrefPattern = Regex("""<base\s+href="([^"]+)"\s*/>""")
        val assetReferencePattern = Regex("""(?:href|src)="([^"]+\.(?:css|js))"""")
    }
}
