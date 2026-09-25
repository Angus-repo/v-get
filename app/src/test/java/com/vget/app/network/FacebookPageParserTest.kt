package com.vget.app.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FacebookPageParserTest {
    private val source = "https://www.facebook.com/watch/?v=123"
    private val hd = "https://video.xx.fbcdn.net/hd.mp4"
    private val sd = "https://video.xx.fbcdn.net/sd.mp4"
    private fun page(json: String) = "<html><head><meta content='海邊 &amp; 旅行' property='og:title'></head><body><script type='application/json'>$json</script></body></html>"

    @Test fun extractsRealHdAndSdWithoutDuplicatingAliases() {
        val info = FacebookPageParser.parse(page("""{"id":"123","browser_native_hd_url":"$hd","hd_src":"$hd","browser_native_sd_url":"$sd"}"""), source)
        assertEquals("海邊 & 旅行", info.title)
        assertEquals(listOf("HD", "SD"), info.formats.map { it.quality })
    }

    @Test fun choosesTheRequestedVideoInsteadOfRecommendations() {
        val info = FacebookPageParser.parse(page("""[{"id":"999","hd_src":"https://video.xx.fbcdn.net/other.mp4"},{"id":"123","sd_src":"$sd"}]"""), source)
        assertEquals(listOf(VideoFormat("SD", sd)), info.formats)
    }

    @Test fun inheritsVideoIdForNestedDeliveryFields() {
        val info = FacebookPageParser.parse(page("""{"__typename":"Video","id":"123","videoDeliveryLegacyFields":{"browser_native_hd_url":"$hd"}}"""), source)
        assertEquals(hd, info.formats.single().url)
    }

    @Test fun doesNotSubstituteAnotherVideoIfRequestedVideoIsUnavailable() {
        assertThrows(IOException::class.java) {
            FacebookPageParser.parse(page("""{"id":"999","sd_src":"$sd"}"""), source)
        }
    }

    @Test fun rejectsAmbiguousShortLinkWithoutTargetIdentity() {
        assertThrows(IOException::class.java) {
            FacebookPageParser.parse(page("""[{"id":"123","sd_src":"$sd"},{"id":"999","hd_src":"$hd"}]"""), "https://fb.watch/abc/")
        }
    }

    @Test fun usesCanonicalVideoIdentityForShortLinks() {
        val html = page("""[{"id":"123","sd_src":"$sd"},{"id":"999","hd_src":"$hd"}]""")
            .replace("<head>", "<head><meta property='og:url' content='$source'>")
        assertEquals(sd, FacebookPageParser.parse(html, "https://fb.watch/abc/").formats.single().url)
    }

    @Test fun decodesJsonEscapesWithoutDecodingSignedQueryParameters() {
        val info = FacebookPageParser.parse(page("""{"id":"123","sd_src":"https:\/\/video.xx.fbcdn.net\/v.mp4?token=a%2Bb%2Fc%3D\u0026oh=one+two"}"""), source)
        assertEquals("https://video.xx.fbcdn.net/v.mp4?token=a%2Bb%2Fc%3D&oh=one+two", info.formats.single().url)
    }

    @Test fun supportsNamedFieldsInLegacyScriptAssignments() {
        val html = """<script>var video = {"hd_src":"$hd","sd_src":"$sd"};</script>"""
        assertEquals(2, FacebookPageParser.parse(html, source).formats.size)
    }

    @Test fun rejectsAmbiguousLegacyData() {
        val html = """<script>var a={"sd_src":"$sd"}; var b={"sd_src":"$hd"};</script>"""
        assertThrows(IOException::class.java) { FacebookPageParser.parse(html, source) }
    }

    @Test fun supportsDirectOpenGraphVideoAsMp4WithoutInventingHd() {
        val info = FacebookPageParser.parse("""<meta content="$sd?a=1&amp;b=2" property="og:video">""", source)
        assertEquals(VideoFormat("MP4", "$sd?a=1&b=2"), info.formats.single())
    }

    @Test fun ignoresImagesDashAndNonFacebookUrls() {
        listOf("""<img src="https://scontent.xx.fbcdn.net/photo.jpg">""",
            page("""{"id":"123","dash_manifest":"manifest","thumbnailImage":{"uri":"$hd"}}"""),
            page("""{"id":"123","sd_src":"https://evil.test/video.mp4"}"""),
            "<html><title>Log in to Facebook</title></html>").forEach {
            assertThrows(IOException::class.java) { FacebookPageParser.parse(it, source) }
        }
    }

    @Test fun identifiesTheObservedMobileAgeGateWithAnEncodedLineBreak() {
        val error = assertThrows(FacebookPageException::class.java) {
            FacebookPageParser.parse("""<h1><span>Log in to view this 18+&#10;content</span></h1>
                <div>It may be inappropriate for people under&#10;18.</div>""", source)
        }
        assertEquals(FacebookPageException.Reason.AGE_RESTRICTED, error.reason)
        assertTrue(error.requiresLogin)
        val message = DownloadErrors.message(error, VideoPlatform.FACEBOOK)
        assertTrue(message.contains("18+"))
        assertTrue(message.contains("必須登入"))
        assertFalse(message.contains("連線失敗"))
    }

    @Test fun identifiesAnExplicitLoginHeading() {
        val error = assertThrows(FacebookPageException::class.java) {
            FacebookPageParser.parse("<h1>Log in to see this content</h1>", source)
        }
        assertEquals(FacebookPageException.Reason.LOGIN_REQUIRED, error.reason)
        assertTrue(error.requiresLogin)
    }

    @Test fun genericLoginButtonsOrScriptStringsDoNotProveAnAccessRestriction() {
        val error = assertThrows(FacebookPageException::class.java) {
            FacebookPageParser.parse("""<button>Log in</button><h1>A public video about age 18+</h1>
                <script>var translation = "Log in to view this 18+ content";</script>""", source)
        }
        assertEquals(FacebookPageException.Reason.NO_MEDIA, error.reason)
        assertFalse(error.requiresLogin)
    }

    @Test fun anAgeRelatedVideoTitleDoesNotBlockAvailablePublicMedia() {
        val html = page("""{"id":"123","sd_src":"$sd"}""") +
            "<h1>Log in to view this 18+ content</h1>"
        assertEquals(sd, FacebookPageParser.parse(html, source).formats.single().url)
    }
}
