package com.vget.app.network

import java.io.IOException
import java.util.Locale
import org.jsoup.nodes.Document

/** A page without media is not necessarily an invalid link or a network failure. */
internal class FacebookPageException(val reason: Reason) : IOException(reason.message) {
    enum class Reason(val message: String) {
        NO_MEDIA("Facebook 未提供這支影片的可下載資料。可能需要登入、影片已失效，或使用目前不支援的格式。請先用 Facebook 開啟原連結確認。"),
        AGE_RESTRICTED("Facebook 將這支影片標示為 18+，必須登入才能觀看。V-Get 目前無法下載需登入的影片；更新下載引擎無法解除此限制。"),
        LOGIN_REQUIRED("Facebook 要求登入才能觀看這支影片。V-Get 目前僅支援免登入的公開影片；在 Facebook App 登入不會授權 V-Get。")
    }

    val requiresLogin: Boolean get() = reason != Reason.NO_MEDIA

    companion object {
        fun from(document: Document): FacebookPageException {
            // The diagnostic request asks for English. Only explicit gate headings
            // count: regular public pages also contain generic login buttons/scripts.
            val headings = document.select("h1, h2, [role=heading]").map {
                it.text().replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
            }
            val reason = when {
                headings.any { it.startsWith("log in to view this 18+ content") } -> Reason.AGE_RESTRICTED
                headings.any { it in setOf("log in to see this content", "log in to view this content",
                    "log in to watch this video", "log in to continue") } -> Reason.LOGIN_REQUIRED
                else -> Reason.NO_MEDIA
            }
            return FacebookPageException(reason)
        }
    }
}
