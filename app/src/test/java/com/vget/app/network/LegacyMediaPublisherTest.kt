package com.vget.app.network

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyMediaPublisherTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun partial(directory: File, content: String) =
        File.createTempFile(".vget-", ".part", directory).apply { writeText(content) }

    @Test fun savesTheTitleAndDoesNotOverwritePreviouslyDownloadedMedia() {
        val directory = temporary.newFolder()
        val first = LegacyMediaPublisher.publish(partial(directory, "first"), "海邊旅行", "mp4")
        val second = LegacyMediaPublisher.publish(partial(directory, "second"), "海邊旅行", "mp4")
        val audio = LegacyMediaPublisher.publish(partial(directory, "audio"), "海邊旅行", "mp3")
        assertEquals("海邊旅行.mp4", first.name)
        assertEquals("海邊旅行 (1).mp4", second.name)
        assertEquals("海邊旅行.mp3", audio.name)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals("audio", audio.readText())
        assertEquals(3, directory.listFiles()!!.size)
    }

    @Test fun handlesFilenameSanitizationCollisionsAndExistingDirectories() {
        val directory = temporary.newFolder()
        File(directory, "旅行_分享.mp4").mkdir()
        val first = LegacyMediaPublisher.publish(partial(directory, "slash"), "旅行/分享", "mp4")
        val second = LegacyMediaPublisher.publish(partial(directory, "colon"), "旅行:分享", "mp4")
        assertEquals("旅行_分享 (1).mp4", first.name)
        assertEquals("旅行_分享 (2).mp4", second.name)
        assertTrue(File(directory, "旅行_分享.mp4").isDirectory)
        assertEquals("slash", first.readText())
        assertEquals("colon", second.readText())
    }

    @Test fun simultaneousDownloadsKeepEveryCompletedFile() {
        val directory = temporary.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..12).map { number -> Callable {
                LegacyMediaPublisher.publish(partial(directory, "content-$number"), "同名影片", "mp4")
            } }
            val results = executor.invokeAll(jobs, 10, TimeUnit.SECONDS).map { it.get() }
            assertEquals(12, results.map { it.name }.distinct().size)
            assertEquals((1..12).map { "content-$it" }.toSet(), results.map { it.readText() }.toSet())
            assertEquals(12, directory.listFiles()!!.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun incompleteFilesAreRejectedWithoutTouchingExistingDownloads() {
        val directory = temporary.newFolder()
        val original = File(directory, "影片.mp4").apply { writeText("original") }
        val empty = partial(directory, "")
        assertThrows(IllegalArgumentException::class.java) { LegacyMediaPublisher.publish(empty, "影片", "mp4") }
        assertEquals("original", original.readText())
        assertFalse(File(directory, "影片 (1).mp4").exists())
    }

    @Test fun failedPublicationRemovesOnlyItsReservedName() {
        val directory = temporary.newFolder()
        val original = File(directory, "影片.mp4").apply { writeText("original") }
        val complete = partial(directory, "new download")
        val failing = object : File(complete.absolutePath) {
            override fun renameTo(dest: File): Boolean = false
        }
        assertThrows(IOException::class.java) { LegacyMediaPublisher.publish(failing, "影片", "mp4") }
        assertEquals("original", original.readText())
        assertEquals("new download", complete.readText())
        assertFalse(File(directory, "影片 (1).mp4").exists())
    }
}
