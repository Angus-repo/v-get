package com.vget.app.network

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun downloadVideo(videoUrl: String, fileName: String? = null): Flow<DownloadProgress> = flow {
        try {
            android.util.Log.e("VideoDownloader", "=== 開始下載影片 ===")
            android.util.Log.e("VideoDownloader", "URL 長度: ${videoUrl.length}")

            emit(DownloadProgress.Starting)

            val cleanUrl = videoUrl.trim()
            android.util.Log.e("VideoDownloader", "清理後的 URL 長度: ${cleanUrl.length}")

            val request = Request.Builder()
                .url(cleanUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "identity")
                .addHeader("Referer", "https://www.facebook.com/")
                .build()

            android.util.Log.e("VideoDownloader", "發送下載請求...")
            client.newCall(request).execute().use { response ->
                android.util.Log.e("VideoDownloader", "收到回應: ${response.code}")

                if (!response.isSuccessful) {
                    android.util.Log.e("VideoDownloader", "HTTP 錯誤: ${response.code}")
                    emit(DownloadProgress.Error("下載失敗: ${response.code}"))
                    return@flow
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                android.util.Log.e("VideoDownloader", "Content-Type: $contentType")

                val isVideoContent = contentType.isBlank() ||
                        contentType.startsWith("video/") ||
                        contentType.contains("mp4") ||
                        contentType.contains("octet-stream")

                if (!isVideoContent) {
                    val preview = response.peekBody(512).string()
                    android.util.Log.e("VideoDownloader", "回應並非影片格式，預覽: ${preview.take(200)}")
                    emit(DownloadProgress.Error("取得的內容不是影片檔案"))
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    android.util.Log.e("VideoDownloader", "回應 body 為空")
                    emit(DownloadProgress.Error("無法取得影片資料"))
                    return@flow
                }

                val contentLength = body.contentLength()
                android.util.Log.e("VideoDownloader", "檔案大小: $contentLength bytes")

                val downloadDir = getDownloadDirectory()
                android.util.Log.e("VideoDownloader", "下載目錄: ${downloadDir.absolutePath}")
                if (!downloadDir.exists()) {
                    val created = downloadDir.mkdirs()
                    android.util.Log.e("VideoDownloader", "建立目錄結果: $created")
                    if (!created) {
                        android.util.Log.e("VideoDownloader", "無法建立下載目錄")
                        emit(DownloadProgress.Error("無法建立下載目錄"))
                        return@flow
                    }
                }

                val file = File(downloadDir, fileName ?: generateFileName())
                android.util.Log.e("VideoDownloader", "目標檔案: ${file.absolutePath}")

                body.byteStream().use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        android.util.Log.e("VideoDownloader", "開始寫入檔案...")
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val progress = (totalBytesRead * 100 / contentLength).toInt()
                                emit(DownloadProgress.Progress(progress, totalBytesRead, contentLength))
                            }
                        }
                        android.util.Log.e("VideoDownloader", "寫入完成，總共: $totalBytesRead bytes")

                        if (contentLength > 0 && totalBytesRead < contentLength) {
                            android.util.Log.e("VideoDownloader", "檔案大小不一致，期望: $contentLength, 實際: $totalBytesRead")
                            emit(DownloadProgress.Error("影片下載未完成，請重試"))
                            return@flow
                        }
                    }
                }

                android.util.Log.e("VideoDownloader", "=== 下載完成 ===")
                emit(DownloadProgress.Completed(file.absolutePath))
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoDownloader", "下載過程發生異常: ${e.javaClass.simpleName}", e)
            android.util.Log.e("VideoDownloader", "錯誤訊息: ${e.message}")
            android.util.Log.e("VideoDownloader", "Stack trace: ${e.stackTraceToString()}")
            emit(DownloadProgress.Error(e.message ?: "未知錯誤: ${e.javaClass.simpleName}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun getDownloadDirectory(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, "V-Get")
    }

    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "facebook_video_$timestamp.mp4"
    }

    sealed class DownloadProgress {
        object Starting : DownloadProgress()
        data class Progress(
            val percentage: Int,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadProgress()
        data class Completed(val filePath: String) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}
