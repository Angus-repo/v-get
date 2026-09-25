package com.vget.app.network

/** Reuses target-video validation for the quality selector and in-app preview. */
internal object FacebookQualityParser {
    fun parse(html: String, source: VideoSource): List<VideoQuality> =
        fromVideo(FacebookPageParser.parse(html, source.url), source)

    fun fromVideo(video: FacebookVideoInfo, source: VideoSource): List<VideoQuality> =
        video.formats.map { format ->
            val (id, description) = when (format.quality) {
                "HD" -> "hd" to "高畫質（HD）"
                "SD" -> "sd" to "標準畫質（SD）"
                else -> "source" to null
            }
            directQuality(format.url, source, id, description = description)
        }
}
