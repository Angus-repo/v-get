package com.vget.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Best-effort metadata probes. Never read a whole video to discover its size. */
internal class MediaSizeClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()

    suspend fun enrich(video: VideoDetails): VideoDetails = coroutineScope {
        val slots = Semaphore(3)
        video.copy(qualities = video.qualities.map { quality -> async {
            if (quality.fileSize != null || quality.directUrl == null) quality
            else slots.withPermit { quality.copy(fileSize = sizeOf(quality.directUrl, video.source)) }
        } }.awaitAll())
    }

    internal suspend fun sizeOf(url: String, source: VideoSource): MediaFileSize? {
        return try {
            runInterruptible {
                val base = Request.Builder().url(url).header("User-Agent", PlatformPageClient.USER_AGENT)
                    .header("Referer", source.platform.referer).header("Accept-Encoding", "identity")
                val head = client.newCall(base.head().build())
                try {
                    head.execute().use { response -> mediaLength(response)?.let { return@runInterruptible MediaFileSize(it) } }
                } finally { head.cancel() }
                // If HEAD is unsupported, request one byte and use Content-Range.
                // A server ignoring Range is closed immediately without reading its body.
                val range = client.newCall(base.get().header("Range", "bytes=0-0").build())
                try {
                    range.execute().use { response -> mediaLength(response)?.let { MediaFileSize(it) } }
                } finally { range.cancel() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { null }
    }

    private fun mediaLength(response: Response): Long? {
        if (!response.isSuccessful) return null
        val type = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
        if (type.isNotBlank() && !type.startsWith("video/") && type != "application/octet-stream") return null
        val value = if (response.code == 206) response.header("Content-Range")
            ?.let { Regex("bytes \\d+-\\d+/(\\d+)").matchEntire(it)?.groupValues?.get(1) }
        else response.header("Content-Length")
        return value?.toLongOrNull()?.takeIf { it > 0 }
    }
}
