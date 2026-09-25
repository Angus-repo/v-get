package com.vget.app.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object FacebookUrl {
    private val pageHosts = setOf("facebook.com", "www.facebook.com", "m.facebook.com",
        "mobile.facebook.com", "mbasic.facebook.com", "web.facebook.com", "fb.com", "www.fb.com", "fb.watch", "www.fb.watch")

    fun parse(input: String): HttpUrl {
        val url = validatePageUrl(input)
        return url.newBuilder()
            .apply { if (url.host in setOf("m.facebook.com", "mobile.facebook.com", "mbasic.facebook.com")) host("www.facebook.com") }
            .build()
    }

    // Server-selected mobile hosts must be preserved. Reapplying input
    // normalization to Location can turn www -> m into www -> www forever.
    internal fun parseRedirect(input: String): HttpUrl = validatePageUrl(input)

    private fun validatePageUrl(input: String): HttpUrl {
        val raw = input.trim()
        val url = (if ("://" in raw) raw else "https://$raw").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("請輸入正確的 Facebook 影片連結")
        require(url.host in pageHosts && url.username.isEmpty() && url.password.isEmpty()
            && ((url.isHttps && url.port == 443) || (!url.isHttps && url.port == 80))) {
            "請輸入正確的 Facebook 影片連結"
        }
        require(url.queryParameter("comment_id") == null && url.queryParameter("reply_comment_id") == null) {
            "請複製留言影片本身的連結，留言串連結無法辨識指定影片"
        }
        return url.newBuilder().scheme("https").port(443).fragment(null).build()
    }

    fun fromSharedText(text: String): String {
        return Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', '。', '，', '）') }
            .firstOrNull { runCatching { parse(it) }.isSuccess } ?: text.trim()
    }

    fun videoId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return parsed.queryParameter("v")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            ?: Regex("/(?:videos|reel)/(?:[^/]+/)?(\\d+)(?:/|$)")
                .find(parsed.encodedPath)?.groupValues?.get(1)
    }

    fun mediaUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host
        return url.toString().takeIf {
            url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
                && (host == "fbcdn.net" || host.endsWith(".fbcdn.net")
                    || host == "facebook.com" || host.endsWith(".facebook.com"))
        }
    }
}
