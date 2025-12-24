package com.example.bot.data.repo

import com.example.bot.data.db.withRetriedTx

/**
 * Пример применения withRetriedTx в репозитории.
 * Здесь мы не привязываемся к конкретным таблицам, а демонстрируем шаблон.
 */
private const val RETRY_PLACEHOLDER_RESULT = 42

class RetryExampleRepository {
    suspend fun doSomethingWithRetry(): Int =
        withRetriedTx(name = "retryExample", manageTransaction = false) {
            // Здесь могла быть ваша Exposed-логика; ниже — заглушка для примера:
            RETRY_PLACEHOLDER_RESULT
        }
}
