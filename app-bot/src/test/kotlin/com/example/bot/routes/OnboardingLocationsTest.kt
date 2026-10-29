package com.example.bot.routes

import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OnboardingLocationsTest {
    @Test
    fun `same city names in different regions retain identity and different timezones`() {
        val kursk = OnboardingLocations.creationBody(body("463343"))
        val krasnoyarsk = OnboardingLocations.creationBody(body("1538635"))
        assertEquals(kursk["city"], krasnoyarsk["city"])
        assertNotEquals(kursk["cityId"], krasnoyarsk["cityId"])
        assertNotEquals(kursk["region"], krasnoyarsk["region"])
        assertEquals(JsonPrimitive("Europe/Moscow"), kursk["timezone"])
        assertEquals(JsonPrimitive("Asia/Krasnoyarsk"), krasnoyarsk["timezone"])
    }

    @Test
    fun `region and timezone overrides fail instead of changing trusted city selection`() {
        for (field in listOf("timezone", "region", "city")) {
            val failure =
                assertFailsWith<OnboardingFailure> {
                    OnboardingLocations.creationBody(JsonObject(body("524901") + (field to JsonPrimitive("override"))))
                }
            assertEquals("validation_error", failure.code)
        }
    }

    private fun body(city: String) =
        JsonObject(
            mapOf("cityId" to JsonPrimitive(city), "countryCode" to JsonPrimitive("RU")),
        )
}
