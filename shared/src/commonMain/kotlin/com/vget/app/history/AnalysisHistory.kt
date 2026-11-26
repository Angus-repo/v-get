package com.vget.app.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnalysisEntry(val title: String, val pageUrl: String)

object AnalysisHistory {
    const val storageKey = "analysisHistory"
    private const val limit = 30

    fun entries(stored: String?): List<AnalysisEntry> {
        if (stored.isNullOrEmpty()) return emptyList()
        return try {
            Json.decodeFromString<List<AnalysisEntry>>(stored).take(limit)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    fun adding(stored: String?, title: String, pageUrl: String): String {
        val newest = AnalysisEntry(title.ifBlank { pageUrl }, pageUrl)
        return Json.encodeToString(listOf(newest) + entries(stored).take(limit - 1))
    }
}
