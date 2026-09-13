package com.vget.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vget.app.network.FacebookUrl
import com.vget.app.network.VideoDownloader
import com.vget.app.network.VideoExtractor
import com.vget.app.network.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    enum class Phase { RESTORING, IDLE, ANALYZING, READY, DOWNLOADING, CANCELLING, COMPLETE, ERROR }
    data class UiState(
        val input: String = "", val phase: Phase = Phase.RESTORING, val video: VideoInfo? = null,
        val selected: Int = 0, val bytes: Long = 0, val total: Long = -1,
        val message: String = "", val savedUri: Uri? = null, val fileName: String = ""
    ) {
        val busy get() = phase in listOf(Phase.RESTORING, Phase.ANALYZING, Phase.DOWNLOADING, Phase.CANCELLING)
    }

    private val extractor = VideoExtractor()
    private val downloader = VideoDownloader(application)
    private val mutableState = MutableStateFlow(UiState(input = savedState["input"] ?: ""))
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    @Volatile private var downloadId = -1L

    init {
        task = viewModelScope.launch {
            try {
                downloadId = downloader.previousId()
                if (downloadId >= 0) observeDownload() else update { copy(phase = Phase.IDLE) }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun input(text: String) {
        if (state.value.busy || text == state.value.input) return
        savedState["input"] = text
        mutableState.value = UiState(input = text, phase = Phase.IDLE)
    }

    fun select(index: Int) {
        if (!state.value.busy && index in (state.value.video?.formats?.indices ?: IntRange.EMPTY)) {
            update { copy(selected = index) }
        }
    }

    fun analyze() {
        if (state.value.busy) return
        val url = FacebookUrl.fromSharedText(state.value.input)
        input(url)
        update { copy(phase = Phase.ANALYZING, video = null, savedUri = null, message = "") }
        task = viewModelScope.launch {
            try {
                val video = withContext(Dispatchers.IO) { extractor.extractVideo(url) }
                update { copy(phase = Phase.READY, video = video, selected = 0) }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun download() {
        val current = state.value
        val video = current.video ?: return
        if (current.busy) return
        downloadId = -1
        update { copy(phase = Phase.DOWNLOADING, bytes = 0, total = -1, savedUri = null, message = "") }
        task = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    downloadId = downloader.enqueue(video.formats[current.selected], video.title)
                }
                observeDownload()
            } catch (e: Exception) { fail(e) }
        }
    }

    private suspend fun observeDownload() {
        update { copy(phase = Phase.DOWNLOADING) }
        downloader.observe(downloadId).collect { progress ->
            when (progress) {
                is VideoDownloader.DownloadProgress.Running -> update {
                    copy(bytes = progress.bytes, total = progress.total,
                        message = if (progress.waiting) "等待網路，系統將自動重試" else "")
                }
                is VideoDownloader.DownloadProgress.Completed -> update {
                    copy(phase = Phase.COMPLETE, savedUri = progress.uri, fileName = progress.fileName)
                }
                is VideoDownloader.DownloadProgress.Failed -> update {
                    copy(phase = Phase.ERROR, message = progress.message, video = null)
                }
            }
        }
    }

    fun cancel() {
        val phase = state.value.phase
        if (phase != Phase.ANALYZING && phase != Phase.DOWNLOADING) return
        val previous = task
        previous?.cancel()
        update { copy(phase = Phase.CANCELLING) }
        task = viewModelScope.launch {
            try {
                previous?.join()
                if (phase == Phase.DOWNLOADING) {
                    // Join the atomic enqueue before removing only this transfer.
                    if (downloadId >= 0) downloader.cancel(downloadId)
                    downloadId = -1
                }
                update { copy(phase = if (video == null) Phase.IDLE else Phase.READY, message = "", bytes = 0, total = -1) }
            } catch (e: Exception) { fail(e) }
        }
    }

    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        val message = when (error) {
            is UnknownHostException -> "無法連線，請檢查手機網路"
            is SocketTimeoutException -> "連線逾時，請稍後重試"
            is SecurityException -> "無法使用系統下載服務，請檢查儲存權限與系統設定"
            else -> error.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } }
                ?: "無法完成操作，請檢查網路後重試"
        }
        update { copy(phase = Phase.ERROR, message = message) }
    }

    private inline fun update(change: UiState.() -> UiState) { mutableState.value = state.value.change() }
}
