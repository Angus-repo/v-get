package com.vget.app.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/** Preserve exact format IDs, audio pairing and HTTP headers for both playback and download. */
internal object YtDlpMetadataParser {
    private val formatId = Regex("[A-Za-z0-9_.-]+")

    fun parse(json: String, source: VideoSource): VideoDetails {
        val root = JsonParser.parseString(json).asJsonObject
        val info = firstVideo(root) ?: throw IllegalArgumentException("貼文中沒有可下載的影片")
        require(!info.flag("is_live") && info.text("live_status") !in setOf("is_live", "is_upcoming")) { "目前不支援直播或尚未開始的影片" }
        require(!info.flag("has_drm")) { "不支援受保護的影片" }
        val commonHeaders = headers(info.get("http_headers"))
        val rawFormats = info.get("formats")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty().ifEmpty { listOf(info) }
        val formats = rawFormats.filter {
            !it.flag("has_drm") && it.text("url")?.toHttpUrlOrNull() != null &&
                it.text("format_id")?.let(formatId::matches) == true
        }
        val audioFormats = formats.filter { it.text("vcodec") == "none" && it.text("acodec") != "none" }
        val candidates = formats.filter(::isVideo).map { video ->
            val audio = if (video.text("acodec") == "none") audioFormats.maxWithOrNull(
                compareBy<JsonObject> { it.number("language_preference") ?: -1.0 }
                    .thenBy { if (it.text("ext") == if (video.text("ext") == "webm") "webm" else "m4a") 1 else 0 }
                    .thenBy { it.number("abr") ?: it.number("tbr") ?: 0.0 }
            ) else null
            val selector = listOfNotNull(video.text("format_id"), audio?.text("format_id")).joinToString("+")
            val videoPreview = stream(video, commonHeaders)
            val audioPreview = audio?.let { stream(it, commonHeaders) }
            val ext = video.text("ext").orEmpty()
            val container = when {
                ext == "mp4" && (audio == null || audio.text("ext") == "m4a") -> "mp4"
                ext == "webm" && (audio == null || audio.text("ext") == "webm") -> "webm"
                else -> "mkv"
            }
            val duration = video.number("duration") ?: info.number("duration")
            val videoSize = fileSize(video, duration)
            val audioSize = audio?.let { fileSize(it, duration) }
            val size = if (audio == null) videoSize else if (videoSize != null && audioSize != null &&
                videoSize.bytes <= Long.MAX_VALUE - audioSize.bytes) {
                // Remuxing changes container overhead, so the sum is an estimate.
                MediaFileSize(videoSize.bytes + audioSize.bytes, approximate = true)
            } else null
            Candidate(VideoQuality(
                id = selector, width = video.positiveInt("width"), height = video.positiveInt("height"),
                fps = video.number("fps")?.takeIf { it > 0 }?.roundToInt(), container = container,
                codec = codecName(video.text("vcodec")),
                silent = video.text("acodec") == "none" && audio == null,
                formatSelector = selector,
                audioFormatSelector = audio?.text("format_id") ?: video.text("format_id")
                    ?.takeIf { video.text("acodec") != "none" },
                fileSize = size, durationSeconds = duration?.takeIf { it > 0 },
                preview = if (videoPreview != null && (audio == null || audioPreview != null)) VideoPreview(videoPreview, audioPreview) else null
            ), video.number("tbr") ?: 0.0)
        }
        // Prefer broadly playable codecs within the same resolution/FPS/container.
        // Never invent resolutions, upscale, or silently substitute another resolution.
        val choices = candidates.groupBy { candidate ->
            val q = candidate.quality
            listOf(q.width, q.height, q.fps, q.container, if (q.resolution == null) q.id else null)
        }.values.map { group ->
            group.maxWith(compareBy<Candidate> { codecPreference(it.quality.codec) }
                .thenBy { if (it.quality.preview != null) 1 else 0 }.thenBy { it.bitrate }).quality
        }.sortedWith(compareByDescending<VideoQuality> { it.resolution ?: 0 }.thenByDescending { it.fps ?: 0 }
            .thenByDescending { codecPreference(it.codec) }.thenBy { it.container })
        require(choices.isNotEmpty()) { "此影片沒有可下載的畫質，請重新分析或更新下載引擎" }
        val multipleUnknown = choices.count { it.resolution == null } > 1
        var unknownIndex = 0
        val labelledChoices = choices.map { quality ->
            if (multipleUnknown && quality.resolution == null) {
                quality.copy(description = "來源畫質 ${++unknownIndex}（解析度未提供）")
            } else quality
        }
        return VideoDetails(source, info.text("title") ?: "${source.platform.displayName} Video", labelledChoices)
    }

    private fun fileSize(format: JsonObject, duration: Double?): MediaFileSize? {
        fun valid(value: Double?) = value?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE }?.toLong()?.takeIf { it > 0 }
        valid(format.number("filesize"))?.let { return MediaFileSize(it) }
        valid(format.number("filesize_approx"))?.let { return MediaFileSize(it, true) }
        val bitrate = format.number("tbr") ?: format.number("abr")
        return if (duration != null && duration > 0 && bitrate != null && bitrate > 0)
            valid(duration * bitrate * 1_000 / 8)?.let { MediaFileSize(it, true) } else null
    }

    private fun firstVideo(info: JsonObject): JsonObject? {
        val entries = info.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
        if (entries != null) return entries.asSequence().filter { it.isJsonObject }
            .mapNotNull { firstVideo(it.asJsonObject) }.firstOrNull()
        val formats = info.get("formats")?.takeIf { it.isJsonArray }?.asJsonArray
        return info.takeIf { formats?.any { it.isJsonObject && isVideo(it.asJsonObject) } == true || isVideo(info) }
    }

    private fun isVideo(format: JsonObject): Boolean = format.text("vcodec") != "none" &&
        format.text("ext") in setOf("mp4", "webm", "mkv", "mov", "m4v") &&
        format.text("url")?.toHttpUrlOrNull() != null

    private fun stream(format: JsonObject, common: Map<String, String>): MediaStream? {
        val url = format.text("url") ?: return null
        val protocol = format.text("protocol") ?: "https"
        if (protocol !in setOf("http", "https", "m3u8", "m3u8_native")) return null
        val mime = if (protocol.startsWith("m3u8")) "application/x-mpegURL" else null
        return MediaStream(url, common + headers(format.get("http_headers")), mime)
    }

    private fun headers(value: JsonElement?): Map<String, String> = value?.takeIf { it.isJsonObject }
        ?.asJsonObject?.entrySet()?.mapNotNull { (key, value) ->
            val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (text == null || '\r' in text || '\n' in text || !Regex("[A-Za-z0-9-]+").matches(key)) null else key to text
        }?.toMap().orEmpty()

    private fun codecName(codec: String?): String? = when {
        codec == null -> null
        codec.startsWith("avc") || codec.startsWith("h264") -> "H.264"
        codec.startsWith("hev") || codec.startsWith("hvc") -> "H.265"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "VP9"
        codec.startsWith("av01") -> "AV1"
        else -> null
    }
    private fun codecPreference(codec: String?) = when (codec) { "H.264" -> 4; "H.265" -> 3; "VP9" -> 2; "AV1" -> 1; else -> 0 }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(key: String): Double? = get(key)?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }
    private fun JsonObject.positiveInt(key: String): Int? = number(key)?.toInt()?.takeIf { it > 0 }
    private fun JsonObject.flag(key: String): Boolean = get(key)?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
    private data class Candidate(val quality: VideoQuality, val bitrate: Double)
}
