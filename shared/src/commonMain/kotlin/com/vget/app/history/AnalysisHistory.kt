package com.vget.app.history

import com.vget.app.network.VideoSource
import com.vget.app.network.WebUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnalysisEntry(val title: String, val pageUrl: String) {
    val sourceLabel: String get() {
        val host = pageUrl.toHttpUrlOrNull()?.host.orEmpty()
        return VideoSource.platformForHost(host)?.displayName ?: host.removePrefix("www.")
    }

    val displayTitle: String get() {
        val lines = title.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.all { char -> char == '.' } }
        val compact = lines.joinToString(" ").replace(whitespace, " ").trim()
        val caption = compact.replaceFirst(engagementPrefix, "").trim()
        return caption.ifEmpty { compact.ifEmpty { title.ifBlank { pageUrl } } }
    }
}

private val whitespace = Regex("""\s+""")
// Some Facebook page titles start with two counters before the actual caption.
private val engagementPrefix = Regex("""^[\d.,]+\s*[萬億]?\s*次觀看\s*[·•]\s*[\d.,]+\s*[萬億]?\s*個心情\s*[|｜]\s*""")

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
