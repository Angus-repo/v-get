package com.vget.app.network

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Only publish complete files; failed or cancelled copies leave no visible partial video. */
internal class VideoStorage(private val context: Context) {
    suspend fun save(file: File, name: String): SavedVideo {
        require(file.isFile && file.length() > 0) { "檔案下載未完成，請重試" }
        val mime = mediaMimeType(file.extension)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/V-Get")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("無法建立下載檔案")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("無法寫入下載資料夾")
                output.use { copy(file, it) }
                currentCoroutineContext().ensureActive()
                val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("無法完成檔案儲存")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return SavedVideo("${Environment.DIRECTORY_DOWNLOADS}/V-Get/$name", uri.toString(), mime)
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "V-Get")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("無法建立下載資料夾")
        val target = File(directory, name)
        val partial = File(directory, "$name.part")
        try {
            partial.outputStream().use { copy(file, it) }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(target)) throw IOException("無法完成檔案儲存")
        } finally {
            partial.delete()
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return SavedVideo(target.absolutePath, FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString(), mime)
    }

    private suspend fun copy(file: File, output: OutputStream) {
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }
}
