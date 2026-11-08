package com.vget.app.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** HEAD only, bounded concurrency and timeouts. Unknown stays unknown; never buffer a video. */
class PageMediaSizes(private val client: HttpClient) {
    suspend fun enrich(video: VideoDetails): VideoDetails = coroutineScope {
        val slots = Semaphore(3)
        video.copy(qualities = video.qualities.map { quality -> async {
            val url = quality.directUrl
            if (quality.fileSize != null || url == null) return@async quality
            val bytes = slots.withPermit {
                try {
                    client.prepareHead(url) {
                        header(HttpHeaders.UserAgent, PageHeaders.MOBILE_USER_AGENT)
                        header(HttpHeaders.Referrer, video.source.platform.referer)
                        header(HttpHeaders.AcceptEncoding, "identity")
                        timeout { requestTimeoutMillis = 5_000; socketTimeoutMillis = 4_000; connectTimeoutMillis = 4_000 }
                    }.execute { response ->
                        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                        if (response.status.value !in 200..299 ||
                            (type.isNotBlank() && !type.startsWith("video/") && type != "application/octet-stream")) null
                        else response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0 }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            }
            quality.copy(fileSize = bytes?.let { MediaFileSize(it) })
        } }.awaitAll())
    }
}
