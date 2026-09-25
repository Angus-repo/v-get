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
        var current = FacebookUrl.parse(url)
        repeat(6) {
            val page = fetch(current)
            if (page.redirect != null) {
                current = FacebookUrl.parse(
                    current.resolve(page.redirect)?.toString()
                        ?: throw IOException("Facebook 連結重新導向失敗")
                )
            } else {
                return FacebookPageParser.parse(page.html, current.toString())
            }
        }
        throw IOException("Facebook 連結重新導向次數過多，請複製影片原始連結")
    }

    private suspend fun fetch(url: HttpUrl): Page = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7")
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
                                ?: throw IOException("Facebook 連結重新導向失敗"))
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

    private data class Page(val html: String = "", val redirect: String? = null)

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
