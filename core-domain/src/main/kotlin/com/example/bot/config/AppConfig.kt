package com.example.bot.config

import java.lang.StringBuilder

enum class AppProfile { DEV, STAGE, PROD }

enum class BotRunMode { WEBHOOK, POLLING }

data class BotConfig(val token: String, val username: String?, val ownerId: Long) {
    fun safe(): String = "BotConfig(token=${maskSecret(token)}, username=$username, ownerId=$ownerId)"
}

data class WebhookConfig(val baseUrl: String?, val secretToken: String?) {
    fun safe(): String = "WebhookConfig(baseUrl=$baseUrl, secretToken=${maskSecret(secretToken)})"
}

data class DbConfig(val url: String, val user: String, val password: String) {
    fun safe(): String = "DbConfig(url=$url, user=$user, password=${maskSecret(password)})"
}

data class WorkersConfig(val globalRps: Int, val chatRps: Int, val parallelism: Int) {
    fun safe(): String = "Workers(global=$globalRps, chat=$chatRps, parallelism=$parallelism)"
}

data class HealthConfig(val dbTimeoutMs: Long) {
    fun safe(): String = "Health(dbTimeoutMs=$dbTimeoutMs)"
}

data class LocalBotApiConfig(val enabled: Boolean, val apiId: String?, val apiHash: String?, val baseUrl: String?) {
    fun safe(): String =
        "LocalBotAPI(enabled=$enabled, apiId=${maskSecret(
            apiId,
        )}, apiHash=${maskSecret(apiHash)}, baseUrl=$baseUrl)"
}

data class HqEndpoints(
    val chatId: Long,
    val general: Int?,
    val bookings: Int?,
    val lists: Int?,
    val qa: Int?,
    val system: Int?,
) {
    fun safe(): String = "HQ(chat=$chatId, general=$general, bookings=$bookings, lists=$lists, qa=$qa, system=$system)"
}

data class ClubEndpoints(
    val name: String,
    val chatId: Long,
    val general: Int?,
    val bookings: Int?,
    val lists: Int?,
    val qa: Int?,
    val media: Int?,
    val system: Int?,
) {
    fun safe(): String =
        "Club(name=$name, chat=$chatId, general=$general, bookings=$bookings, " +
            "lists=$lists, qa=$qa, media=$media, system=$system)"
}

