package com.example.bot.miniapp

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import java.io.File

/** Ktor module serving Mini App static files. */
fun Application.miniAppModule() {
    install(Compression) { gzip() }
    install(DefaultHeaders) {
        header("X-Frame-Options", "SAMEORIGIN")
        header(
            "Content-Security-Policy",
            "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "connect-src *",
        )
    }
    routing {
        // Ktor 3.x: static/static { files/default } → staticFiles(dir, indexFile)
        staticFiles("/app", File("miniapp/dist"), "index.html")
    }
}
