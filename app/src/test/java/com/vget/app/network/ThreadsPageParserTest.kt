package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class ThreadsPageParserTest {
    private val source = VideoSource.parse("https://threads.com/@user/post/TARGET")
    private fun page(json: String, meta: String = "") = """
        <html><head><title>A Threads post</title>$meta</head><body>
        <script type="application/json" data-sjs>{broken JSON}</script>
        <script type="application/json" data-sjs>{"require":[["data",$json]]}</script>
        </body></html>
    """.trimIndent()

    @Test fun selectsRequestedPostOverRecommendationsAndPreservesSignedUrl() {
        val result = ThreadsPageParser.parse(page("""[
          {"code":"RECOMMENDED","video_versions":[{"url":"https://video.fbcdn.net/wrong.mp4"}]},
          {"code":"TARGET","video_versions":[{"url":"https:\/\/video.fbcdn.net\/right.mp4?sig=A%2FB+X%2B\u0026token=1"}]}
        ]"""), source)
        assertEquals("https://video.fbcdn.net/right.mp4?sig=A%2FB+X%2B&token=1", result?.videoUrl)
    }

    @Test fun absentTargetNeverDownloadsAnotherPost() {
        assertNull(ThreadsPageParser.parse(page("""{"code":"OTHER","video_versions":[{"url":"https://video.fbcdn.net/wrong.mp4"}]}"""), source))
    }

    @Test fun imagePostNeverDownloadsQuotedOrRecommendedVideo() {
        assertNull(ThreadsPageParser.parse(page("""{
          "code":"TARGET","media_type":1,"quoted_post":{"code":"OTHER","video_versions":[{"url":"https://video.fbcdn.net/wrong.mp4"}]}
        }"""), source))
    }

    @Test fun mixedCarouselSelectsFirstVideoAndHighestQualityVersion() {
        val result = ThreadsPageParser.parse(page("""{"code":"TARGET","carousel_media":[
          {"media_type":1,"image_versions2":{}},
          {"video_versions":[
            {"url":"https://video.fbcdn.net/low.mp4","width":320,"height":240},
            {"url":"https://video.fbcdn.net/high.mp4","width":1280,"height":720}]},
          {"video_versions":[{"url":"https://video.fbcdn.net/second.mp4"}]}
        ]}"""), source)
        assertEquals("https://video.fbcdn.net/high.mp4", result?.videoUrl)
    }

    @Test fun shareLinkUsesCanonicalPostId() {
        val share = VideoSource.parse("https://threads.net/share/opaque")
        val result = ThreadsPageParser.parse(page("""{"code":"TARGET","video_url":"https://video.fbcdn.net/video.mp4"}""",
            """<meta content="https://www.threads.com/@user/post/TARGET" property="og:url">"""), share)
        assertNotNull(result)
    }

    @Test fun exposesEveryDistinctResolutionAndKeepsItsExactMediaUrl() {
        val result = ThreadsPageParser.parse(page("""{"code":"TARGET","video_versions":[
          {"url":"https://video.fbcdn.net/low.mp4?sig=low","width":640,"height":360},
          {"url":"https://video.fbcdn.net/high.mp4?sig=high","width":1920,"height":1080},
          {"url":"https://video.fbcdn.net/high.mp4?sig=duplicate","width":1920,"height":1080}
        ]}"""), source)!!
        assertEquals(listOf(1080, 360), result.qualities.map { it.resolution })
        val selected = result.qualities.last()
        assertEquals("https://video.fbcdn.net/low.mp4?sig=low", selected.directUrl)
        assertEquals(selected.directUrl, selected.preview!!.video.url)
    }

    @Test fun missingVersionDimensionsNeverInheritOriginalResolution() {
        val result = ThreadsPageParser.parse(page("""{"code":"TARGET","original_width":1920,
          "original_height":1080,"has_audio":false,"video_versions":[
          {"url":"https://video.fbcdn.net/one.mp4"},
          {"url":"https://video.fbcdn.net/two.mp4"}
        ]}"""), source)!!
        assertEquals(2, result.qualities.size)
        assertTrue(result.qualities.all { it.resolution == null && it.silent })
        assertTrue(result.qualities.all { it.label.contains("解析度未提供") && !it.label.contains("1080") })
        assertNotEquals(result.qualities[0].label, result.qualities[1].label)
    }

    @Test fun shareWithoutCanonicalIdentityIsRejected() {
        assertNull(ThreadsPageParser.parse(page("""{"code":"OTHER","video_url":"https://video.fbcdn.net/wrong.mp4"}"""),
            VideoSource.parse("https://threads.net/share/opaque")))
    }

    @Test fun parsesHtmlEntitiesInMetadataWithoutDecodingSignedQuery() {
        val result = ThreadsPageParser.parse(page("{}", """
          <meta property="og:url" content="https://www.threads.com/@user/post/TARGET">
          <meta content="https://scontent.cdninstagram.com/video.mp4?a=%2F&amp;b=+" property="og:video:secure_url">
        """), source)
        assertEquals("https://scontent.cdninstagram.com/video.mp4?a=%2F&b=+", result?.videoUrl)
    }

    @Test fun rejectsNonMediaHostsAndMalformedVersionData() {
        listOf("https://evil.example/video.mp4", "file:///video.mp4", "https://fbcdn.net.evil.example/video.mp4").forEach { url ->
            assertNull(ThreadsPageParser.parse(page("""{"code":"TARGET","video_versions":[null,{"url":"$url"}]}"""), source))
        }
    }
}
