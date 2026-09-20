package com.vget.app.network

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal class YtDlpDownloader(context: Context) {
    private val context = context.applicationContext

    private fun initialize() {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    suspend fun updateEngine() = runInterruptible(Dispatchers.IO) {
        initialize()
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
    }

    private suspend fun prepareEngine() {
        val needsUpdate = runInterruptible(Dispatchers.IO) {
            initialize()
            YoutubeDL.getInstance().version(context) == null
        }
        if (needsUpdate) {
            try {
                updateEngine()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Retain the bundled engine if the update endpoint is unavailable.
            }
        }
    }

    suspend fun inspect(source: VideoSource): VideoDetails {
        val id = UUID.randomUUID().toString()
        try {
            prepareEngine()
            return runInterruptible(Dispatchers.IO) {
                val response = YoutubeDL.getInstance().execute(buildInspectRequest(source.url), id)
                YtDlpMetadataParser.parse(response.out, source)
            }
        } finally {
            YoutubeDL.getInstance().destroyProcessById(id)
        }
    }

    fun download(source: VideoSource, quality: VideoQuality): Flow<VideoDownloader.DownloadProgress> = channelFlow {
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "vget-$id")
        try {
            send(VideoDownloader.DownloadProgress.Processing("正在準備 ${source.platform.displayName} 下載..."))
            prepareEngine()
            runInterruptible(Dispatchers.IO) { check(directory.mkdirs()) { "無法建立下載暫存資料夾" } }
            val manifest = File(directory, "completed.txt")
            val request = buildRequest(source.url, directory, manifest, quality)
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
                    if (progress >= 0) trySend(VideoDownloader.DownloadProgress.Progress(progress.toInt().coerceIn(0, 99)))
                }
            }
            currentCoroutineContext().ensureActive()
            send(VideoDownloader.DownloadProgress.Processing("正在儲存影片..."))
            val saved = withContext(Dispatchers.IO) {
                val file = completedFile(directory, manifest)
                VideoStorage(context).save(file, "${source.platform.name.lowercase()}_$id.${file.extension}")
            }
            send(VideoDownloader.DownloadProgress.Completed(saved))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(VideoDownloader.DownloadProgress.Error(DownloadErrors.message(e, source.platform)))
        } finally {
            YoutubeDL.getInstance().destroyProcessById(id)
            directory.deleteRecursively()
        }
    }

    companion object {
        private fun baseRequest(url: String) = YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("--playlist-items", "1")
            addOption("--match-filters", "!is_live & !is_upcoming")
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
        }

        internal fun buildInspectRequest(url: String) = baseRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
        }

        internal fun buildRequest(url: String, directory: File, manifest: File, quality: VideoQuality): YoutubeDLRequest {
            val selector = requireNotNull(quality.formatSelector) { "請先分析影片並選擇畫質" }
            require(Regex("[A-Za-z0-9_.-]+(?:\\+[A-Za-z0-9_.-]+)?").matches(selector)) { "無效的影片畫質" }
            require(quality.container in setOf("mp4", "webm", "mkv")) { "不支援的影片格式" }
            return baseRequest(url).apply {
                addOption("--abort-on-unavailable-fragments")
                addOption("--no-mtime")
                addOption("--no-simulate")
                addOption("--newline")
                // Exact selection: failure asks for re-analysis instead of choosing another quality.
                addOption("-f", selector)
                addOption("--merge-output-format", quality.container)
                addOption("-o", File(directory, "video.%(ext)s").absolutePath)
                addCommands(listOf("--print-to-file", "after_move:filepath", manifest.absolutePath))
            }
        }

        internal fun completedFile(directory: File, manifest: File): File {
            val path = manifest.takeIf { it.isFile }?.readLines()?.singleOrNull()
                ?: throw IOException("找不到完整影片；直播、預告或無影片的貼文目前不支援")
            val file = File(path).canonicalFile
            if (file.parentFile != directory.canonicalFile || !file.isFile || file.length() == 0L ||
                file.extension.lowercase() !in setOf("mp4", "webm", "mkv")) {
                throw IOException("影片下載未完成，請重試")
            }
            return file
        }
    }
}
