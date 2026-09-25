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
            val qualities = attachedVideoQualities(post, source).ifEmpty {
                // A Threads text post can display an Instagram reel inline. That
                // media has its own shortcode and is not in the post's versions.
                // Read only this explicit attachment of the matched post; never
                // recursively select videos from quoted posts or recommendations.
                post.objectValue("text_post_app_info")?.objectValue("linked_inline_media")
                    ?.let { attachedVideoQualities(it, source) }.orEmpty()
            }
            if (qualities.isNotEmpty()) {
                return VideoExtractor.VideoInfo(qualities.first().directUrl!!, source.url, document.title().ifBlank { "Threads Video" }, qualities)
            }
        }
        // A matched post with no attached video must not fall back to unrelated page media.
        if (matches.isNotEmpty()) return null
        if (canonicalSource?.postId != targetId) return null
        val metaUrl = sequenceOf("og:video:secure_url", "og:video:url", "og:video")
            .mapNotNull { document.selectFirst("meta[property=$it]")?.attr("content") }
            .firstOrNull { isMediaUrl(it) } ?: return null
        return VideoExtractor.VideoInfo(metaUrl, source.url, document.title().ifBlank { "Threads Video" }, listOf(directQuality(metaUrl, source)))
    }

    private fun attachedVideoQualities(media: JsonObject, source: VideoSource): List<VideoQuality> =
        videoQualities(media, source).ifEmpty {
            media.get("carousel_media")?.takeIf { it.isJsonArray }?.asJsonArray?.asSequence()
                ?.filter { it.isJsonObject }?.map { videoQualities(it.asJsonObject, source) }
                ?.firstOrNull { it.isNotEmpty() }.orEmpty()
        }

    private fun videoQualities(media: JsonObject, source: VideoSource): List<VideoQuality> {
        val versions = media.get("video_versions")?.takeIf { it.isJsonArray }?.asJsonArray
        val silent = media.get("has_audio")?.let { runCatching { !it.asBoolean }.getOrDefault(false) } ?: false
        val choices = versions?.asSequence()?.filter { it.isJsonObject }?.map { it.asJsonObject }
            ?.filter { it.text("url")?.let(::isMediaUrl) == true }
            ?.distinctBy { it.text("url")!!.toHttpUrlOrNull()!!.let { url -> url.host + url.encodedPath } }
            ?.mapIndexed { index, version -> directQuality(version.text("url")!!, source, "threads-$index",
                version.number("width").toInt().takeIf { it > 0 }, version.number("height").toInt().takeIf { it > 0 }, silent = silent) }
            ?.sortedByDescending { it.resolution ?: 0 }?.toList().orEmpty()
        if (choices.isNotEmpty()) {
            return choices.mapIndexed { index, quality ->
                if (choices.size > 1 && quality.resolution == null) quality.copy(description = "來源畫質 ${index + 1}（解析度未提供）") else quality
            }
        }
        return media.text("video_url")?.takeIf(::isMediaUrl)?.let { listOf(directQuality(it, source, silent = silent)) }.orEmpty()
    }

    private fun isMediaUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            (url.host == "cdninstagram.com" || url.host.endsWith(".cdninstagram.com") ||
                url.host == "fbcdn.net" || url.host.endsWith(".fbcdn.net"))
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.number(key: String): Long = get(key)?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
}
