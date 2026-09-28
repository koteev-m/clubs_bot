package com.example.bot.plugins

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class ProfileDetectionTest {
    @ParameterizedTest
    @CsvSource(
        "' production ', DEV, true",
        "staging, DEV, true",
        "DEV, PROD, false",
        "TEST, STAGE, false",
    )
    fun `profile keeps precedence and normalization`(
        profile: String,
        env: String,
        expected: Boolean,
    ) = testApplication {
        environment {
            config = MapApplicationConfig("app.env.APP_PROFILE" to profile, "app.env.APP_ENV" to env)
        }
        application { assertEquals(expected, isProdLikeProfile()) }
        startApplication()
    }
}
