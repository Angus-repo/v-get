package com.vget.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retry only explicit replicas of this format, never another resolution or note. */
internal suspend fun <T> withMediaFallback(
    quality: VideoQuality,
    onRetry: suspend () -> Unit = {},
    action: suspend (VideoQuality) -> T
): T {
    val choices = if (quality.directUrl == null) listOf(quality)
        else quality.directCandidates.map { quality.copy(directUrl = it) }
    for ((index, candidate) in choices.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (index > 0) onRetry()
        try {
            return action(candidate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (index == choices.lastIndex || !DownloadErrors.canRetryMedia(error)) throw error
        }
    }
    error("沒有可下載的影片來源")
}
