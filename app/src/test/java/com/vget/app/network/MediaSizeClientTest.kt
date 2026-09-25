package com.vget.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaSizeClientTest {
    private val source = VideoSource.parse("https://threads.com/@user/post/ABC")
    private val url = "https://video.cdninstagram.com/selected.mp4?sig=A%2FB+C"
    private fun response(request: Request, code: Int = 200, vararg headers: Pair<String, String>) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body("".toResponseBody()).apply { headers.forEach { header(it.first, it.second) } }.build()

    @Test fun readsLengthWithTheSameUrlAndHeadersAsTheDownload() = runBlocking {
        val client = MediaSizeClient(OkHttpClient.Builder().addInterceptor {
            val request = it.request()
            assertEquals("HEAD", request.method)
            assertEquals(url, request.url.toString())
            assertEquals("identity", request.header("Accept-Encoding"))
            assertEquals(source.platform.referer, request.header("Referer"))
            response(request, 200, "Content-Type" to "video/mp4", "Content-Length" to "1234567")
        }.build())
        assertEquals(MediaFileSize(1_234_567), client.sizeOf(url, source))
    }

    @Test fun rangeFallbackUsesTotalFileSizeInsteadOfTheOneByteBody() = runBlocking {
        var calls = 0
        val client = MediaSizeClient(OkHttpClient.Builder().addInterceptor {
            calls++
            if (it.request().method == "HEAD") response(it.request(), 405)
            else {
                assertEquals("bytes=0-0", it.request().header("Range"))
                response(it.request(), 206, "Content-Type" to "video/mp4", "Content-Length" to "1",
                    "Content-Range" to "bytes 0-0/9876543")
            }
        }.build())
        assertEquals(9_876_543L, client.sizeOf(url, source)?.bytes)
        assertEquals(2, calls)
    }

    @Test fun unavailableSizeDoesNotBlockTheVideoAndHtmlIsNotAFileSize() = runBlocking {
        val client = MediaSizeClient(OkHttpClient.Builder().addInterceptor {
            response(it.request(), 200, "Content-Type" to "text/html", "Content-Length" to "5000")
        }.build())
        val result = client.enrich(VideoDetails(source, "fixture", listOf(directQuality(url, source))))
        assertNull(result.qualities.single().fileSize)
        assertEquals(url, result.qualities.single().directUrl)
    }

    @Test fun existingMetadataAvoidsAnyNetworkProbe() = runBlocking {
        val client = MediaSizeClient(OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build())
        val quality = directQuality(url, source, fileSize = MediaFileSize(500_000, true))
        assertEquals(quality, client.enrich(VideoDetails(source, "fixture", listOf(quality))).qualities.single())
    }
}
