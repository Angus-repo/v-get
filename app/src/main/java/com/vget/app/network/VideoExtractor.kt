package com.vget.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resolves public pages on the device, without login cookies or a parsing service. */
class VideoExtractor internal constructor(private val client: OkHttpClient) {
    constructor() : this(OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build())

    /** Adapts Facebook's validated formats to the multi-platform preview/download flow. */
    suspend fun extractVideoUrl(url: String): Result<VideoInfo> = try {
        val video = extractVideo(url)
        val qualities = FacebookQualityParser.fromVideo(video, VideoSource.parse(video.sourceUrl))
        Result.success(VideoInfo(qualities.first().directUrl!!, video.sourceUrl, video.title, qualities))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun extractVideo(url: String): FacebookVideoInfo {
        val page = fetchPage(FacebookUrl.parse(url), USER_AGENT)
        try {
            return FacebookPageParser.parse(page.html, page.url.toString())
        } catch (error: FacebookPageException) {
            if (error.reason != FacebookPageException.Reason.NO_MEDIA) throw error
            val targetId = FacebookUrl.videoId(page.url.toString()) ?: throw error
            // Desktop Reels can return only an app shell. The same public URL's
            // mobile page may contain media or an explicit login/18+ explanation.
            // One alternate request only; no cookies, account access or gate bypass.
            val mobile = fetchPage(page.url, PlatformPageClient.USER_AGENT, targetId, "en-US,en;q=0.9")
            // Keep the original target ID even if a login redirect loses it.
            return FacebookPageParser.parse(mobile.html, page.url.toString())
        }
    }

    private suspend fun fetchPage(start: HttpUrl, agent: String, expectedId: String? = null,
        language: String = "zh-TW,zh;q=0.9,en;q=0.7"): ResolvedPage {
        var current = start
        var targetId = expectedId ?: FacebookUrl.videoId(start.toString())
        val visited = mutableSetOf<HttpUrl>()
        val redirects = mutableListOf<String>()
        val stage = if (agent == USER_AGENT) "分享／影片頁面" else "行動版頁面"
        repeat(6) {
            if (!visited.add(current)) {
                throw redirectFailure("重新導向循環", "FB_REDIRECT_LOOP", stage, redirects, current)
            }
            val page = fetch(current, agent, language)
            if (page.redirect != null) {
                redirects += "${current.host}（${page.statusCode}）"
                current = FacebookUrl.parseRedirect(
                    current.resolve(page.redirect)?.toString()
                        ?: throw IOException("Facebook 連結重新導向失敗")
                )
                val redirectedId = FacebookUrl.videoId(current.toString())
                if (targetId != null && redirectedId != null && redirectedId != targetId) {
                    throw IOException("Facebook 連結導向不同影片，請重新複製原影片的分享連結")
                }
                if (targetId == null) targetId = redirectedId
            } else {
                return ResolvedPage(current, page.html)
            }
        }
        throw redirectFailure("重新導向次數過多", "FB_REDIRECT_LIMIT", stage, redirects, current)
    }

    private fun redirectFailure(reason: String, code: String, stage: String,
        redirects: List<String>, current: HttpUrl) = IOException(
        "Facebook $reason，請稍後再試。\n$code · $stage\n" +
            // Only validated public hostnames/statuses, never paths, tokens or cookies.
            (redirects + current.host).joinToString(" → ")
    )

    private suspend fun fetch(url: HttpUrl, agent: String, language: String): Page = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", agent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", language)
            // Facebook share/reel pages return HTTP 400 for this UA without
            // navigation metadata, even when the same public URL works in a browser.
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val page = response.use {
                        if (it.code in listOf(301, 302, 303, 307, 308)) {
                            Page(redirect = it.header("Location")
                                ?: throw IOException("Facebook 連結重新導向失敗"), statusCode = it.code)
                        } else {
                            if (!it.isSuccessful) throw HttpStatusException(it.code, "Facebook 暫時無法提供影片")
                            val body = it.body ?: throw IOException("無法取得影片頁面")
                            val source = body.source()
                            if (source.request(8L * 1024 * 1024 + 1)) {
                                throw IOException("影片頁面過大，請改用影片原始連結")
                            }
                            Page(html = source.readUtf8())
                        }
                    }
                    continuation.resume(page)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private data class Page(val html: String = "", val redirect: String? = null, val statusCode: Int = 200)
    private data class ResolvedPage(val url: HttpUrl, val html: String)

    // Shared result shape retained for the Threads and Xiaohongshu page parsers.
    data class VideoInfo(
        val videoUrl: String,
        val sourceUrl: String,
        val title: String,
        val qualities: List<VideoQuality> = emptyList()
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
