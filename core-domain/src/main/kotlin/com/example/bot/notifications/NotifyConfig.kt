package com.example.bot.notifications

data class NotifyConfig(
    val globalRps: Int = (System.getenv("GLOBAL_RPS") ?: "25").toInt(),
    val chatRps: Double = (System.getenv("CHAT_RPS") ?: "1.5").toDouble(),
    val workerParallelism: Int = (System.getenv("WORKER_PARALLELISM") ?: "4").toInt(),
    val retryBaseMs: Long = (System.getenv("RETRY_BASE_MS") ?: "500").toLong(),
    val retryMaxMs: Long = (System.getenv("RETRY_MAX_MS") ?: "15000").toLong(),
)
