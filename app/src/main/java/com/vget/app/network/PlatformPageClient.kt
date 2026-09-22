package com.vget.app.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PlatformPageClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun extractThreads(source: VideoSource): VideoExtractor.VideoInfo {
        var pageSource = source
        // Public link-preview pages sometimes contain media omitted from the regular HTML.
        for (agent in listOf(USER_AGENT, PREVIEW_USER_AGENT)) {
            currentCoroutineContext().ensureActive()
            val (resolved, html) = fetch(pageSource, agent)
            // A share token is not a post ID. Keep the validated redirect identity
            // even when the returned page omits its canonical metadata.
            if (pageSource.postId == null) {
                pageSource = runCatching { VideoSource.parse(resolved) }.getOrNull()
                    ?.takeIf { it.platform == VideoPlatform.THREADS && it.postId != null }
                    ?: pageSource
            }
            ThreadsPageParser.parse(html, pageSource)?.let { return it }
        }
        throw IOException("找不到這篇 Threads 貼文的影片。請確認貼文公開且包含影片；需要登入的內容目前不支援。")
    }

    suspend fun resolveInstagramShare(source: VideoSource): VideoSource {
        if (source.postId != null) return source
        val (resolved, html) = fetch(source, USER_AGENT)
        val candidate = runCatching { VideoSource.parse(resolved) }.getOrNull()
            ?.takeIf { it.platform == VideoPlatform.INSTAGRAM && it.postId != null }
        if (candidate != null) return candidate
        val canonical = org.jsoup.Jsoup.parse(html).selectFirst("meta[property=og:url]")?.attr("content")
        return canonical?.let { runCatching { VideoSource.parse(it) }.getOrNull() }
            ?.takeIf { it.platform == VideoPlatform.INSTAGRAM && it.postId != null }
            ?: throw IOException("無法解析 Instagram 分享連結，請改用貼文或 Reels 的完整網址")
    }

    private suspend fun fetch(source: VideoSource, agent: String): Pair<String, String> {
        var url = source.url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val response = runInterruptible {
                client.newCall(Request.Builder().url(url).header("User-Agent", agent)
                    .header("Referer", source.platform.referer)
                    // Without navigation headers Threads may return only the app
                    // shell for an Android share link, with no post or media data.
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()).execute()
            }
            response.use {
                if (it.isRedirect) {
                    val next = it.header("Location")?.let(it.request.url::resolve)
                        ?: throw IOException("分享連結重新導向失敗")
                    require(next.isHttps && VideoSource.platformForHost(next.host) == source.platform &&
                        next.username.isEmpty() && next.password.isEmpty() && next.port == 443) { "分享連結導向不支援的網站" }
                    url = next.toString()
                } else {
                    if (!it.isSuccessful) throw IOException("${source.platform.displayName} 連線失敗（HTTP ${it.code}）")
                    return url to runInterruptible { it.body?.string().orEmpty() }
                }
            }
        }
        throw IOException("分享連結重新導向次數過多")
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private const val PREVIEW_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    }
}
