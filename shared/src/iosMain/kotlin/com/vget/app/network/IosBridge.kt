package com.vget.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small stable Swift boundary; parsing and routing remain Kotlin, IO is cancellable. */
class IosBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 20_000; socketTimeoutMillis = 30_000 }
    }
    private val json = Json { encodeDefaults = true }

    fun inspect(input: String, serviceUrl: String, serviceToken: String,
        success: (String) -> Unit, failure: (String) -> Unit): IosRequest {
        val job = scope.launch {
            try {
                val source = VideoSource.parse(input)
                val details = when (source.platform) {
                    VideoPlatform.YOUTUBE, VideoPlatform.INSTAGRAM -> {
                        require(serviceUrl.isNotBlank()) { "iOS 的 YouTube／Instagram 解析需要自架下載服務，請先在設定中填入服務網址及權杖。" }
                        val resolved = if (source.platform == VideoPlatform.INSTAGRAM) PageInspector(client).resolveInstagramShare(source) else source
                        EngineService(client, serviceUrl, serviceToken).inspect(resolved)
                    }
                    else -> PageInspector(client).inspect(source)
                }
                val payload = json.encodeToString(IosVideo(details.title, details.source.url, details.source.platform.name,
                    details.qualities.map { IosQuality(it.id, it.label, it.mp3Size?.label, it.container, it.silent,
                        it.formatSelector, it.audioFormatSelector, it.directCandidates, it.preview) }))
                withContext(Dispatchers.Main) { success(payload) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { withContext(Dispatchers.Main) { failure(safeFailure(error)) } }
        }
        return IosRequest(job)
    }

    fun fileName(title: String, extension: String, copyNumber: Int): String =
        DownloadFileNames.fromTitle(title, extension, copyNumber)

    fun close() { scope.cancel(); client.close() }
}

class IosRequest internal constructor(private val job: Job) {
    fun cancel() { job.cancel() }
}

@Serializable private data class IosVideo(val title: String, val sourceUrl: String, val platform: String, val qualities: List<IosQuality>)
@Serializable private data class IosQuality(val id: String, val label: String, val mp3Size: String?, val container: String,
    val silent: Boolean, val formatSelector: String?, val audioFormatSelector: String?, val directCandidates: List<String>, val preview: VideoPreview?)
