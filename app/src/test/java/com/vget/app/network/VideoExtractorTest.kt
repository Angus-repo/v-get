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

    @Test fun adaptsOnlyTheRequestedVideoToDownloadAndPreviewChoices() = runBlocking {
        val signedHd = "https://video.xx.fbcdn.net/hd.mp4?sig=A%2FB+C&token=1"
        val sd = "https://video.xx.fbcdn.net/sd.mp4"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, """
                <meta property="og:title" content="Requested video">
                <script type="application/json">[
                  {"id":"999","hd_src":"https://video.xx.fbcdn.net/recommendation.mp4"},
                  {"id":"123","hd_src":"$signedHd","sd_src":"$sd"}
                ]</script>
            """.trimIndent())
        }.build()
        val info = VideoExtractor(client).extractVideoUrl("https://www.facebook.com/reel/123/").getOrThrow()
        assertEquals("Requested video", info.title)
        assertEquals(signedHd, info.videoUrl)
        assertEquals(listOf("hd", "sd"), info.qualities.map { it.id })
        assertEquals(listOf(signedHd, sd), info.qualities.map { it.directUrl })
        info.qualities.forEach { quality ->
            val preview = requireNotNull(quality.preview)
            assertEquals(quality.directUrl, preview.video.url)
            assertEquals("https://www.facebook.com/", preview.video.headers["Referer"])
            assertNull(quality.height)
            assertNull(quality.fileSize)
            assertFalse(quality.silent)
        }
    }

    @Test fun adapterDoesNotFallBackToUnrelatedMediaWhenTheTargetIsMissing() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, """
                <script type="application/json">{"id":"999","hd_src":"https://video.xx.fbcdn.net/other.mp4"}</script>
                <meta property="og:video" content="https://video.xx.fbcdn.net/other.mp4">
            """.trimIndent())
        }.build()
        val result = VideoExtractor(client).extractVideoUrl("https://www.facebook.com/reel/123/")
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test fun adapterPreservesHttpStatusForTheSharedErrorPanel() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 403, "<html>Forbidden</html>")
        }.build()
        val result = VideoExtractor(client).extractVideoUrl("https://www.facebook.com/reel/123/")
        val error = result.exceptionOrNull() as HttpStatusException
        assertEquals(403, error.statusCode)
        assertTrue(DownloadErrors.message(error, VideoPlatform.FACEBOOK).contains("HTTP 403"))
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
