package com.vget.app.network

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID

/** The Android system owns transfers, including retries and background execution. */
class VideoDownloader(context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    // Device-local IDs must not be restored onto another installation.
    private val preferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun enqueue(format: VideoFormat, title: String): Long {
        val url = FacebookUrl.mediaUrl(format.url)
            ?: throw IllegalArgumentException("無效的影片下載連結")
        val fileName = fileName(title)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("V-Get · ${format.quality}")
            .setMimeType("video/mp4")
            .addRequestHeader("User-Agent", VideoExtractor.USER_AGENT)
            .addRequestHeader("Referer", "https://www.facebook.com/")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "V-Get/$fileName")
        val id = manager.enqueue(request)
        // Persist the system ID before returning so an Activity/process restart can reattach.
        if (!preferences.edit().putLong("download_id", id).putString("file_name", fileName).commit()) {
            manager.remove(id)
            throw IllegalStateException("無法保存下載工作，請再試一次")
        }
        return id
    }

    suspend fun previousId(): Long = withContext(Dispatchers.IO) { preferences.getLong("download_id", -1) }

    fun observe(id: Long) = flow {
        while (true) {
            val state = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use DownloadProgress.Failed("下載已由系統移除")
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri == null) DownloadProgress.Failed("找不到下載檔案，請重新下載")
                        else DownloadProgress.Completed(uri, preferences.getString("file_name", "影片.mp4")!!)
                    }
                    DownloadManager.STATUS_FAILED -> DownloadProgress.Failed(
                        failureMessage(number(DownloadManager.COLUMN_REASON).toInt())
                    )
                    else -> DownloadProgress.Running(
                        number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        number(DownloadManager.COLUMN_STATUS).toInt() == DownloadManager.STATUS_PAUSED
                    )
                }
            } ?: DownloadProgress.Failed("無法讀取系統下載狀態")
            if (state is DownloadProgress.Failed) cancel(id)
            emit(state)
            if (state !is DownloadProgress.Running) break
            delay(600)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO + NonCancellable) {
        manager.remove(id) // Also removes the partial file.
        if (preferences.getLong("download_id", -1) == id) {
            preferences.edit().remove("download_id").remove("file_name").commit()
        }
        Unit
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手機儲存空間不足，請釋放空間後重試"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "找不到可用的儲存空間"
        401, 403, 404, 410 -> "影片連結已失效或無法存取，請重新解析"
        else -> "系統下載失敗，請檢查網路並重新解析影片"
    }

    sealed class DownloadProgress {
        data class Running(val bytes: Long, val total: Long, val waiting: Boolean) : DownloadProgress()
        data class Completed(val uri: Uri, val fileName: String) : DownloadProgress()
        data class Failed(val message: String) : DownloadProgress()
    }

    companion object {
        internal fun fileName(title: String): String {
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(60).ifBlank { "Facebook" }
            return "${safe}_${UUID.randomUUID().toString().take(8)}.mp4"
        }
    }
}
