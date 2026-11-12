package com.vget.app.network

data class PageVideoInfo(
    val videoUrl: String,
    val sourceUrl: String,
    val title: String,
    val qualities: List<VideoQuality> = emptyList()
)

object PageHeaders {
    const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val PREVIEW_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
}

internal expect fun normalizeNfc(value: String): String
