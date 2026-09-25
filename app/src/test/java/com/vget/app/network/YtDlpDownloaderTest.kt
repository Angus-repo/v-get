package com.vget.app.network

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class YtDlpDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mp3DownloadsOnlyTheSelectedAudioAndConvertsBeforeReportingCompletion() {
        val directory = temporary.newFolder("mp3")
        val manifest = File(directory, "completed.txt")
        val request = YtDlpDownloader.buildMp3Request(VideoSource.parse("https://youtu.be/BaW_jenozKc"), directory, manifest,
            VideoQuality("137+140", formatSelector = "137+140", audioFormatSelector = "140"))
        assertEquals("140", request.getOption("-f"))
        assertTrue(request.hasOption("--extract-audio"))
        assertEquals("mp3", request.getOption("--audio-format"))
        assertEquals("192K", request.getOption("--audio-quality"))
        val command = request.buildCommand()
        val index = command.indexOf("--print-to-file")
        assertEquals(listOf("--print-to-file", "after_move:filepath", manifest.absolutePath), command.subList(index, index + 3))
        assertFalse(request.hasOption("--merge-output-format"))
    }

    @Test fun directMp3UsesTheSelectedMediaUrlAndPlatformHeaders() {
        val directory = temporary.newFolder("direct-mp3")
        val source = VideoSource.parse("https://xhslink.cn/o/91RAPBJqYUG")
        val url = "https://sns-video-bd.xhscdn.com/chosen.mp4?sig=A%2FB+C"
        val request = YtDlpDownloader.buildMp3Request(source, directory, File(directory, "completed.txt"), directQuality(url, source))
        assertTrue(request.buildCommand().contains(url))
        assertFalse(request.buildCommand().contains(source.url))
        assertEquals(source.platform.referer, request.getOption("--referer"))
        assertEquals(PlatformPageClient.USER_AGENT, request.getOption("--user-agent"))
    }

    @Test fun silentAndInvalidAudioSelectionsAreRejected() {
        val directory = temporary.newFolder("invalid-mp3")
        val source = VideoSource.parse("https://youtu.be/BaW_jenozKc")
        for (quality in listOf(VideoQuality("silent", silent = true, formatSelector = "137"),
            VideoQuality("bad", formatSelector = "137/best"))) {
            assertThrows(IllegalArgumentException::class.java) {
                YtDlpDownloader.buildMp3Request(source, directory, File(directory, "completed.txt"), quality)
            }
        }
    }

    @Test fun unconvertedVideoCannotBeReportedAsMp3AndMp3UsesAudioMimeType() {
        val directory = temporary.newFolder("converted")
        val video = File(directory, "audio.mp4").apply { writeText("not converted") }
        val manifest = File(directory, "completed.txt").apply { writeText(video.absolutePath) }
        assertThrows(IOException::class.java) { YtDlpDownloader.completedFile(directory, manifest, DownloadFormat.MP3) }
        assertEquals("audio/mpeg", mediaMimeType("mp3"))
    }

    @Test fun commandDownloadsOneVideoAndReportsOnlyAfterMerging() {
        val directory = temporary.newFolder("download")
        val manifest = File(directory, "completed.txt")
        val request = YtDlpDownloader.buildRequest("https://youtube.com/watch?v=BaW_jenozKc", directory, manifest,
            VideoQuality("136+140", height = 720, formatSelector = "136+140"))
        // The wrapper appends options before its custom commands.
        request.addOption("--js-runtimes", "quickjs:/test/libqjs.so")
        val command = request.buildCommand()
        val index = command.indexOf("--print-to-file")
        assertEquals(listOf("--print-to-file", "after_move:filepath", manifest.absolutePath), command.subList(index, index + 3))
        assertTrue(command.contains("--no-playlist"))
        assertTrue(command.contains("--abort-on-unavailable-fragments"))
        assertTrue(command.contains("--no-simulate"))
        assertEquals("1", request.getOption("--playlist-items"))
        assertEquals("136+140", request.getOption("-f"))
        assertFalse(request.getOption("-f")!!.contains("/"))
    }


    @Test fun inspectionNeverDownloadsMedia() {
        val request = YtDlpDownloader.buildInspectRequest("https://youtube.com/watch?v=BaW_jenozKc")
        assertTrue(request.hasOption("--skip-download"))
        assertTrue(request.hasOption("--dump-single-json"))
        assertFalse(request.hasOption("--no-simulate"))
    }

    @Test fun missingOrFallbackSelectorsCannotBypassUserChoice() {
        val directory = temporary.newFolder("selectors")
        val manifest = File(directory, "complete.txt")
        listOf(null, "136/best", "137+140+251").forEach { selector ->
            assertThrows(IllegalArgumentException::class.java) {
                YtDlpDownloader.buildRequest("https://youtube.com/watch?v=BaW_jenozKc", directory, manifest,
                    VideoQuality("test", formatSelector = selector))
            }
        }
    }

    @Test fun acceptsOnlyCompletedNonemptyMediaInJobDirectory() {
        val directory = temporary.newFolder("download")
        val video = File(directory, "video.mp4").apply { writeText("complete-media") }
        val manifest = File(directory, "completed.txt").apply { writeText(video.absolutePath + "\n") }
        assertEquals(video.canonicalFile, YtDlpDownloader.completedFile(directory, manifest))
    }

    @Test fun missingManifestEmptyFilesPartialFilesAndOutsidePathsAreRejected() {
        val directory = temporary.newFolder("download")
        val manifest = File(directory, "completed.txt")
        assertThrows(IOException::class.java) { YtDlpDownloader.completedFile(directory, manifest) }
        listOf(
            File(directory, "video.mp4").apply { createNewFile() },
            File(directory, "video.mp4.part").apply { writeText("partial") },
            temporary.newFile("outside.mp4").apply { writeText("outside") }
        ).forEach { file ->
            manifest.writeText(file.absolutePath)
            assertThrows(IOException::class.java) { YtDlpDownloader.completedFile(directory, manifest) }
        }
    }

    @Test fun loginAndRateLimitFailuresAreActionableWithoutLeakingLogs() {
        assertTrue(DownloadErrors.message(IOException("ERROR: Sign in to confirm your age https://signed-url"), VideoPlatform.YOUTUBE).contains("登入"))
        assertTrue(DownloadErrors.message(IOException("HTTP Error 429"), VideoPlatform.INSTAGRAM).contains("稍後"))
        assertFalse(DownloadErrors.message(IOException("https://signed-url?secret=123"), VideoPlatform.YOUTUBE).contains("secret"))
    }
}
