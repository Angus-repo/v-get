package com.vget.app.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Downloads progressive Facebook/Threads media, then publishes it through MediaStore. */
class VideoDownloader(context: Context) {
    private val context = context.applicationContext
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun downloadVideo(videoUrl: String, source: VideoSource): Flow<DownloadProgress> = flow {
        val temporary = File.createTempFile("vget_", ".mp4", context.cacheDir)
        try {
            emit(DownloadProgress.Starting)
            val request = Request.Builder().url(videoUrl)
                .header("User-Agent", PlatformPageClient.USER_AGENT)
                .header("Accept", "*/*").header("Accept-Encoding", "identity")
                .header("Referer", source.platform.referer).build()
            val call = client.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("下載失敗（HTTP ${response.code}）")
                    val body = response.body ?: throw IOException("無法取得影片資料")
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    require(type.startsWith("video/") || type.contains("octet-stream")) { "取得的內容不是影片檔案" }
                    val total = body.contentLength()
                    var downloaded = 0L
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else 0
                                if (percent != lastPercent) {
                                    emit(DownloadProgress.Progress(percent, downloaded, total))
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                    if (downloaded == 0L || (total >= 0 && downloaded != total)) throw IOException("影片下載未完成，請重試")
                }
            } finally {
                call.cancel()
            }
            emit(DownloadProgress.Processing("正在儲存影片..."))
            val name = "${source.platform.name.lowercase()}_${UUID.randomUUID()}.mp4"
            emit(DownloadProgress.Completed(VideoStorage(context).save(temporary, name)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DownloadProgress.Error(DownloadErrors.message(e, source.platform)))
        } finally {
            temporary.delete()
        }
    }.flowOn(Dispatchers.IO)

    sealed class DownloadProgress {
        object Starting : DownloadProgress()
        data class Processing(val message: String) : DownloadProgress()
        data class Progress(val percentage: Int, val downloadedBytes: Long = 0, val totalBytes: Long = -1) : DownloadProgress()
        data class Completed(val video: SavedVideo) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}
