package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class VideoSourceTest {
    @Test fun extractsUserProvidedXiaohongshuShareText() {
        val input = "月薪1万，其实是月薪3000的八倍，人家一个月的收入相... https://xhslink.cn/o/91RAPBJqYUG 複製後開啟小紅書查看筆記"
        val source = VideoSource.parse(input)
        assertEquals(VideoPlatform.XIAOHONGSHU, source.platform)
        assertEquals("https://xhslink.cn/o/91RAPBJqYUG", source.url)
        assertNull(source.postId)
    }

    @Test fun separatesChinesePunctuationAndAdjacentShareInstructionsFromUrl() {
        listOf("分享：https://xhslink.cn/o/91RAPBJqYUG複製後開啟小紅書",
            "分享\n「https://xhslink.cn/o/91RAPBJqYUG」\n查看筆記",
            "（https://xhslink.cn/o/91RAPBJqYUG），複製後開啟",
            "https://xhslink.cn/o/91RAPBJqYUG。").forEach {
            assertEquals(it, "https://xhslink.cn/o/91RAPBJqYUG", VideoSource.parse(it).url)
        }
    }

    @Test fun acceptsXiaohongshuAndRednoteNotesWithoutChangingShareTokens() {
        val id = "6ab5166e00000000150062aa"
        for (host in listOf("www.xiaohongshu.com", "www.rednote.com")) {
            for (path in listOf("explore", "discovery/item")) {
                val url = "https://$host/$path/$id?xsec_token=A%2FB+C%2B==&xsec_source=app_share"
                val source = VideoSource.parse("分享 $url 複製後開啟")
                assertEquals(url, source.url)
                assertEquals(id, source.postId)
            }
        }
        for (host in listOf("xhslink.com", "xhslink.cn")) {
            for (path in listOf("Abc123", "m/Abc123", "a/Abc123", "o/Abc123")) {
                assertEquals(VideoPlatform.XIAOHONGSHU, VideoSource.parse("https://$host/$path").platform)
            }
        }
    }

    @Test fun rejectsXiaohongshuLookalikesProfilesAndNonNotePaths() {
        listOf("https://xhslink.cn.evil.example/o/abc", "https://xiaohongshu.com@evil.example/explore/abc",
            "https://user:pass@xhslink.cn/o/abc", "https://xhslink.cn:8443/o/abc",
            "https://www.xiaohongshu.com/", "https://www.xiaohongshu.com/user/profile/6ab5166e00000000150062aa",
            "https://www.xiaohongshu.com/explore/not-a-note", "https://xhslink.cn/o/").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { VideoSource.parse(it) }
        }
    }

    @Test fun youtubeVariantsSelectOnlyTheRequestedVideo() {
        listOf(
            "https://youtube.com/watch?v=BaW_jenozKc&list=PL123&index=2",
            "https://youtu.be/BaW_jenozKc?si=tracking",
            "https://m.youtube.com/shorts/BaW_jenozKc",
            "https://www.youtube.com/embed/BaW_jenozKc",
            "http://youtube.com/live/BaW_jenozKc",
            "https://www.youtube-nocookie.com/embed/BaW_jenozKc",
            "看看這支影片： https://youtu.be/BaW_jenozKc。"
        ).forEach {
            val source = VideoSource.parse(it)
            assertEquals(it, VideoPlatform.YOUTUBE, source.platform)
            assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", source.url)
        }
    }

    @Test fun instagramPostsReelsAndShareLinks() {
        listOf("p/ABC123", "reel/ABC123", "reels/ABC123", "tv/ABC123", "user/reel/ABC123", "user/p/ABC123", "share/reel/ABC123", "share/ABC123").forEach {
            val source = VideoSource.parse("https://www.instagram.com/$it/?igsh=tracking")
            assertEquals(VideoPlatform.INSTAGRAM, source.platform)
            assertFalse(source.url.contains("igsh"))
        }
    }

    @Test fun threadsOldAndNewDomainsAndShareFormats() {
        listOf("threads.com", "www.threads.net").forEach { host ->
            listOf("@user/post/C8_X-abc", "t/C8_X-abc").forEach { path ->
                val source = VideoSource.parse("https://$host/$path?xmt=tracking")
                assertEquals(VideoPlatform.THREADS, source.platform)
                assertEquals("C8_X-abc", source.postId)
                assertTrue(source.url.startsWith("https://www.threads.com/"))
            }
        }
        assertNull(VideoSource.parse("https://threads.com/share/abc").postId)
    }

    @Test fun facebookKeepsVideoQueryAndNormalizesMobileHost() {
        assertEquals("https://www.facebook.com/watch/?v=123", VideoSource.parse("m.facebook.com/watch/?v=123").url)
        assertEquals(VideoPlatform.FACEBOOK, VideoSource.parse("https://fb.watch/abc/").platform)
        assertEquals(VideoPlatform.FACEBOOK, VideoSource.parse("https://www.facebook.com/share/v/abc/").platform)
        assertTrue(VideoSource.parse("https://www.facebook.com/測試/videos/123").url.endsWith("/videos/123"))
    }

    @Test fun facebookValidationIsSharedWithTheMultiPlatformInput() {
        listOf("m", "mobile", "mbasic").forEach { host ->
            assertEquals("https://www.facebook.com/watch/?v=123&token=A%2FB+C",
                VideoSource.parse("看看 http://$host.facebook.com/watch/?v=123&token=A%2FB+C。分享影片").url)
        }
        listOf("https://www.facebook.com/watch/?v=123&comment_id=456",
            "https://www.facebook.com/reel/123/?reply_comment_id=456",
            "https://unrecognized.facebook.com/reel/123/",
            "https://www.facebook.com:80/reel/123/").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { VideoSource.parse(url) }
        }
    }

    @Test fun rejectsUnsupportedAndDeceptiveUrlsBeforeNetworkAccess() {
        listOf(
            "", "not a URL", "file:///etc/passwd", "ftp://youtube.com/watch?v=BaW_jenozKc",
            "https://youtube.com.evil.example/watch?v=BaW_jenozKc",
            "https://evil.example/?next=https%3A%2F%2Fyoutube.com",
            "https://youtube.com@evil.example/watch?v=BaW_jenozKc",
            "https://user:password@youtube.com/watch?v=BaW_jenozKc",
            "https://youtube.com:8080/watch?v=BaW_jenozKc",
            "https://youtube.com/playlist?list=PL123", "https://youtube.com/watch?v=bad",
            "https://instagram.com/username/", "https://instagram.com/stories/username/123",
            "https://threads.com/@username", "https://facebook.com/"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { VideoSource.parse(url) }
        }
    }
}
