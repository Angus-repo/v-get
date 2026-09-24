package com.vget.app.network

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectMediaClientTest {
    @get:Rule val folder = TemporaryFolder()
    private val source = VideoSource.parse("https://xhslink.cn/o/5ddMkHn9fgh")
    private val primary = "https://sns-video-v14.xhscdn.com/selected.mp4?sign=A%2FB+C"
    private val backup = "https://sns-bak-v11.xhscdn.com/selected.mp4?sign=A%2FB+C"
    private val payload = "complete-video"
    private val quality = directQuality(primary, source, width = 720, height = 1280,
        fileSize = MediaFileSize(payload.length.toLong())).copy(directFallbackUrls = listOf(backup))
    private fun client(handle: (Request) -> Response) = DirectMediaClient(
        OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build())
    private fun response(request: Request, status: Int = 200, body: ResponseBody = payload.toResponseBody(),
        type: String = "video/mp4", range: String? = null) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type", type)
        .apply { range?.let { header("Content-Range", it) } }.body(body).build()

    @Test fun forbiddenPrimaryUsesOnlyTheSelectedFormatsPublishedReplica() = runBlocking {
        val urls = mutableListOf<String>(); var retries = 0; val file = folder.newFile()
        client { request ->
            urls.add(request.url.toString())
            assertEquals(source.platform.referer, request.header("Referer"))
            assertEquals(PlatformPageClient.USER_AGENT, request.header("User-Agent"))
            assertEquals("identity", request.header("Accept-Encoding"))
            response(request, if (urls.size == 1) 403 else 200)
        }.download(quality, source, file, onRetry = { retries++ })
        assertEquals(listOf(primary, backup), urls)
        assertEquals(1, retries)
        assertEquals(payload, file.readText())
    }

    @Test fun truncatedFirstAttemptIsReplacedRatherThanAppendedToTheBackup() = runBlocking {
        var calls = 0; val file = folder.newFile().apply { writeText("previous-data") }
        val shortBody = object : ResponseBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = 100L
            override fun source() = Buffer().writeUtf8("partial")
        }
        client { request -> response(request, body = if (++calls == 1) shortBody else payload.toResponseBody()) }
            .download(quality, source, file)
        assertEquals(2, calls)
        assertEquals(payload, file.readText())
    }

    @Test fun aPartial206ResponseCannotBeSavedAsTheWholeVideo() = runBlocking {
        for (range in listOf("bytes 0-3/100", "bytes 0-99/100")) {
            var calls = 0; val file = folder.newFile()
            client { request ->
                if (++calls == 1) response(request, 206, "part".toResponseBody(), range = range)
                else response(request)
            }.download(quality.copy(fileSize = null), source, file)
            assertEquals(2, calls)
            assertEquals(payload, file.readText())
        }
    }

    @Test fun incorrectMetadataLengthAndHtmlResponsesAreNotPublished() = runBlocking {
        for (html in listOf(false, true)) {
            val file = folder.newFile()
            try {
                client { request -> response(request, type = if (html) "text/html" else "video/mp4") }
                    .download(quality.copy(fileSize = MediaFileSize(100)), source, file)
                fail("Incomplete or non-video data must fail")
            } catch (_: IOException) { }
            assertFalse(file.exists())
        }
    }

    @Test fun everyReplicaFailingRetainsTheFinalHttpStatusAndDeletesTemporaryData() = runBlocking {
        var calls = 0; val file = folder.newFile().apply { writeText("partial") }
        try {
            client { request -> response(request, if (++calls == 1) 503 else 403) }.download(quality, source, file)
            fail("All replicas failed")
        } catch (error: HttpStatusException) { assertEquals(403, error.statusCode) }
        assertEquals(2, calls)
        assertFalse(file.exists())
    }

    @Test fun loginAndRateLimitsStopWithoutTryingAnotherReplica() = runBlocking {
        for (status in listOf(401, 429)) {
            var calls = 0
            try {
                client { request -> calls++; response(request, status) }.download(quality, source, folder.newFile())
                fail("Restricted response must fail")
            } catch (error: HttpStatusException) { assertEquals(status, error.statusCode) }
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationDoesNotRetryAndRemovesTheTemporaryFile() = runBlocking {
        var calls = 0; val file = folder.newFile()
        try {
            client { request -> calls++; response(request) }.download(quality, source, file,
                onProgress = { _, _, _ -> throw CancellationException("cancelled") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
        assertFalse(file.exists())
    }

    @Test fun aLocalWriteFailureDoesNotDownloadAnotherReplica() = runBlocking {
        var calls = 0; val file = File(folder.root, "missing/target.mp4")
        try {
            client { request -> calls++; response(request) }.download(quality, source, file)
            fail("Local write must fail")
        } catch (_: LocalMediaException) { }
        assertEquals(1, calls)
    }
}
