package com.vget.app.network

import java.util.Locale

internal object DownloadErrors {
    fun message(error: Exception, platform: VideoPlatform): String {
        val message = error.message.orEmpty()
        val lower = message.lowercase(Locale.ROOT)
        return when {
            listOf("private", "login", "log in", "sign in", "age-restricted", "cookies", "members-only").any { it in lower } ->
                "${platform.displayName} 要求登入，或影片有私人／年齡限制。目前僅支援不需登入的公開影片。"
            "429" in lower || "too many requests" in lower -> "${platform.displayName} 暫時限制下載次數，請稍後再試。"
            "timeout" in lower || "timed out" in lower -> "連線逾時，請檢查網路後再試。"
            "resolve host" in lower || "name resolution" in lower || "unable to resolve" in lower -> "無法連線到 ${platform.displayName}，請檢查網路。"
            "no space" in lower || "enospc" in lower -> "儲存空間不足，請清出空間後再試。"
            // Never expose raw engine logs or signed media URLs in the UI.
            message.any { it in '\u4e00'..'\u9fff' } && "http" !in lower -> message.take(240)
            else -> "${platform.displayName} 影片下載失敗。請確認連結有效且影片公開；YouTube／IG 可嘗試更新下載引擎。"
        }
    }
}
