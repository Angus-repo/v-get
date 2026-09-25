package com.vget.app.network

import java.util.Locale
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object DownloadErrors {
    private val url = Regex("(?:https?|file)://\\S+", RegexOption.IGNORE_CASE)
    private val httpStatus = Regex("\\bHTTP(?:/\\d(?:\\.\\d)?)?(?:\\s+Error)?\\s*[:=]?\\s*(\\d{3})\\b", RegexOption.IGNORE_CASE)
    private fun causes(error: Throwable) = generateSequence(error) { it.cause }.take(6).toList()
    private fun safeText(error: Throwable) = causes(error).joinToString("\n") { url.replace(it.message.orEmpty(), "[連結]") }
    private fun status(error: Throwable): Int? = causes(error).filterIsInstance<HttpStatusException>().firstOrNull()?.statusCode
        ?: httpStatus.find(safeText(error))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..599 }

    internal fun canRetryMedia(error: Exception): Boolean {
        val chain = causes(error)
        val lower = safeText(error).lowercase(Locale.ROOT)
        if (chain.any { it is LocalMediaException || it is FileNotFoundException || it is SecurityException } ||
            listOf("no space", "enospc", "permission denied", "no audio", "conversion failed", "轉換", "找不到完整下載檔").any { it in lower }) return false
        status(error)?.let { return it !in setOf(401, 429) && it in 400..599 }
        return chain.any { it is IOException } ||
            listOf("unable to download", "timed out", "timeout", "connection reset", "resolve host", "name resolution").any { it in lower }
    }

    fun message(error: Exception, platform: VideoPlatform): String {
        val message = error.message.orEmpty()
        val chain = causes(error)
        val lower = safeText(error).lowercase(Locale.ROOT)
        val code = status(error)
        return when {
            error is PageConnectionException -> error.userMessage()
            code == 429 -> "${platform.displayName} 暫時限制請求次數（HTTP 429），請稍後再試。"
            code == 401 -> "${platform.displayName} 要求登入（HTTP 401），目前僅支援免登入的公開影片。"
            code == 403 -> "${platform.displayName} 拒絕這次請求（HTTP 403），請重新分析連結或稍後再試。"
            code == 404 || code == 410 -> "${platform.displayName} 找不到影片或連結已失效（HTTP $code），請重新複製分享連結。"
            code != null -> "${platform.displayName} 連線失敗（HTTP $code），請稍後再試。"
            listOf("no audio", "does not contain any audio", "audio stream not found").any { it in lower } ->
                "這支影片沒有可擷取的音軌，無法轉為 MP3。"
            "requested format" in lower -> "所選畫質目前無法取得，請重新分析連結後再選擇。"
            listOf("private", "login", "log in", "sign in", "age-restricted", "cookies", "members-only").any { it in lower } ->
                "${platform.displayName} 要求登入，或影片有私人／年齡限制。目前僅支援不需登入的公開影片。"
            "429" in lower || "too many requests" in lower -> "${platform.displayName} 暫時限制下載次數，請稍後再試。"
            chain.any { it is UnknownHostException } || "resolve host" in lower || "name resolution" in lower || "unable to resolve" in lower ->
                "無法解析 ${platform.displayName} 的網址，請檢查網路或切換 Wi-Fi／行動網路後再試。"
            chain.any { it is SSLException } || "certificate verify failed" in lower || "ssl handshake" in lower ->
                "無法與 ${platform.displayName} 建立安全連線，請確認手機日期時間與網路後再試。"
            chain.any { it is SocketTimeoutException } || "timeout" in lower || "timed out" in lower -> "連線逾時，請檢查網路後再試。"
            chain.any { it is SocketException } || "unexpected end of stream" in lower || "stream was reset" in lower ->
                "${platform.displayName} 的連線中斷，請重新分析或切換網路後再試。"
            "no space" in lower || "enospc" in lower -> "儲存空間不足，請清出空間後再試。"
            chain.any { it is SecurityException } || "permission denied" in lower -> "無法存取下載檔案，請確認 App 權限與可用儲存空間。"
            // Never expose raw engine logs or signed media URLs in the UI.
            message.any { it in '\u4e00'..'\u9fff' } && !url.containsMatchIn(message) &&
                listOf("xsec_token", "signature=", "authorization").none { it in lower } -> message.take(240)
            else -> "${platform.displayName} 處理失敗（${error.javaClass.simpleName.take(64)}），請重新分析連結後再試。" +
                if (platform in setOf(VideoPlatform.YOUTUBE, VideoPlatform.INSTAGRAM)) "也可嘗試更新下載引擎。" else ""
        }
    }
}