data class AppConfig(
    val profile: AppProfile,
    val runMode: BotRunMode,
    val bot: BotConfig,
    val webhook: WebhookConfig,
    val db: DbConfig,
    val workers: WorkersConfig,
    val health: HealthConfig,
    val localApi: LocalBotApiConfig,
    val hq: HqEndpoints,
    val clubs: List<ClubEndpoints>,
) {
    companion object {
        private const val DEFAULT_GLOBAL_RPS = 25
        private const val DEFAULT_CHAT_RPS = 2
        private const val DEFAULT_WORKER_PARALLELISM = 4
        private const val DEFAULT_HEALTH_DB_TIMEOUT_MS = 150L

        fun fromEnv(): AppConfig {
            val profile = env("APP_PROFILE")?.let { AppProfile.valueOf(it.uppercase()) } ?: AppProfile.DEV
            val runMode =
                if (envBool("TELEGRAM_USE_POLLING", false)) {
                    BotRunMode.POLLING
                } else {
                    BotRunMode.WEBHOOK
                }
            val bot =
                BotConfig(
                    token = envRequired("TELEGRAM_BOT_TOKEN"),
                    username = env("BOT_USERNAME"),
                    ownerId = envLong("OWNER_TELEGRAM_ID"),
                )
            val webhook =
                WebhookConfig(
                    baseUrl = env("WEBHOOK_BASE_URL"),
                    secretToken = env("WEBHOOK_SECRET_TOKEN"),
                )
            val db =
                DbConfig(
                    url = envRequired("DATABASE_URL"),
                    user = envRequired("DATABASE_USER"),
                    password = envRequired("DATABASE_PASSWORD"),
                )
            val workers =
                WorkersConfig(
                    globalRps = envInt("GLOBAL_RPS", DEFAULT_GLOBAL_RPS),
                    chatRps = envInt("CHAT_RPS", DEFAULT_CHAT_RPS),
                    parallelism = envInt("WORKER_PARALLELISM", DEFAULT_WORKER_PARALLELISM),
                )
            val health =
                HealthConfig(
                    dbTimeoutMs = envLong("HEALTH_DB_TIMEOUT_MS", DEFAULT_HEALTH_DB_TIMEOUT_MS),
                )
            val localApi =
                LocalBotApiConfig(
                    enabled = envBool("LOCAL_BOT_API_ENABLED", false),
                    apiId = env("TELEGRAM_API_ID"),
                    apiHash = env("TELEGRAM_API_HASH"),
                    baseUrl = env("LOCAL_BOT_API_URL"),
                )
            val hq =
                HqEndpoints(
                    chatId = envLong("HQ_CHAT_ID"),
                    general = env("HQ_GENERAL_THREAD_ID")?.toIntOrNull(),
                    bookings = env("HQ_BOOKINGS_THREAD_ID")?.toIntOrNull(),
                    lists = env("HQ_LISTS_THREAD_ID")?.toIntOrNull(),
                    qa = env("HQ_QA_THREAD_ID")?.toIntOrNull(),
                    system = env("HQ_SYSTEM_THREAD_ID")?.toIntOrNull(),
                )
            val clubs =
                listOf(
                    ClubEndpoints(
                        name = "Mix",
                        chatId = envLong("CLUB1_CHAT_ID"),
                        general = env("CLUB1_GENERAL_THREAD_ID")?.toIntOrNull(),
                        bookings = env("CLUB1_BOOKINGS_THREAD_ID")?.toIntOrNull(),
                        lists = env("CLUB1_LISTS_THREAD_ID")?.toIntOrNull(),
                        qa = env("CLUB1_QA_THREAD_ID")?.toIntOrNull(),
                        media = env("CLUB1_MEDIA_THREAD_ID")?.toIntOrNull(),
                        system = env("CLUB1_SYSTEM_THREAD_ID")?.toIntOrNull(),
                    ),
                    ClubEndpoints(
                        name = "Osobnyak",
                        chatId = envLong("CLUB2_CHAT_ID"),
                        general = env("CLUB2_GENERAL_THREAD_ID")?.toIntOrNull(),
                        bookings = env("CLUB2_BOOKINGS_THREAD_ID")?.toIntOrNull(),
                        lists = env("CLUB2_LISTS_THREAD_ID")?.toIntOrNull(),
                        qa = env("CLUB2_QA_THREAD_ID")?.toIntOrNull(),
                        media = env("CLUB2_MEDIA_THREAD_ID")?.toIntOrNull(),
                        system = env("CLUB2_SYSTEM_THREAD_ID")?.toIntOrNull(),
                    ),
                    ClubEndpoints(
                        name = "Internal3",
                        chatId = envLong("CLUB3_CHAT_ID"),
                        general = env("CLUB3_GENERAL_THREAD_ID")?.toIntOrNull(),
                        bookings = env("CLUB3_BOOKINGS_THREAD_ID")?.toIntOrNull(),
                        lists = env("CLUB3_LISTS_THREAD_ID")?.toIntOrNull(),
                        qa = env("CLUB3_QA_THREAD_ID")?.toIntOrNull(),
                        media = env("CLUB3_MEDIA_THREAD_ID")?.toIntOrNull(),
                        system = env("CLUB3_SYSTEM_THREAD_ID")?.toIntOrNull(),
                    ),
                    ClubEndpoints(
                        name = "NN",
                        chatId = envLong("CLUB4_CHAT_ID"),
                        general = env("CLUB4_GENERAL_THREAD_ID")?.toIntOrNull(),
                        bookings = env("CLUB4_BOOKINGS_THREAD_ID")?.toIntOrNull(),
                        lists = env("CLUB4_LISTS_THREAD_ID")?.toIntOrNull(),
                        qa = env("CLUB4_QA_THREAD_ID")?.toIntOrNull(),
                        media = env("CLUB4_MEDIA_THREAD_ID")?.toIntOrNull(),
                        system = env("CLUB4_SYSTEM_THREAD_ID")?.toIntOrNull(),
                    ),
                )
            return AppConfig(profile, runMode, bot, webhook, db, workers, health, localApi, hq, clubs)
        }
    }

    fun toSafeString(): String {
        val sb = StringBuilder()
        sb.appendLine("AppConfig(profile=$profile, runMode=$runMode)")
        sb.appendLine("  ${bot.safe()}")
        sb.appendLine("  ${webhook.safe()}")
        sb.appendLine("  ${db.safe()}")
        sb.appendLine("  ${workers.safe()}")
        sb.appendLine("  ${health.safe()}")
        sb.appendLine("  ${localApi.safe()}")
        sb.appendLine("  ${hq.safe()}")
        clubs.forEach { sb.appendLine("  ${it.safe()}") }
        return sb.toString()
    }
}
