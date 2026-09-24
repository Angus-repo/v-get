package com.vget.app.network

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaFallbackTest {
    private val source = VideoSource.parse("https://xhslink.cn/o/5ddMkHn9fgh")
    private val primary = "https://sns-video-v14.xhscdn.com/720.mp4?sign=fixture"
    private val backup = "https://sns-bak-v11.xhscdn.com/720.mp4?sign=fixture"
    private val quality = directQuality(primary, source, width = 720, height = 1280)
        .copy(directFallbackUrls = listOf(backup, primary, backup))

    @Test fun mp3RetriesUseTheSameFormatAndItsExactSignedReplicaUrl() = runBlocking {
        val requested = mutableListOf<String>()
        val result = withMediaFallback(quality) { candidate ->
            requested.add(candidate.directUrl!!)
            assertEquals(quality.id, candidate.id)
            assertEquals(720, candidate.resolution)
            if (requested.size == 1) throw Exception("ERROR: unable to download video data: HTTP Error 403")
            val command = YtDlpDownloader.buildMp3Request(source, File("job"), File("job/completed.txt"), candidate).buildCommand()
            assertTrue(command.contains(backup))
            assertTrue(command.contains("--extract-audio"))
            "complete"
        }
        assertEquals("complete", result)
        assertEquals(listOf(primary, backup), requested)
    }

    @Test fun conversionFailureIsNotRetriedAsIfItWereANetworkProblem() = runBlocking {
        var calls = 0
        try {
            withMediaFallback<Unit>(quality) { calls++; throw IllegalStateException("FFmpeg conversion failed") }
            fail("Conversion error must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }
}
