package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class FacebookUrlTest {
    @Test fun normalizesMobileLinksAndUpgradesHttp() {
        assertEquals("https://www.facebook.com/watch/?v=123", FacebookUrl.parse(" http://m.facebook.com/watch/?v=123#share ").toString())
        assertEquals("https://fb.watch/abc/", FacebookUrl.parse("fb.watch/abc/").toString())
    }

    @Test fun rejectsLookalikeHostsCredentialsAndUnexpectedPorts() {
        listOf("https://facebook.com.evil.test/video", "https://evil.test/facebook.com/123",
            "https://user@facebook.com/watch/?v=123", "https://facebook.com:8443/watch/?v=123",
            "file:///facebook.com/video", "https://127.0.0.1/facebook.com/video").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { FacebookUrl.parse(it) }
        }
    }

    @Test fun preservesSignedQueryEncoding() {
        val url = "https://video.xx.fbcdn.net/v/video.mp4?token=a%2Bb%2Fc%3D&oh=one+two"
        assertEquals(url, FacebookUrl.mediaUrl(url))
    }

    @Test fun rejectsNonFacebookMediaAndCleartext() {
        listOf("https://fbcdn.net.evil.test/video.mp4", "https://evil.test/video.mp4",
            "http://video.xx.fbcdn.net/video.mp4", "https://user@video.xx.fbcdn.net/a.mp4").forEach {
            assertNull(it, FacebookUrl.mediaUrl(it))
        }
    }

    @Test fun extractsTheFacebookLinkFromSharedText() {
        assertEquals("https://www.facebook.com/reel/123/",
            FacebookUrl.fromSharedText("看看 https://example.com 和 https://www.facebook.com/reel/123/。"))
    }

    @Test fun recognizesVideoIdsFromSupportedPaths() {
        listOf("https://www.facebook.com/watch/?v=123", "https://www.facebook.com/reel/123/",
            "https://www.facebook.com/user/videos/123/", "https://www.facebook.com/videos/title/123/").forEach {
            assertEquals(it, "123", FacebookUrl.videoId(it))
        }
        assertNull(FacebookUrl.videoId("https://fb.watch/short/"))
    }

    @Test fun rejectsCommentThreadLinksInsteadOfDownloadingTheParentPost() {
        assertThrows(IllegalArgumentException::class.java) {
            FacebookUrl.parse("https://www.facebook.com/watch/?v=123&comment_id=456")
        }
    }

    @Test fun redirectsPreserveMobileHostsAndEncodedParameters() {
        for (host in listOf("m.facebook.com", "mobile.facebook.com", "mbasic.facebook.com")) {
            val url = "https://$host/reel/123/?token=A%2FB+C&share_url=https%3A%2F%2Fwww.facebook.com%2F"
            assertEquals(url, FacebookUrl.parseRedirect(url).toString())
            assertEquals("www.facebook.com", FacebookUrl.parse(url).host)
        }
        assertEquals("https://m.facebook.com/reel/123/",
            FacebookUrl.parseRedirect("http://m.facebook.com/reel/123/#fragment").toString())
    }

    @Test fun redirectValidationStillRejectsOffSiteAndUnsafeTargets() {
        listOf("https://m.facebook.com.evil.test/reel/123/", "https://evil.test/reel/123/",
            "https://user:password@m.facebook.com/reel/123/", "https://m.facebook.com:8443/reel/123/",
            "http://m.facebook.com:443/reel/123/", "https://m.facebook.com/watch/?v=123&comment_id=456"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { FacebookUrl.parseRedirect(url) }
        }
    }

}
