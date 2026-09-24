package com.vget.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VideoDownloadService(context: Context) {
    private val directDownloader = VideoDownloader(context)
    private val engine = YtDlpDownloader(context)
    private val pages = PlatformPageClient()
    private val sizes = MediaSizeClient()

    suspend fun inspect(source: VideoSource): VideoDetails = withContext(Dispatchers.IO) {
        when (source.platform) {
            VideoPlatform.YOUTUBE -> engine.inspect(source)
            VideoPlatform.INSTAGRAM -> engine.inspect(pages.resolveInstagramShare(source))
            VideoPlatform.THREADS -> details(pages.extractThreads(source), source)
            VideoPlatform.FACEBOOK -> details(VideoExtractor().extractVideoUrl(source.url).getOrThrow(), source)
            VideoPlatform.XIAOHONGSHU -> details(pages.extractXiaohongshu(source), source)
        }.let { sizes.enrich(it) }
    }

    private fun details(info: VideoExtractor.VideoInfo, source: VideoSource) = VideoDetails(
        source, info.title, info.qualities.ifEmpty { listOf(directQuality(info.videoUrl, source)) }
    )

    fun download(video: VideoDetails, quality: VideoQuality, format: DownloadFormat = DownloadFormat.VIDEO): Flow<VideoDownloader.DownloadProgress> {
        require(quality in video.qualities) { "請重新分析並選擇有效畫質" }
        return if (format == DownloadFormat.MP3 || quality.formatSelector != null) engine.download(video.source, quality, format)
        else directDownloader.downloadVideo(quality, video.source)
    }

    suspend fun updateEngine() { engine.updateEngine() }
}
