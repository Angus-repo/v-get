package com.vget.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlatformPageClientTest {
    private val share = VideoSource.parse("https://www.threads.com/share/BASlIr-pCj/")
    private val postUrl = "https://www.threads.com/@user/post/TARGET"
    private val mediaUrl = "https://video.cdninstagram.com/inline.mp4?sig=A%2FB+X%2B"
    private val page = """<script type="application/json">{
        "code":"TARGET","media_type":19,"video_versions":null,"carousel_media":null,
        "text_post_app_info":{"linked_inline_media":{
            "code":"INSTAGRAM","video_versions":[{"url":"$mediaUrl"}]
        }}
    }</script>"""

    private fun client(handle: (Request) -> Response) = PlatformPageClient(
        OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build()
    )

    private fun response(request: Request, body: String = "", redirect: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (redirect == null) 200 else 302).message("fixture")
            .body(body.toResponseBody()).apply { redirect?.let { header("Location", it) } }.build()

    @Test fun xiaohongshuSharePreservesTokenAndOnlyFetchesHttps() = runBlocking {
        val requests = mutableListOf<Request>()
        val id = "6ab5166e00000000150062aa"
        val token = "xsec_token=A%2FB+C%2B==&xsec_source=app_share"
        val result = client { request ->
            requests.add(request)
            if (request.url.host == "xhslink.cn") response(request, redirect = "http://www.xiaohongshu.com/discovery/item/$id?$token")
            else response(request, """<script>window.__INITIAL_STATE__={"note":{"noteDetailMap":{"$id":{"note":{
                "type":"video","video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/video.mp4"}]}}}
            }}}}};</script>""")
        }.extractXiaohongshu(VideoSource.parse("分享 https://xhslink.cn/o/91RAPBJqYUG 複製後開啟"))
        assertEquals("https://sns-video-bd.xhscdn.com/video.mp4", result.videoUrl)
        assertEquals("https://www.xiaohongshu.com/discovery/item/$id?$token", requests.last().url.toString())
        assertTrue(requests.all { it.url.isHttps })
    }

    @Test fun xiaohongshuLoginAndOffPlatformRedirectsStopWithoutFetchingThem() = runBlocking {
        for (location in listOf("https://www.xiaohongshu.com/login?redirectPath=secret", "https://evil.example/note")) {
            var calls = 0
            try {
                client { request -> calls++; response(request, redirect = location) }
                    .extractXiaohongshu(VideoSource.parse("https://xhslink.cn/o/91RAPBJqYUG"))
                fail("Restricted redirect must stop")
            } catch (e: Exception) {
                if ("/login" in location) assertTrue(e.message.orEmpty().contains("登入"))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun xiaohongshuRedirectCannotSubstituteAnotherNote() = runBlocking {
        val original = VideoSource.parse("https://www.xiaohongshu.com/explore/6ab5166e00000000150062aa")
        try {
            client { request ->
                if (request.url.toString() == original.url) response(request, redirect = "https://www.xiaohongshu.com/explore/ffffffffffffffffffffffff")
                else response(request, "<html></html>")
            }.extractXiaohongshu(original)
            fail("Different note must not be selected")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("不同筆記")) }
    }

    @Test fun followsShareRedirectAndUsesItsPostIdWithoutCanonicalMetadata() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client { request ->
            requests.add(request)
            if (request.url.encodedPath.startsWith("/share/")) response(request, redirect = postUrl)
            else response(request, page)
        }.extractThreads(share)
        assertEquals(mediaUrl, result.videoUrl)
        assertEquals(postUrl, result.sourceUrl)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Sec-Fetch-Mode") == "navigate" &&
            it.header("Sec-Fetch-Dest") == "document" && it.header("Accept")!!.contains("text/html") })
    }

    @Test fun previewFallbackKeepsResolvedIdentityWhenFirstPageIsOnlyAShell() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client { request ->
            requests.add(request)
            when {
                request.url.encodedPath.startsWith("/share/") -> response(request, redirect = postUrl)
                request.header("User-Agent") == PlatformPageClient.USER_AGENT -> response(request, "<html></html>")
                else -> response(request, page)
            }
        }.extractThreads(share)
        assertEquals(mediaUrl, result.videoUrl)
        assertEquals(listOf(share.url, postUrl, postUrl), requests.map { it.url.toString() })
    }

    @Test fun redirectNeverOverridesAnExplicitPostId() = runBlocking {
        val original = VideoSource.parse("https://www.threads.com/@user/post/ORIGINAL")
        try {
            client { request ->
                if (request.url.toString() == original.url) response(request, redirect = postUrl)
                else response(request, page)
            }.extractThreads(original)
            fail("A different post must not be downloaded")
        } catch (_: IOException) { }
    }

    @Test fun rejectsRedirectsOutsideThreadsBeforeMakingAnotherRequest() = runBlocking {
        for (location in listOf("https://evil.example/post/TARGET", "http://www.threads.com/@user/post/TARGET",
            "https://www.threads.com:8443/@user/post/TARGET", "https://user:pass@www.threads.com/@user/post/TARGET")) {
            var calls = 0
            try {
                client { request -> calls++; response(request, redirect = location) }.extractThreads(share)
                fail("Unsafe redirect must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, calls)
        }
    }
}
