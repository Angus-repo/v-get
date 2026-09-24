package com.vget.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class HttpStatusException(val statusCode: Int, action: String) : IOException("$action（HTTP $statusCode）")
internal class LocalMediaException(cause: IOException) : IOException("無法寫入影片暫存檔", cause)

/** All attempts replace the temporary file; incomplete downloads are never published. */
internal class DirectMediaClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun download(
        quality: VideoQuality, source: VideoSource, temporary: File,
        onRetry: suspend () -> Unit = {},
        onProgress: suspend (Int, Long, Long) -> Unit = { _, _, _ -> }
    ) {
        try {
            withMediaFallback(quality, onRetry) { candidate ->
                val request = Request.Builder().url(requireNotNull(candidate.directUrl))
                    .header("User-Agent", PlatformPageClient.USER_AGENT)
                    .header("Accept", "*/*").header("Accept-Encoding", "identity")
                    .header("Referer", source.platform.referer).build()
                val call = client.newCall(request)
                try {
                    runInterruptible(Dispatchers.IO) { call.execute() }.use { response ->
                        if (!response.isSuccessful) throw HttpStatusException(response.code, "影片來源回應失敗")
                        val body = response.body ?: throw IOException("無法取得影片資料")
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (!type.startsWith("video/") && !type.contains("octet-stream")) {
                            throw IOException("影片來源回傳的內容不是影片檔案，請重新分析")
                        }
                        val total = body.contentLength()
                        val rangeLength = if (response.code == 206) {
                            val range = Regex("bytes 0-(\\d+)/(\\d+)")
                                .matchEntire(response.header("Content-Range").orEmpty())
                            val end = range?.groupValues?.get(1)?.toLongOrNull()
                            val complete = range?.groupValues?.get(2)?.toLongOrNull()
                            if (end == null || complete == null || complete <= 0 || end != complete - 1) {
                                throw IOException("影片來源只回傳部分內容，請重新分析後再試")
                            }
                            complete
                        } else null
                        var downloaded = 0L
                        var lastPercent = -1
                        val output = try { temporary.outputStream() } catch (e: IOException) { throw LocalMediaException(e) }
                        output.use {
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = runInterruptible(Dispatchers.IO) {
                                        val size = input.read(buffer)
                                        if (size > 0) try { output.write(buffer, 0, size) }
                                            catch (e: IOException) { throw LocalMediaException(e) }
                                        size
                                    }
                                    if (count < 0) break
                                    downloaded += count
                                    val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else 0
                                    if (percent != lastPercent) {
                                        onProgress(percent, downloaded, total)
                                        lastPercent = percent
                                    }
                                }
                            }
                        }
                        val expected = candidate.fileSize?.takeUnless { it.approximate }?.bytes
                        if (downloaded == 0L || (total >= 0 && downloaded != total) ||
                            (rangeLength != null && downloaded != rangeLength) ||
                            (expected != null && downloaded != expected)) {
                            throw IOException("影片下載未完成，請重新分析後再試")
                        }
                    }
                } finally { call.cancel() }
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}
