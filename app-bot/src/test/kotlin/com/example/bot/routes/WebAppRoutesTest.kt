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

            listOf("/app", "/app/", "/app?mode=guest", "/app?mode=admin").forEach { path ->
                val indexResponse = client.get(path)
                val html = indexResponse.bodyAsText()

                assertEquals(HttpStatusCode.OK, indexResponse.status)
                assertTrue(indexResponse.contentType()?.match(ContentType.Text.Html) == true)
                assertEquals(packagedIndex, html)
                assertContains(html, "<title>Куда пойдём?</title>")
                assertEquals(listOf("/app/"), baseHrefPattern.findAll(html).map { it.groupValues[1] }.toList())
            }

            assertEquals(HttpStatusCode.OK, client.get("/app/index.html").status)
            assertEquals(HttpStatusCode.OK, client.get("/app/layout.html").status)
            assertEquals("User-agent: *\nDisallow: /\n", client.get("/app/robots.txt").bodyAsText())
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

    @Test
    fun `support mode serves the bounded React entry with isolated assets`() =
        testApplication {
            application {
                install(DefaultHeaders)
                install(Compression) { gzip() }
                webAppRoutes()
            }

            val packagedSupportIndex =
                requireNotNull(WebAppRoutesTest::class.java.classLoader.getResource("webapp/app/react/index.html"))
                    .readText()

            listOf("/app?mode=support", "/app/?mode=support").forEach { path ->
                val response = client.get(path)
                val html = response.bodyAsText()

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)
                assertEquals(packagedSupportIndex, html)
                assertContains(html, "<div id=\"root\"></div>")

                val assetReferences = assetReferencePattern.findAll(html).map { it.groupValues[1] }.toList()
                assertTrue(assetReferences.any { it.endsWith(".css") })
                assertTrue(assetReferences.any { it.endsWith(".js") })
                assertTrue(assetReferences.all { it.startsWith("/app/react/assets/") })

                assetReferences.forEach { assetPath ->
                    assertEquals(HttpStatusCode.OK, client.get(assetPath).status)
                }
            }

            listOf(
                "/app/react/index.html",
                "/app/react/index.html?mode=admin",
                "/app/react/index.html?mode=promoter",
                "/app/react/index.html?mode=guest-support",
            ).forEach { directEntryPath ->
                assertEquals(HttpStatusCode.NotFound, client.get(directEntryPath).status)
            }
        }

    @Test
    fun `guest support mode serves the bounded React entry with isolated assets`() =
        testApplication {
            application {
                install(DefaultHeaders)
                install(Compression) { gzip() }
                webAppRoutes()
            }

            val packagedGuestSupportIndex =
                requireNotNull(WebAppRoutesTest::class.java.classLoader.getResource("webapp/app/react/index.html"))
                    .readText()

            listOf("/app?mode=guest-support", "/app/?mode=guest-support").forEach { path ->
                val response = client.get(path)
                val html = response.bodyAsText()

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)
                assertEquals(packagedGuestSupportIndex, html)
                assertContains(html, "<div id=\"root\"></div>")

                val assetReferences = assetReferencePattern.findAll(html).map { it.groupValues[1] }.toList()
                assertTrue(assetReferences.any { it.endsWith(".css") })
                assertTrue(assetReferences.any { it.endsWith(".js") })
                assertTrue(assetReferences.all { it.startsWith("/app/react/assets/") })

                assetReferences.forEach { assetPath ->
                    assertEquals(HttpStatusCode.OK, client.get(assetPath).status)
                }
            }
        }

    private companion object {
        val baseHrefPattern = Regex("""<base\s+href="([^"]+)"\s*/>""")
        val assetReferencePattern = Regex("""(?:href|src)="([^"]+\.(?:css|js))"""")
    }
}
