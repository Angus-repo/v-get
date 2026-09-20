package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class YtDlpMetadataParserTest {
    private val source = VideoSource.parse("https://youtube.com/watch?v=BaW_jenozKc")
    private val metadata = """{
      "title":"A sample video", "http_headers":{"User-Agent":"sample-agent","Referer":"https://youtube.com/"},
      "formats":[
        {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128,"url":"https://cdn.example/audio.m4a"},
        {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus","abr":160,"url":"https://cdn.example/audio.webm"},
        {"format_id":"18","ext":"mp4","width":640,"height":360,"vcodec":"avc1","acodec":"mp4a.40.2","url":"https://cdn.example/360.mp4"},
        {"format_id":"136","ext":"mp4","width":1280,"height":720,"fps":30,"vcodec":"avc1","acodec":"none","url":"https://cdn.example/720.mp4?sig=A%2FB+C","http_headers":{"Referer":"https://youtube.com/watch"}},
        {"format_id":"137","ext":"mp4","width":1920,"height":1080,"fps":30,"vcodec":"avc1","acodec":"none","url":"https://cdn.example/1080.mp4"},
        {"format_id":"399","ext":"mp4","width":1920,"height":1080,"fps":30,"vcodec":"av01","acodec":"none","url":"https://cdn.example/1080-av1.mp4"},
        {"format_id":"315","ext":"webm","width":3840,"height":2160,"fps":60,"vcodec":"vp9","acodec":"none","url":"https://cdn.example/2160.webm"},
        {"format_id":"drm","ext":"mp4","height":4320,"vcodec":"avc1","url":"https://cdn.example/drm.mp4","has_drm":true},
        {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none","url":"https://cdn.example/storyboard"}
      ]
    }"""

    @Test fun listsRealVideoResolutionsWithoutAudioOrDrmOrInventedQualities() {
        val details = YtDlpMetadataParser.parse(metadata, source)
        assertEquals(listOf(2160, 1080, 720, 360), details.qualities.map { it.resolution })
        assertEquals("137+140", details.qualities.first { it.height == 1080 }.formatSelector)
        assertTrue(details.qualities.first().label.contains("60fps"))
    }

    @Test fun chosenPreviewAndDownloadUseTheSameVideoAndAudio() {
        val choice = YtDlpMetadataParser.parse(metadata, source).qualities.first { it.height == 720 }
        assertEquals("136+140", choice.formatSelector)
        assertEquals("https://cdn.example/720.mp4?sig=A%2FB+C", choice.preview?.video?.url)
        assertEquals("https://cdn.example/audio.m4a", choice.preview?.audio?.url)
        assertEquals("sample-agent", choice.preview?.video?.headers?.get("User-Agent"))
        assertEquals("https://youtube.com/watch", choice.preview?.video?.headers?.get("Referer"))
        assertEquals("https://youtube.com/", choice.preview?.audio?.headers?.get("Referer"))
    }

    @Test fun webmVideoPairsWithWebmAudioAndCombinedVideoNeedsNoExtraTrack() {
        val choices = YtDlpMetadataParser.parse(metadata, source).qualities
        assertEquals("315+251", choices.first().formatSelector)
        assertEquals("webm", choices.first().container)
        assertEquals("18", choices.last().formatSelector)
        assertNull(choices.last().preview?.audio)
    }

    @Test fun hlsVariantCarriesPlaylistMimeTypeAndDoesNotUseTheMasterPlaylist() {
        val info = """{"title":"HLS","formats":[{"format_id":"hls-720","ext":"mp4","height":720,"vcodec":"avc1","acodec":"aac","protocol":"m3u8_native","url":"https://cdn.example/720.m3u8","manifest_url":"https://cdn.example/master.m3u8"}]}"""
        val choice = YtDlpMetadataParser.parse(info, source).qualities.single()
        assertEquals("application/x-mpegURL", choice.preview?.video?.mimeType)
        assertEquals("https://cdn.example/720.m3u8", choice.preview?.video?.url)
    }

    @Test fun fragmentedDownloadsRemainSelectableWithoutOfferingAnInvalidPreview() {
        val info = """{"formats":[{"format_id":"dash-720","ext":"mp4","height":720,"vcodec":"avc1","acodec":"none","protocol":"http_dash_segments","url":"https://cdn.example/fragments/"}]}"""
        val choice = YtDlpMetadataParser.parse(info, source).qualities.single()
        assertEquals("dash-720", choice.formatSelector)
        assertNull(choice.preview)
        assertTrue(choice.label.contains("無音軌"))
    }

    @Test fun portraitResolutionUsesShortEdgeAndUnknownResolutionIsNotInvented() {
        val portrait = YtDlpMetadataParser.parse("""{"formats":[{"format_id":"p","ext":"mp4","width":1080,"height":1920,"vcodec":"avc1","url":"https://cdn.example/p.mp4"}]}""", source).qualities.single()
        assertTrue(portrait.label.startsWith("1080p"))
        assertTrue(portrait.label.contains("1080×1920"))
        val unknown = YtDlpMetadataParser.parse("""{"formats":[{"format_id":"u","ext":"mp4","vcodec":"avc1","url":"https://cdn.example/u.mp4"}]}""", source).qualities.single()
        assertTrue(unknown.label.contains("解析度未提供"))
    }

    @Test fun selectsFirstVideoEntryWithoutMixingDifferentPostsFormats() {
        val playlist = """{"entries":[null,{"ext":"jpg","url":"https://cdn.example/p.jpg"},$metadata,{"formats":[{"format_id":"unrelated","ext":"mp4","height":999,"vcodec":"avc1","url":"https://cdn.example/other.mp4"}]}]}"""
        assertEquals(listOf(2160, 1080, 720, 360), YtDlpMetadataParser.parse(playlist, source).qualities.map { it.resolution })
    }

    @Test fun unknownResolutionVersionsHaveDistinctLabelsAndExactSelections() {
        val info = """{"formats":[
          {"format_id":"0","ext":"mp4","url":"https://cdn.example/one.mp4"},
          {"format_id":"1","ext":"mp4","url":"https://cdn.example/two.mp4"}
        ]}"""
        val choices = YtDlpMetadataParser.parse(info, source).qualities
        assertEquals(listOf("0", "1"), choices.map { it.formatSelector })
        assertEquals(2, choices.map { it.label }.distinct().size)
        assertTrue(choices.all { it.resolution == null && it.label.contains("解析度未提供") })
    }

    @Test fun rejectsLiveAudioOnlyAndInvalidFormatIds() {
        val bad = listOf(
            metadata.replace("\"title\"", "\"is_live\":true,\"title\""),
            """{"formats":[{"format_id":"audio","ext":"m4a","vcodec":"none","url":"https://cdn.example/a.m4a"}]}""",
            """{"formats":[{"format_id":"137/best","ext":"mp4","vcodec":"avc1","url":"https://cdn.example/v.mp4"}]}"""
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { YtDlpMetadataParser.parse(it, source) } }
    }
}
