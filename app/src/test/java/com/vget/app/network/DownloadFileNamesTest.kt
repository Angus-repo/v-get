package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class DownloadFileNamesTest {
    @Test fun usesThePageTitleForEveryOutputFormatWithoutRandomIdentifiers() {
        val title = "台积电提前一年量产1.4nm芯片！"
        for (extension in listOf("mp4", "mp3", "webm", "mkv")) {
            assertEquals("$title.$extension", DownloadFileNames.fromTitle(title, extension))
        }
    }

    @Test fun preservesChineseSpacesEmojiAndReadablePunctuation() {
        val title = "我的旅行 🌊・台灣（上集）- 100%"
        assertEquals("$title.mp4", DownloadFileNames.fromTitle(title, "mp4"))
        assertEquals("$title.mp3", DownloadFileNames.fromTitle(title, "mp3"))
        assertEquals("Café.mp3", DownloadFileNames.fromTitle("Cafe\u0301", "mp3"))
    }

    @Test fun removesPathSeparatorsControlsAndUnsafeFilenameCharacters() {
        val name = DownloadFileNames.fromTitle("../../我的\\影片: \"測試\"<>|?*\u0000\n\u202e .", "mp4")
        assertFalse(name.any { it in "/\\:*?\"<>|" || it.isISOControl() || it == '\u202e' })
        assertFalse(name.startsWith('.'))
        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.contains("我的_影片"))
    }

    @Test fun normalizesWhitespaceAndProvidesFallbackForMissingTitles() {
        assertEquals("我的 旅行.mp4", DownloadFileNames.fromTitle(" \n我的\t\u00a0旅行\n ", "mp4"))
        listOf("", "  ", "...", "\u0000\u202e", "///???").forEach { title ->
            assertEquals("V-Get.mp3", DownloadFileNames.fromTitle(title, "mp3"))
        }
    }

    @Test fun keepsExtensionsAccurateAndOnlyAddsCopyNumbersWhenRequested() {
        assertEquals("我的影片.mp4", DownloadFileNames.fromTitle("我的影片.MP4", "MP4"))
        assertEquals("我的影片 (1).mp4", DownloadFileNames.fromTitle("我的影片", "mp4", 1))
        assertEquals("我的影片 (12).mp3", DownloadFileNames.fromTitle("我的影片", "mp3", 12))
        assertThrows(IllegalArgumentException::class.java) { DownloadFileNames.fromTitle("title", "../mp4") }
        assertThrows(IllegalArgumentException::class.java) { DownloadFileNames.fromTitle("title", "mp3", -1) }
    }

    @Test fun limitsUtf8LengthWithoutSplittingEmojiOrLosingTheExtension() {
        for (title in listOf("長".repeat(200), "🌊".repeat(100), "a".repeat(179) + "🌊")) {
            val name = DownloadFileNames.fromTitle(title, "webm", 9999)
            val stem = name.removeSuffix(" (9999).webm")
            assertTrue(stem.toByteArray(Charsets.UTF_8).size <= 180)
            assertEquals(stem, String(stem.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertTrue(title.startsWith(stem))
            assertTrue(name.endsWith(" (9999).webm"))
        }
    }

    @Test fun avoidsReservedDeviceNamesWhenFilesAreCopiedToOtherSystems() {
        assertEquals("_CON.mp4", DownloadFileNames.fromTitle("CON", "mp4"))
        assertEquals("_nul.mp3", DownloadFileNames.fromTitle("nul", "mp3"))
        assertEquals("_COM1.mp4", DownloadFileNames.fromTitle("COM1", "mp4"))
    }
}
