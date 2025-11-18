@file:Suppress(
    "ktlint:standard:string-template-indent",
    "ktlint:standard:multiline-expression-wrapping",
)

package com.example.bot.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Простой UI для Telegram WebApp.
 * Страницы: /ui/waitlist, /ui/guest-list
 * /ui/checkin теперь отрисовывается в BookingWebAppRoutes.installBookingWebApp().
 */
fun Application.installWebUi() {
    routing {
        route("/ui/waitlist") {
            get {
                call.respondText(
                    contentType = ContentType.Text.Html,
                    text = """
                    <!doctype html><html lang="ru"><head>
                      <meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
                      <title>Waitlist</title>
                      <script src="https://telegram.org/js/telegram-web-app.js"></script>
                    </head><body>
                      <h1>Waitlist</h1>
                      <p>Заглушка очереди ожидания. Здесь будет форма записи.</p>
                      <script>const tg=window.Telegram?.WebApp; tg?.expand?.(); tg?.ready?.();</script>
                    </body></html>
                    """.trimIndent(),
                )
            }
            head { call.respond(HttpStatusCode.OK) }
        }

        route("/ui/guest-list") {
            get {
                call.respondText(
                    contentType = ContentType.Text.Html,
                    text = """
                    <!doctype html><html lang="ru"><head>
                      <meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
                      <title>Guest List</title>
                      <script src="https://telegram.org/js/telegram-web-app.js"></script>
                    </head><body>
                      <h1>Guest List</h1>
                      <p>Заглушка гостевого листа. Здесь будет поиск/чек‑ин гостей.</p>
                      <script>const tg=window.Telegram?.WebApp; tg?.expand?.(); tg?.ready?.();</script>
                    </body></html>
                    """.trimIndent(),
                )
            }
            head { call.respond(HttpStatusCode.OK) }
        }
    }
}
