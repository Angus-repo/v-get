package com.vget.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class VideoDownloadService(context: Context) {
    private val directDownloader = VideoDownloader(context)
    private val engine = YtDlpDownloader(context)
    private val pages = PlatformPageClient()

    fun download(source: VideoSource): Flow<VideoDownloader.DownloadProgress> = flow {
        when (source.platform) {
            VideoPlatform.YOUTUBE -> emitAll(engine.download(source))
            VideoPlatform.INSTAGRAM -> {
                val resolved = withContext(Dispatchers.IO) { pages.resolveInstagramShare(source) }
                emitAll(engine.download(resolved))
            }
            VideoPlatform.THREADS -> {
                val video = withContext(Dispatchers.IO) { pages.extractThreads(source) }
                emitAll(directDownloader.downloadVideo(video.videoUrl, source))
            }
            VideoPlatform.FACEBOOK -> {
                val video = withContext(Dispatchers.IO) { VideoExtractor().extractVideoUrl(source.url).getOrThrow() }
                emitAll(directDownloader.downloadVideo(video.videoUrl, source))
            }
        }
    }

    suspend fun updateEngine() { engine.updateEngine() }
}
