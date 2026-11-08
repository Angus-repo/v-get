package com.vget.app.network

import kotlin.math.min
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadFormat { VIDEO, MP3 }

@Serializable
data class MediaFileSize(val bytes: Long, val approximate: Boolean = false) {
    init { require(bytes > 0) }
    val label: String get() = (if (approximate) "約 " else "") + formatBytes(bytes)
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${decimal(bytes / 1_000.0, 1)} KB"
    bytes < 1_000_000_000 -> "${decimal(bytes / 1_000_000.0, 1)} MB"
    else -> "${decimal(bytes / 1_000_000_000.0, 2)} GB"
}

private fun decimal(value: Double, digits: Int): String {
    val scale = if (digits == 1) 10 else 100
    val rounded = kotlin.math.round(value * scale).toLong()
    return "${rounded / scale}.${(rounded % scale).toString().padStart(digits, '0')}"
}

fun mediaMimeType(extension: String): String = when (extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    else -> "video/mp4"
}

@Serializable
data class MediaStream(val url: String, val headers: Map<String, String> = emptyMap(), val mimeType: String? = null)
@Serializable
data class VideoPreview(val video: MediaStream, val audio: MediaStream? = null)

@Serializable
data class VideoQuality(
    val id: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val container: String = "mp4",
    val codec: String? = null,
    val description: String? = null,
    val silent: Boolean = false,
    val formatSelector: String? = null,
    val directUrl: String? = null,
    val preview: VideoPreview? = null,
    val fileSize: MediaFileSize? = null,
    val durationSeconds: Double? = null,
    val audioFormatSelector: String? = null,
    val directFallbackUrls: List<String> = emptyList()
) {
    val directCandidates: List<String> get() =
        if (directUrl == null) emptyList() else (listOf(directUrl) + directFallbackUrls).distinct()
    val mp3Size: MediaFileSize? get() = durationSeconds?.takeIf { it.isFinite() && it > 0 }
        ?.times(192_000.0 / 8)?.takeIf { it < Long.MAX_VALUE }?.toLong()?.takeIf { it > 0 }
        ?.let { MediaFileSize(it, approximate = true) }
    val resolution: Int? get() = if (width != null && height != null) min(width, height) else height ?: width
    val label: String get() = buildList {
        add(description ?: resolution?.let { "${it}p" } ?: "來源畫質（解析度未提供）")
        if (width != null && height != null) add("${width}×${height}")
        fps?.takeIf { it > 30 }?.let { add("${it}fps") }
        add(container.uppercase())
        codec?.let { add(it) }
        if (silent) add("無音軌")
        add(fileSize?.label ?: "容量未提供")
    }.joinToString(" · ")
}

@Serializable
data class VideoDetails(val source: VideoSource, val title: String, val qualities: List<VideoQuality>) {
    init { require(qualities.isNotEmpty()) { "此影片沒有可下載的畫質" } }
}

@Serializable
data class SavedVideo(val filePath: String, val uri: String, val mimeType: String)

fun directQuality(
    url: String, source: VideoSource, id: String = "source", width: Int? = null,
    height: Int? = null, description: String? = null, silent: Boolean = false,
    fileSize: MediaFileSize? = null, durationSeconds: Double? = null
) = VideoQuality(
    id = id, width = width, height = height, description = description, silent = silent, directUrl = url,
    fileSize = fileSize, durationSeconds = durationSeconds,
    preview = VideoPreview(MediaStream(url, mapOf("User-Agent" to PageHeaders.MOBILE_USER_AGENT,
        "Referer" to source.platform.referer), "video/mp4"))
)
