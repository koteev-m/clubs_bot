package com.example.bot.layout

interface LayoutAssetsRepository {
    /** Возвращает JSON геометрии для layout по клубу и fingerprint. */
    suspend fun loadGeometry(clubId: Long, fingerprint: String): String?
}
