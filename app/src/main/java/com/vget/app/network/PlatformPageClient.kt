package com.vget.app.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PlatformPageClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val requests = SecurePageRequests(this.client)

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

    suspend fun extractXiaohongshu(source: VideoSource): VideoExtractor.VideoInfo {
        val (resolved, html) = fetch(source, USER_AGENT)
        val target = runCatching { VideoSource.parse(resolved) }.getOrNull()
            ?.takeIf { it.platform == VideoPlatform.XIAOHONGSHU && it.postId != null }
            ?: throw IOException("無法解析小紅書分享連結，請從小紅書重新複製影片筆記的連結")
        if (source.postId != null && source.postId != target.postId) {
            throw IOException("小紅書連結導向不同筆記，請重新複製原影片的分享連結")
        }
        return XiaohongshuPageParser.parse(html, target)
            ?: throw IOException("找不到這篇小紅書筆記的公開影片。純圖片、需要登入或驗證的內容目前不支援。")
    }

    private suspend fun fetch(source: VideoSource, agent: String): Pair<String, String> {
        var url = source.url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (source.platform == VideoPlatform.XIAOHONGSHU &&
                url.toHttpUrlOrNull()?.pathSegments
                    ?.any { it in setOf("login", "captcha", "website-login") } == true) {
                throw IOException("小紅書要求登入或驗證，目前僅支援免登入的公開影片。")
            }
            val response = requests.execute(
                Request.Builder().url(url).header("User-Agent", agent)
                    .header("Referer", source.platform.referer)
                    // Without navigation headers Threads may return only the app
                    // shell for an Android share link, with no post or media data.
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build(), source.platform)
            response.use {
                if (it.isRedirect) {
                    var next = it.header("Location")?.let(it.request.url::resolve)
                        ?: throw IOException("分享連結重新導向失敗")
                    // Some XHS app links still return HTTP Locations. Upgrade
                    // same-platform links before sending any network request.
                    if (source.platform == VideoPlatform.XIAOHONGSHU && next.scheme == "http" && next.port == 80 &&
                        VideoSource.platformForHost(next.host) == source.platform) {
                        next = next.newBuilder().scheme("https").port(443).build()
                    }
                    require(next.isHttps && VideoSource.platformForHost(next.host) == source.platform &&
                        next.username.isEmpty() && next.password.isEmpty() && next.port == 443) { "分享連結導向不支援的網站" }
                    url = next.toString()
                } else {
                    if (!it.isSuccessful) throw HttpStatusException(it.code, "${source.platform.displayName} 分享頁連線失敗")
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
