package com.vget.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in integration check; default unit tests never depend on Facebook availability. */
class VideoExtractorLiveTest {
    @Test fun resolvesProvidedPublicLinkAndReadsEachMp4Header() = runBlocking {
        val url = System.getProperty("vget.liveUrl").orEmpty()
        assumeTrue("Set the vgetLiveUrl Gradle property to run the live check", url.isNotBlank())
        val expectedAccess = System.getProperty("vget.liveExpectedAccess").orEmpty()
        if (expectedAccess.isNotBlank()) {
            val result = VideoExtractor().extractVideoUrl(url)
            assertTrue("Expected a Facebook access restriction", result.isFailure)
            val error = result.exceptionOrNull() as FacebookPageException
            assertEquals(FacebookPageException.Reason.valueOf(expectedAccess), error.reason)
            assertTrue(error.requiresLogin)
            println("Live check: Facebook explicitly requires ${error.reason}; no media download attempted")
            return@runBlocking
        }
        val info = VideoExtractor().extractVideo(url)
        assertTrue(info.formats.isNotEmpty())
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        info.formats.forEach { format ->
            val request = Request.Builder().url(format.url)
                .header("User-Agent", VideoExtractor.USER_AGENT)
                .header("Referer", "https://www.facebook.com/")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-31")
                .build()
            client.newCall(request).execute().use { response ->
                assertTrue("${format.quality}: HTTP ${response.code}", response.isSuccessful)
                val prefix = response.body!!.source().readByteArray(12)
                assertEquals("ftyp", String(prefix, 4, 4, Charsets.US_ASCII))
            }
        }
        println("Live check: resolved video ${FacebookUrl.videoId(info.sourceUrl)}, verified ${info.formats.map { it.quality }} MP4 headers")
    }
}
