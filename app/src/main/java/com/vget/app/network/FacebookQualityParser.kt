package com.vget.app.network

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal object FacebookQualityParser {
    fun parse(html: String, source: VideoSource): List<VideoQuality> {
        fun value(key: String): String? {
            val raw = Regex("\"$key\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").find(html)?.groupValues?.get(1) ?: return null
            return runCatching { JsonParser.parseString(raw).asString }.getOrNull()
                ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
                ?.takeIf { it.toHttpUrlOrNull() != null }
        }
        val groups = listOf(
            Triple("hd", "高畫質（HD）", listOf("browser_native_hd_url", "playable_url_quality_hd", "hd_src")),
            Triple("sd", "標準畫質（SD）", listOf("browser_native_sd_url", "sd_src", "playable_url"))
        )
        val choices = groups.mapNotNull { (id, label, keys) ->
            keys.firstNotNullOfOrNull(::value)?.let { directQuality(it, source, id, description = label) }
        }
        if (choices.isNotEmpty()) return choices.distinctBy { it.directUrl }
        val document = Jsoup.parse(html)
        val url = sequenceOf("og:video:secure_url", "og:video:url", "og:video")
            .mapNotNull { document.selectFirst("meta[property=$it]")?.attr("content") }
            .firstOrNull { it.toHttpUrlOrNull() != null }
        return url?.let { listOf(directQuality(it, source)) }.orEmpty()
    }
}
