package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class XiaohongshuPageParserTest {
    private val id = "6ab5166e00000000150062aa"
    private val source = VideoSource.parse("https://www.xiaohongshu.com/discovery/item/$id?xsec_token=fixture")
    private val url = "https://sns-video-bd.xhscdn.com/stream/1080.mp4?sign=A%2FB+C%2B"
    private fun page(note: String, target: String = id) = """<script>window.__INITIAL_STATE__={
        "note":{"noteDetailMap":{"$target":{"note":$note}}},"notLoaded":undefined
        };window.somethingElse=true;</script>"""
    private fun note(streams: String, type: String = "video") = """{"noteId":"$id","type":"$type",
        "title":"測試 undefined 與 } 字元","video":{"media":{"stream":$streams}}}"""
    private val formats = """{"h264":[
        {"masterUrl":"https://sns-video-bd.xhscdn.com/720.mp4","width":720,"height":1280,"size":3000000,"duration":60000,"videoCodec":"h264","audioCodec":"aac"},
        {"masterUrl":"$url","width":1080,"height":1920,"size":6000000,"duration":60000,"videoCodec":"h264","audioCodec":"aac","fps":60}
    ]}"""

    @Test fun readsExactNoteQualitySizeDurationAndSignedMediaUrl() {
        val info = XiaohongshuPageParser.parse(page(note(formats)), source)!!
        assertEquals("測試 undefined 與 } 字元", info.title)
        assertEquals(listOf(1080, 720), info.qualities.map { it.resolution })
        val chosen = info.qualities.first()
        assertEquals(url, chosen.directUrl)
        assertEquals(url, chosen.preview?.video?.url)
        assertEquals(6_000_000L, chosen.fileSize?.bytes)
        assertEquals(false, chosen.fileSize?.approximate)
        assertEquals(60.0, chosen.durationSeconds!!, 0.0)
        assertEquals(1_440_000L, chosen.mp3Size?.bytes)
        assertTrue(chosen.label.contains("6.0 MB"))
        assertTrue(chosen.label.contains("60fps"))
    }

    @Test fun readsTheMobileSharePageShapeObservedOnTheProvidedVideo() {
        val html = """<script>window.__INITIAL_STATE__={"noteData":{"data":{"noteData":${note(formats)},
            "relatedNotes":[{"noteId":"ffffffffffffffffffffffff","video":{}}]}}};</script>"""
        val info = XiaohongshuPageParser.parse(html, source)!!
        assertEquals(url, info.videoUrl)
        assertEquals(listOf(1080, 720), info.qualities.map { it.resolution })
        assertNull(XiaohongshuPageParser.parse(html.replace("\"noteId\":\"$id\"", "\"noteId\":\"eeeeeeeeeeeeeeeeeeeeeeee\""), source))
    }

    @Test fun neverDownloadsAnotherNoteOrMediaFromAnImageNote() {
        assertNull(XiaohongshuPageParser.parse(page(note(formats), "ffffffffffffffffffffffff"), source))
        assertNull(XiaohongshuPageParser.parse(page(note(formats, "normal")), source))
        assertNull(XiaohongshuPageParser.parse(page(note(formats).replace("\"noteId\":\"$id\"", "\"noteId\":\"ffffffffffffffffffffffff\"")), source))
    }

    @Test fun sharedDurationUsesSecondsWhenStreamDurationIsMissing() {
        val html = page(note("""{"h264":[{"masterUrl":"$url"}]}""")
            .replace("\"media\":{", "\"media\":{\"video\":{\"duration\":25,\"drmType\":0},"))
        val chosen = XiaohongshuPageParser.parse(html, source)!!.qualities.single()
        assertEquals(25.0, chosen.durationSeconds!!, 0.0)
        assertEquals(600_000L, chosen.mp3Size?.bytes)
    }

    @Test fun protectedStreamsAreNotOfferedForDownload() {
        val html = page(note(formats).replace("\"media\":{", "\"media\":{\"video\":{\"drmType\":1},"))
        assertNull(XiaohongshuPageParser.parse(html, source))
    }

    @Test fun usesAnExplicitBackupButRejectsForeignHostsCredentialsAndPlaylists() {
        val validBackup = """{"h264":[{"masterUrl":"https://evil.example/video.mp4","backupUrls":["$url"]}]}"""
        assertEquals(url, XiaohongshuPageParser.parse(page(note(validBackup)), source)?.videoUrl)
        for (bad in listOf("https://xhscdn.com.evil.example/v.mp4", "https://user:pass@sns-video-bd.xhscdn.com/v.mp4",
            "https://sns-video-bd.xhscdn.com:8443/v.mp4", "https://sns-video-bd.xhscdn.com/v.m3u8")) {
            assertNull(XiaohongshuPageParser.parse(page(note("""{"h264":[{"masterUrl":"$bad"}]}""")), source))
        }
    }

    @Test fun malformedAndLoginPagesDoNotBecomeMedia() {
        for (html in listOf("<html>請登入</html>", "<script>window.__INITIAL_STATE__={broken</script>",
            page(note("{}")))) assertNull(XiaohongshuPageParser.parse(html, source))
    }

    @Test fun unknownDimensionsAndSizesStayUnknown() {
        val info = XiaohongshuPageParser.parse(page(note("""{"h264":[{"masterUrl":"$url","size":0}]}""")), source)!!
        assertNull(info.qualities.single().resolution)
        assertNull(info.qualities.single().fileSize)
        assertNull(info.qualities.single().mp3Size)
        assertTrue(info.qualities.single().label.contains("容量未提供"))
    }
}
