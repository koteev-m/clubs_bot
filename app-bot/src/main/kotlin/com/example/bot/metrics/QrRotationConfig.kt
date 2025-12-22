package com.example.bot.metrics

/**
 * Конфигурация окна ротации QR-секретов.
 */
data class QrRotationConfig(
    val oldSecret: String?,
    val rotationDeadlineEpochSeconds: Long?,
) {
    val rotationActive: Boolean = !oldSecret.isNullOrBlank()

    companion object {
        private const val ENV_QR_OLD_SECRET = "QR_OLD_SECRET"
        private const val ENV_QR_ROTATION_DEADLINE_EPOCH = "QR_ROTATION_DEADLINE_EPOCH"

        fun fromEnv(env: Map<String, String> = System.getenv()): QrRotationConfig =
            QrRotationConfig(
                oldSecret = env[ENV_QR_OLD_SECRET],
                rotationDeadlineEpochSeconds = env[ENV_QR_ROTATION_DEADLINE_EPOCH]?.toLongOrNull(),
            )
    }
}
