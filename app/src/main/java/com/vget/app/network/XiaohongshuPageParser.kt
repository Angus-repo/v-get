package com.vget.app.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.StringReader
import kotlin.math.roundToInt

/** Only read streams attached to the requested note; never select recommendations. */
internal object XiaohongshuPageParser {
    private val initialState = Regex("(?:window\\.)?__INITIAL_STATE__\\s*=")

    fun parse(html: String, source: VideoSource): VideoExtractor.VideoInfo? {
        val target = source.postId ?: return null
        val document = Jsoup.parse(html)
        for (script in document.select("script")) {
            val data = script.data()
            val start = initialState.find(data)?.range?.last?.plus(1) ?: continue
            // Parse one JSON/JS object, without executing scripts. Gson accepts
            // unquoted undefined values and leaves string contents untouched.
            val state = runCatching {
                JsonReader(StringReader(data.substring(start))).use { JsonParser.parseReader(it).asJsonObject }
            }.getOrNull() ?: continue
            val desktopNote = state.obj("note")?.obj("noteDetailMap")?.obj(target)?.obj("note")
            val mobileNote = state.obj("noteData")?.obj("data")?.obj("noteData")
                ?.takeIf { it.text("noteId")?.lowercase() == target }
            val note = desktopNote ?: mobileNote ?: continue
            if (note.text("noteId")?.lowercase()?.let { it != target } == true) return null
            if (note.text("type")?.let { it != "video" } == true) return null
            val video = note.obj("video") ?: return null
            val media = video.obj("media") ?: return null
            val metadata = media.obj("video")
            if (metadata?.number("drmType")?.let { it != 0.0 } == true) return null
            val streams = media.obj("stream") ?: return null
            val choices = streams.entrySet().flatMap { (_, group) ->
                group.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }
                    ?.map { it.asJsonObject }.orEmpty()
            }.mapNotNull { format ->
                val backups = format.get("backupUrls")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }.orEmpty()
                val url = (listOfNotNull(format.text("masterUrl")) + backups).firstNotNullOfOrNull(::mediaUrl)
                    ?: return@mapNotNull null
                // Stream durations use milliseconds; the shared video metadata uses seconds.
                val duration = format.number("duration")?.div(1_000)?.takeIf { it > 0 }
                    ?: metadata?.number("duration")?.takeIf { it > 0 }
                val size = format.number("size")?.takeIf { it > 0 && it < Long.MAX_VALUE }?.toLong()
                    ?.takeIf { it > 0 }?.let { MediaFileSize(it) }
                val silent = format.text("audioCodec") == "none"
                directQuality(url, source, id = "xhs-${url.hashCode()}",
                    width = format.number("width")?.toInt()?.takeIf { it > 0 },
                    height = format.number("height")?.toInt()?.takeIf { it > 0 },
                    silent = silent, fileSize = size, durationSeconds = duration).copy(
                    fps = format.number("fps")?.roundToInt()?.takeIf { it > 0 },
                    codec = when (format.text("videoCodec")?.lowercase()) {
                        "h264", "avc", "avc1" -> "H.264"
                        "h265", "hevc", "hev1", "hvc1" -> "H.265"
                        else -> format.text("videoCodec")
                    })
            }.distinctBy { it.directUrl }.sortedWith(compareByDescending<VideoQuality> { it.resolution ?: 0 }
                .thenByDescending { if (it.codec == "H.264") 1 else 0 })
            if (choices.isEmpty()) return null
            val title = note.text("title")?.takeIf { it.isNotBlank() }
                ?: note.text("desc")?.takeIf { it.isNotBlank() }?.take(100) ?: "小紅書影片"
            return VideoExtractor.VideoInfo(choices.first().directUrl!!, source.url, title, choices)
        }
        return null
    }

    private fun mediaUrl(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443) ||
            !(url.host == "xhscdn.com" || url.host.endsWith(".xhscdn.com")) ||
            url.encodedPath.endsWith(".m3u8", ignoreCase = true)) return null
        return url.newBuilder().scheme("https").port(443).build().toString()
    }

    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(key: String) = get(key)?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }
}
