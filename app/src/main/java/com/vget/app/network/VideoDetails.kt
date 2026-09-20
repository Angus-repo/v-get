package com.vget.app.network

import kotlin.math.min

data class MediaStream(val url: String, val headers: Map<String, String> = emptyMap(), val mimeType: String? = null)
data class VideoPreview(val video: MediaStream, val audio: MediaStream? = null)

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
    val preview: VideoPreview? = null
) {
    val resolution: Int? get() = if (width != null && height != null) min(width, height) else height ?: width
    val label: String get() = buildList {
        add(description ?: resolution?.let { "${it}p" } ?: "來源畫質（解析度未提供）")
        if (width != null && height != null) add("${width}×${height}")
        fps?.takeIf { it > 30 }?.let { add("${it}fps") }
        add(container.uppercase())
        codec?.let { add(it) }
        if (silent) add("無音軌")
    }.joinToString(" · ")
}

data class VideoDetails(val source: VideoSource, val title: String, val qualities: List<VideoQuality>) {
    init { require(qualities.isNotEmpty()) { "此影片沒有可下載的畫質" } }
}

data class SavedVideo(val filePath: String, val uri: String, val mimeType: String)

internal fun directQuality(
    url: String, source: VideoSource, id: String = "source", width: Int? = null,
    height: Int? = null, description: String? = null, silent: Boolean = false
) = VideoQuality(
    id = id, width = width, height = height, description = description, silent = silent, directUrl = url,
    preview = VideoPreview(MediaStream(url, mapOf("User-Agent" to PlatformPageClient.USER_AGENT,
        "Referer" to source.platform.referer), "video/mp4"))
)
