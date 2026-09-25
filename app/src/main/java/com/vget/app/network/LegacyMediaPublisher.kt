package com.vget.app.network

import java.io.File
import java.io.IOException

/** Android 7–9: reserve the final name atomically before publishing a complete copy. */
internal object LegacyMediaPublisher {
    fun publish(partial: File, title: String, extension: String): File {
        require(partial.isFile && partial.length() > 0) { "檔案下載未完成，請重試" }
        val directory = requireNotNull(partial.parentFile)
        for (copyNumber in 0..9999) {
            val target = File(directory, DownloadFileNames.fromTitle(title, extension, copyNumber))
            // createNewFile is atomic: a concurrent download cannot claim this path.
            if (!target.createNewFile()) continue
            try {
                if (!partial.renameTo(target)) throw IOException("無法完成檔案儲存")
                return target
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }
        throw IOException("同名下載檔案過多，請先整理下載資料夾")
    }
}
