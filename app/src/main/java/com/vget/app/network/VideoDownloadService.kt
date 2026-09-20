package com.vget.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VideoDownloadService(context: Context) {
    private val directDownloader = VideoDownloader(context)
    private val engine = YtDlpDownloader(context)
    private val pages = PlatformPageClient()

    suspend fun inspect(source: VideoSource): VideoDetails = withContext(Dispatchers.IO) {
        when (source.platform) {
            VideoPlatform.YOUTUBE -> engine.inspect(source)
            VideoPlatform.INSTAGRAM -> engine.inspect(pages.resolveInstagramShare(source))
            VideoPlatform.THREADS -> details(pages.extractThreads(source), source)
            VideoPlatform.FACEBOOK -> details(VideoExtractor().extractVideoUrl(source.url).getOrThrow(), source)
        }
    }

    private fun details(info: VideoExtractor.VideoInfo, source: VideoSource) = VideoDetails(
        source, info.title, info.qualities.ifEmpty { listOf(directQuality(info.videoUrl, source)) }
    )

    fun download(video: VideoDetails, quality: VideoQuality): Flow<VideoDownloader.DownloadProgress> {
        require(quality in video.qualities) { "請重新分析並選擇有效畫質" }
        return if (quality.formatSelector != null) engine.download(video.source, quality)
        else directDownloader.downloadVideo(requireNotNull(quality.directUrl), video.source)
    }

    suspend fun updateEngine() { engine.updateEngine() }
}
