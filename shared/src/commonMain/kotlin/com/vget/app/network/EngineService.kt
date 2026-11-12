package com.vget.app.network

import com.vget.app.network.WebUrl.Companion.toHttpUrlOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No default service, cookies, third-party endpoint or automatic URL submission. */
class EngineService(private val client: HttpClient, endpoint: String, private val token: String) {
    private val base = endpoint.trim().trimEnd('/')
    init {
        val url = base.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.port == 443 && '?' !in base && '#' !in base) { "下載服務必須是 HTTPS 網址，且不能包含帳密或查詢參數" }
        require(token.isNotBlank() && '\r' !in token && '\n' !in token) { "請設定下載服務的存取權杖" }
    }

    suspend fun inspect(source: VideoSource): VideoDetails {
        val json = client.preparePost("$base/v1/inspect") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InspectRequest(source.url)))
        }.execute { response ->
            require(response.status.value == 200) { "下載服務解析失敗（HTTP ${response.status.value}），請確認服務設定與來源連結" }
            response.readBoundedText()
        }
        return YtDlpMetadataParser.parse(json, source)
    }

    @Serializable private data class InspectRequest(val url: String)
}
