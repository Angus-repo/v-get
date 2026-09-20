package com.vget.app.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Select the requested post, never a video from replies or recommendations. */
internal object ThreadsPageParser {
    fun parse(html: String, source: VideoSource): VideoExtractor.VideoInfo? {
        val document = Jsoup.parse(html)
        val canonical = document.selectFirst("meta[property=og:url]")?.attr("content")
            ?: document.selectFirst("link[rel=canonical]")?.attr("href")
        val canonicalSource = canonical?.let { runCatching { VideoSource.parse(it) }.getOrNull() }
            ?.takeIf { it.platform == VideoPlatform.THREADS }
        val targetId = source.postId ?: canonicalSource?.postId ?: return null
        val matches = mutableListOf<JsonObject>()

        fun visit(value: JsonElement, depth: Int = 0) {
            if (depth > 100) return
            when {
                value.isJsonArray -> value.asJsonArray.forEach { visit(it, depth + 1) }
                value.isJsonObject -> {
                    val obj = value.asJsonObject
                    if (obj.text("code") == targetId || obj.text("shortcode") == targetId) matches.add(obj)
                    obj.entrySet().forEach { visit(it.value, depth + 1) }
                }
            }
        }
        document.select("script[type=application/json]").forEach { script ->
            runCatching { JsonParser.parseString(script.data()) }.getOrNull()?.let { visit(it) }
        }
        for (post in matches) {
            val url = videoUrl(post) ?: post.get("carousel_media")?.takeIf { it.isJsonArray }
                ?.asJsonArray?.asSequence()?.filter { it.isJsonObject }
                ?.mapNotNull { videoUrl(it.asJsonObject) }?.firstOrNull()
            if (url != null) {
                return VideoExtractor.VideoInfo(url, source.url, document.title().ifBlank { "Threads Video" })
            }
        }
        // A matched text/image-only post must not fall back to unrelated page media.
        if (matches.isNotEmpty()) return null
        if (canonicalSource?.postId != targetId) return null
        val metaUrl = sequenceOf("og:video:secure_url", "og:video:url", "og:video")
            .mapNotNull { document.selectFirst("meta[property=$it]")?.attr("content") }
            .firstOrNull { isMediaUrl(it) } ?: return null
        return VideoExtractor.VideoInfo(metaUrl, source.url, document.title().ifBlank { "Threads Video" })
    }

    private fun videoUrl(media: JsonObject): String? {
        val versions = media.get("video_versions")?.takeIf { it.isJsonArray }?.asJsonArray
        return versions?.asSequence()?.filter { it.isJsonObject }?.map { it.asJsonObject }
            ?.filter { it.text("url")?.let(::isMediaUrl) == true }
            ?.sortedByDescending { (it.number("width") * it.number("height")) }
            ?.firstOrNull()?.text("url")
            ?: media.text("video_url")?.takeIf(::isMediaUrl)
    }

    private fun isMediaUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            (url.host == "cdninstagram.com" || url.host.endsWith(".cdninstagram.com") ||
                url.host == "fbcdn.net" || url.host.endsWith(".fbcdn.net"))
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(key: String): Long = get(key)?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
}
