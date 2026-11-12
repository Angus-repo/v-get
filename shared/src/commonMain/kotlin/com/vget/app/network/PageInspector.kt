package com.vget.app.network

import com.vget.app.network.WebUrl.Companion.toHttpUrlOrNull
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import okio.IOException

/** Shared public-page workflow; the supplied HTTP engine must disable automatic redirects. */
class PageInspector(private val client: HttpClient) {
    suspend fun resolveInstagramShare(source: VideoSource): VideoSource {
        if (source.postId != null) return source
        val page = fetch(source, PageHeaders.MOBILE_USER_AGENT)
        parsedSource(page.url, VideoPlatform.INSTAGRAM)?.takeIf { it.postId != null }?.let { return it }
        val canonical = Ksoup.parse(page.html).selectFirst("meta[property=og:url]")?.attr("content")
        return canonical?.let { parsedSource(it, VideoPlatform.INSTAGRAM) }?.takeIf { it.postId != null }
            ?: throw IOException("無法解析 Instagram 分享連結，請改用貼文或 Reels 的完整網址")
    }

    suspend fun inspect(source: VideoSource): VideoDetails {
        val info = when (source.platform) {
            VideoPlatform.FACEBOOK -> facebook(source)
            VideoPlatform.THREADS -> threads(source)
            VideoPlatform.XIAOHONGSHU -> xiaohongshu(source)
            else -> throw IllegalArgumentException("此來源需要下載引擎，請先設定自架下載服務。")
        }
        return VideoDetails(source, info.title, info.qualities.ifEmpty {
            listOf(directQuality(info.videoUrl, source))
        })
    }

    private suspend fun facebook(source: VideoSource): PageVideoInfo {
        val page = fetch(source, PageHeaders.DESKTOP_USER_AGENT)
        val result = try {
            FacebookPageParser.parse(page.html, page.url)
        } catch (error: FacebookPageException) {
            if (error.reason != FacebookPageException.Reason.NO_MEDIA || FacebookUrl.videoId(page.url) == null) throw error
            val alternate = fetch(source.copy(url = page.url), PageHeaders.MOBILE_USER_AGENT, "en-US,en;q=0.9")
            FacebookPageParser.parse(alternate.html, page.url)
        }
        val qualities = FacebookQualityParser.fromVideo(result, source)
        return PageVideoInfo(qualities.first().directUrl!!, source.url, result.title, qualities)
    }

    private suspend fun threads(source: VideoSource): PageVideoInfo {
        var target = source
        for (agent in listOf(PageHeaders.MOBILE_USER_AGENT, PageHeaders.PREVIEW_USER_AGENT)) {
            val page = fetch(target, agent)
            if (ThreadsPageParser.isAudienceRestricted(page.html)) {
                throw IOException("Threads 限制部分使用者觀看這篇貼文（This content isn't available to everyone）。目前無法取得影片；限制原因可能與登入狀態、年齡或地區有關，請在 Threads 中確認。")
            }
            if (target.postId == null) target = parsedSource(page.url, source.platform) ?: target
            ThreadsPageParser.parse(page.html, target)?.let { return it }
        }
        throw IOException("找不到這篇 Threads 貼文的影片。請確認貼文公開且包含影片；需要登入的內容目前不支援。")
    }

    private suspend fun xiaohongshu(source: VideoSource): PageVideoInfo {
        val page = fetch(source, PageHeaders.MOBILE_USER_AGENT)
        val target = parsedSource(page.url, source.platform)?.takeIf { it.postId != null }
            ?: throw IOException("無法解析小紅書分享連結，請重新複製影片筆記的連結")
        require(source.postId == null || source.postId == target.postId) { "小紅書連結導向不同筆記" }
        return XiaohongshuPageParser.parse(page.html, target)
            ?: throw IOException("找不到這篇小紅書筆記的公開影片。純圖片、需要登入或驗證的內容目前不支援。")
    }

    private fun parsedSource(url: String, platform: VideoPlatform) =
        runCatching { VideoSource.parse(url) }.getOrNull()?.takeIf { it.platform == platform }

    private suspend fun fetch(source: VideoSource, agent: String, language: String = "zh-TW,zh;q=0.9,en;q=0.7"): Page {
        var current = source.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("無效的網址")
        var identity = if (source.platform == VideoPlatform.FACEBOOK) FacebookUrl.videoId(source.url) else source.postId
        val visited = mutableSetOf<String>()
        repeat(6) {
            require(visited.add(current.toString())) { "分享連結重新導向循環，請改用影片原始連結" }
            if (source.platform == VideoPlatform.XIAOHONGSHU &&
                current.pathSegments.any { it in setOf("login", "captcha", "website-login") }) {
                throw IOException("小紅書要求登入或驗證，目前僅支援免登入的公開影片。")
            }
            val response = client.prepareGet(current.toString()) {
                header(HttpHeaders.UserAgent, agent)
                header(HttpHeaders.Referrer, source.platform.referer)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                header(HttpHeaders.AcceptLanguage, language)
                header("Sec-Fetch-Dest", "document")
                header("Sec-Fetch-Mode", "navigate")
                header("Sec-Fetch-Site", "none")
                header("Upgrade-Insecure-Requests", "1")
            }.execute { response ->
                if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                    Page(response.headers[HttpHeaders.Location] ?: throw IOException("分享連結重新導向失敗"), "", true)
                } else {
                    if (response.status.value !in 200..299) throw IOException("${source.platform.displayName} 分享頁連線失敗（HTTP ${response.status.value}）")
                    Page(current.toString(), response.readBoundedText())
                }
            }
            if (!response.redirect) return response
            var next = current.resolve(response.url) ?: throw IOException("分享連結重新導向失敗")
            if (source.platform == VideoPlatform.FACEBOOK) {
                next = FacebookUrl.parseRedirect(next.toString())
            } else if (source.platform == VideoPlatform.XIAOHONGSHU && next.scheme == "http" && next.port == 80) {
                next = next.newBuilder().scheme("https").port(443).build()
            }
            require(next.isHttps && next.port == 443 && next.username.isEmpty() && next.password.isEmpty() &&
                VideoSource.platformForHost(next.host) == source.platform) { "分享連結導向不支援的網站" }
            val nextId = if (source.platform == VideoPlatform.FACEBOOK) FacebookUrl.videoId(next.toString())
                else parsedSource(next.toString(), source.platform)?.postId
            require(identity == null || nextId == null || identity == nextId) { "分享連結導向不同影片，請重新複製原連結" }
            if (identity == null) identity = nextId
            current = next
        }
        throw IOException("分享連結重新導向次數過多")
    }

    private data class Page(val url: String, val html: String, val redirect: Boolean = false)
}

internal suspend fun HttpResponse.readBoundedText(maximum: Int = 8 * 1024 * 1024): String {
    val channel = bodyAsChannel()
    val bytes = ByteArray(maximum + 1)
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count < 0) break
        size += count
    }
    require(size <= maximum) { "影片頁面過大，請改用影片原始連結" }
    return bytes.decodeToString(endIndex = size)
}

/** Only fixed app messages reach the UI; network errors must not expose tokens or signed URLs. */
internal fun safeFailure(error: Throwable): String {
    if (error is CancellationException) return "已取消"
    val message = error.message.orEmpty()
    return if (message.any { it in '\u4e00'..'\u9fff' } &&
        listOf("http://", "https://", "authorization", "xsec_token", "signature=").none { it in message.lowercase() }) {
        message.take(300)
    } else "連線或解析失敗，請檢查網路後重新分析。"
}
