package com.vget.app.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

data class VideoFormat(val quality: String, val url: String)
data class FacebookVideoInfo(val title: String, val sourceUrl: String, val formats: List<VideoFormat>)

/** Only named progressive video fields are accepted, never arbitrary CDN assets. */
object FacebookPageParser {
    private val fields = linkedMapOf(
        "browser_native_hd_url" to "HD", "playable_url_quality_hd" to "HD", "hd_src" to "HD",
        "browser_native_sd_url" to "SD", "playable_url" to "SD", "sd_src" to "SD"
    )
    private data class Candidate(val id: String?, val formats: List<VideoFormat>)

    fun parse(html: String, sourceUrl: String): FacebookVideoInfo {
        val document = Jsoup.parse(html)
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf(String::isNotBlank) ?: document.title().ifBlank { "Facebook 影片" }
        val targetId = FacebookUrl.videoId(sourceUrl)
            ?: document.selectFirst("meta[property=og:url]")?.attr("content")?.let(FacebookUrl::videoId)
        val candidates = mutableListOf<Candidate>()
        document.select("script").forEach { script ->
            val raw = script.data().trim()
            if (raw.startsWith("{") || raw.startsWith("[")) {
                runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { visit(it, null, candidates, 0) }
            }
        }

        val matching = candidates.filter { targetId != null && it.id == targetId }
        val formats = when {
            matching.isNotEmpty() -> matching.flatMap { it.formats }
            // Do not return a recommendation when the requested video is unavailable.
            targetId != null && candidates.any { it.id != null } -> emptyList()
            candidates.size == 1 -> candidates.single().formats
            candidates.isNotEmpty() -> emptyList()
            else -> legacyFormats(html, document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
                .map { it.attr("content") })
        }.distinctBy { it.url }.distinctBy { it.quality }.sortedBy { if (it.quality == "HD") 0 else 1 }

        if (formats.isEmpty()) throw FacebookPageException.from(document)
        return FacebookVideoInfo(title.take(200), sourceUrl, formats)
    }

    private fun visit(node: JsonElement, inheritedId: String?, out: MutableList<Candidate>, depth: Int) {
        if (depth > 100) return
        if (node.isJsonArray) node.asJsonArray.forEach { visit(it, inheritedId, out, depth + 1) }
        if (!node.isJsonObject) return
        val obj = node.asJsonObject
        val hasVideoFields = fields.keys.any(obj::has) || obj.has("videoDeliveryLegacyFields")
        val id = obj.text("video_id") ?: if (hasVideoFields || obj.text("__typename") == "Video") {
            obj.text("id") ?: inheritedId
        } else inheritedId
        val formats = fields.mapNotNull { (key, quality) ->
            obj.text(key)?.let { media(it) }?.let { VideoFormat(quality, it) }
        }
        if (formats.isNotEmpty()) out += Candidate(id, formats)
        obj.entrySet().forEach { (_, value) -> visit(value, id, out, depth + 1) }
    }

    private fun legacyFormats(html: String, metaUrls: List<String>): List<VideoFormat> {
        val found = fields.flatMap { (key, quality) ->
            Regex("\"$key\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").findAll(html)
                .mapNotNull { match ->
                    runCatching { JsonParser.parseString(match.groupValues[1]).asString }.getOrNull()
                        ?.let { media(it) }?.let { VideoFormat(quality, it) }
                }.toList()
        }.distinctBy { it.url }
        // Multiple unrelated objects without IDs cannot be associated with the requested post.
        if (found.groupBy { it.quality }.any { it.value.size > 1 }) return emptyList()
        if (found.isNotEmpty()) return found
        return metaUrls.mapNotNull { media(it) }.distinct().singleOrNull()
            ?.let { listOf(VideoFormat("MP4", it)) } ?: emptyList()
    }

    private fun media(value: String): String? = FacebookUrl.mediaUrl(Parser.unescapeEntities(value, false))
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
