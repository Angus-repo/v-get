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

    fun download(video: VideoDetails, quality: VideoQuality, format: DownloadFormat = DownloadFormat.VIDEO): Flow<VideoDownloader.DownloadProgress> = channelFlow {
        val source = video.source
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "vget-$id")
        try {
            send(VideoDownloader.DownloadProgress.Processing("正在準備 ${source.platform.displayName} 下載..."))
            if (format == DownloadFormat.MP3 && quality.directUrl != null) runInterruptible(Dispatchers.IO) { initialize() }
            else prepareEngine()
            runInterruptible(Dispatchers.IO) { check(directory.mkdirs()) { "無法建立下載暫存資料夾" } }
            val file = withMediaFallback(quality,
                onRetry = { send(VideoDownloader.DownloadProgress.Processing("正在嘗試同畫質的備援來源...")) }) { candidate ->
                // Do not mix a failed replica's partial files with a fresh attempt.
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().destroyProcessById(id)
                    directory.listFiles()?.forEach { check(it.deleteRecursively()) { "無法清理下載暫存檔" } }
                }
                val manifest = File(directory, "completed.txt")
                val request = if (format == DownloadFormat.MP3) buildMp3Request(source, directory, manifest, candidate)
                    else buildRequest(source.url, directory, manifest, candidate)
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
                        if (format == DownloadFormat.MP3 && progress >= 100) {
                            trySend(VideoDownloader.DownloadProgress.Processing("正在轉換 MP3 音訊..."))
                        } else if (progress >= 0) trySend(VideoDownloader.DownloadProgress.Progress(progress.toInt().coerceIn(0, 99)))
                    }
                }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { completedFile(directory, manifest, format) }
            }
            currentCoroutineContext().ensureActive()
            send(VideoDownloader.DownloadProgress.Processing(if (format == DownloadFormat.MP3) "正在儲存 MP3 音訊..." else "正在儲存影片..."))
            val saved = withContext(Dispatchers.IO) {
                VideoStorage(context).save(file, video.title)
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

        internal fun buildMp3Request(source: VideoSource, directory: File, manifest: File, quality: VideoQuality): YoutubeDLRequest {
            require(!quality.silent) { "所選畫質沒有音軌，無法轉為 MP3" }
            val direct = quality.directUrl
            val selector = if (direct != null) "best" else quality.audioFormatSelector
                ?: quality.formatSelector?.substringAfterLast('+')
                ?: throw IllegalArgumentException("請先分析影片並選擇畫質")
            require(Regex("[A-Za-z0-9_.-]+").matches(selector)) { "無效的音訊格式" }
            return baseRequest(direct ?: source.url).apply {
                addOption("--abort-on-unavailable-fragments")
                addOption("--no-mtime")
                addOption("--no-simulate")
                addOption("--newline")
                addOption("-f", selector)
                addOption("--extract-audio")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "192K")
                if (direct != null) {
                    addOption("--referer", source.platform.referer)
                    addOption("--user-agent", PlatformPageClient.USER_AGENT)
                }
                addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
                addCommands(listOf("--print-to-file", "after_move:filepath", manifest.absolutePath))
            }
        }

        internal fun completedFile(directory: File, manifest: File, format: DownloadFormat = DownloadFormat.VIDEO): File {
            val path = manifest.takeIf { it.isFile }?.readLines()?.singleOrNull()
                ?: throw IOException("找不到完整下載檔；直播、預告或無影片的貼文目前不支援")
            val file = File(path).canonicalFile
            val extensions = if (format == DownloadFormat.MP3) setOf("mp3") else setOf("mp4", "webm", "mkv")
            if (file.parentFile != directory.canonicalFile || !file.isFile || file.length() == 0L ||
                file.extension.lowercase() !in extensions) {
                throw IOException("檔案下載或轉換未完成，請重試")
            }
            return file
        }
    }
}
