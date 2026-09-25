package com.vget.app.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.UUID

/** Downloads the chosen progressive format, then publishes it through MediaStore. */
class VideoDownloader(context: Context) {
    private val context = context.applicationContext
    private val client = DirectMediaClient()

    fun downloadVideo(quality: VideoQuality, source: VideoSource): Flow<DownloadProgress> = flow {
        val temporary = File.createTempFile("vget_", ".mp4", context.cacheDir)
        try {
            emit(DownloadProgress.Starting)
            client.download(quality, source, temporary,
                onRetry = { emit(DownloadProgress.Processing("正在嘗試同畫質的備援來源...")) },
                onProgress = { percent, downloaded, total -> emit(DownloadProgress.Progress(percent, downloaded, total)) })
            emit(DownloadProgress.Processing("正在儲存影片..."))
            val name = fileName(source.platform.name.lowercase())
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

    companion object {
        internal fun fileName(title: String): String {
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(60).ifBlank { "V-Get" }
            return "${safe}_${UUID.randomUUID().toString().take(8)}.mp4"
        }
    }
}
