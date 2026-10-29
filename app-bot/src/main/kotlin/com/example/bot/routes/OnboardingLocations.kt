package com.example.bot.routes

import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Pinned offline GeoNames snapshot. No address or search term leaves this server. */
internal object OnboardingLocations {
    private data class City(
        val id: String,
        val country: String,
        val name: String,
        val region: String,
        val timezone: String,
        val names: String,
    ) {
        fun view() =
            buildJsonObject {
                put("id", id)
                put("countryCode", country)
                put("name", name)
                put("region", region)
                put("timezone", timezone)
            }
    }

    private val cities: List<City> by lazy {
        val zones = ZoneId.getAvailableZoneIds()
        val countries = Locale.getISOCountries().toSet()
        val stream = checkNotNull(javaClass.getResourceAsStream("/locations/cities.tsv.gz"))
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .map { line ->
                    val fields = line.split('\t')
                    check(fields.size == FIELD_COUNT)
                    City(fields[0], fields[1], fields[2], fields[3], fields[4], "|" + normalize(fields[5]) + "|")
                }.filter { it.timezone in zones && it.country in countries }
                .toList()
        }
    }

    private val byId by lazy { cities.associateBy { it.id } }

    fun countries(): JsonObject =
        buildJsonObject {
            val language = Locale.forLanguageTag("ru")
            put(
                "countries",
                JsonArray(
                    cities
                        .map { it.country }
                        .distinct()
                        .map { code ->
                            buildJsonObject {
                                put("code", code)
                                put(
                                    "name",
                                    Locale
                                        .Builder()
                                        .setRegion(code)
                                        .build()
                                        .getDisplayCountry(language),
                                )
                            }
                        }.sortedBy { it["name"].toString() },
                ),
            )
        }

    fun search(body: JsonObject): JsonObject {
        only(body, setOf("countryCode", "query"))
        val country = text(body, "countryCode")
        val query = normalize(text(body, "query").trim())
        if (country.length != 2 || query.length !in MIN_QUERY..MAX_QUERY) invalid()
        val matches =
            cities
                .filter { it.country == country && it.names.contains("|$query") }
                .sortedWith(
                    compareBy<City> {
                        if (it.names.contains(
                                "|$query|",
                            )
                        ) {
                            0
                        } else {
                            1
                        }
                    }.thenBy { it.name }.thenBy { it.region },
                )
        return buildJsonObject {
            put("cities", JsonArray(matches.take(RESULT_LIMIT).map { it.view() }))
            put("hasMore", matches.size > RESULT_LIMIT)
        }
    }

    fun creationBody(body: JsonObject): JsonObject {
        only(body, setOf("venueName", "countryCode", "cityId", "address", "applicationId"))
        val city = byId[text(body, "cityId")] ?: throw OnboardingFailure("location_required")
        if (city.country != text(body, "countryCode")) throw OnboardingFailure("location_required")
        return JsonObject(
            body +
                mapOf(
                    "city" to JsonPrimitive(city.name),
                    "region" to JsonPrimitive(city.region),
                    "timezone" to JsonPrimitive(city.timezone),
                ),
        )
    }

    private fun text(
        body: JsonObject,
        key: String,
    ): String = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun only(
        body: JsonObject,
        keys: Set<String>,
    ) {
        if (body.keys.any { it !in keys }) invalid()
    }

    private fun normalize(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')

    private fun invalid(): Nothing = throw OnboardingFailure("validation_error")

    private const val FIELD_COUNT = 6
    private const val MIN_QUERY = 2
    private const val MAX_QUERY = 120
    private const val RESULT_LIMIT = 30
}
