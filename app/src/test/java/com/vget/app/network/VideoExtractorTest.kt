package com.vget.app.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class VideoExtractorTest {
    private fun response(request: Request, status: Int, body: String = "", location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody()).apply { if (location != null) header("Location", location) }.build()

    @Test fun sendsNavigationHeadersOnBothShareAndVideoRequests() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val navigation = request.header("Sec-Fetch-Dest") == "document"
                && request.header("Sec-Fetch-Mode") == "navigate"
                && request.header("Sec-Fetch-Site") == "none"
                && request.header("Upgrade-Insecure-Requests") == "1"
            when {
                !navigation -> response(request, 400, "<title>Error</title>Sorry, something went wrong.")
                request.url.encodedPath.startsWith("/share/") -> response(request, 302, location = "/reel/123/?rdid=example")
                else -> response(request, 200, """
                    <meta property="og:title" content="Public test video">
                    <script type="application/json">{"video":{"id":"123","videoDeliveryLegacyFields":{
                      "browser_native_hd_url":"https://video.xx.fbcdn.net/hd.mp4",
                      "browser_native_sd_url":"https://video.xx.fbcdn.net/sd.mp4"}}}</script>
                """.trimIndent())
            }
        }.build()
        val result = VideoExtractor(client).extractVideo("https://www.facebook.com/share/v/example-token/")
        assertEquals("Public test video", result.title)
        assertEquals(listOf("HD", "SD"), result.formats.map { it.quality })
        assertEquals("https://www.facebook.com/reel/123/?rdid=example", result.sourceUrl)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Cookie") == null })
    }

    @Test fun validatesRedirectHostBeforeSendingAnotherRequest() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            response(chain.request(), 302, location = "https://example.org/facebook.com/video")
        }.build()
        try {
            VideoExtractor(client).extractVideo("https://www.facebook.com/share/v/example-token/")
            fail("An off-site redirect must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, calls)
        }
    }

    @Test fun doesNotTreatAnHttpErrorPageAsVideoData() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 400, "<html><title>Error</title>Sorry, something went wrong.</html>")
        }.build()
        try {
            VideoExtractor(client).extractVideo("https://www.facebook.com/share/v/example-token/")
            fail("HTTP errors must remain visible")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("400"))
        }
    }
}
