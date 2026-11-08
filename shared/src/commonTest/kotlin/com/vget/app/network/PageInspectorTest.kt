package com.vget.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PageInspectorTest {
    @Test fun preservesMobileRedirectAndRequestedFacebookIdentity() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            if (hosts.size == 1) respond("", HttpStatusCode.Found, headersOf("Location", "https://m.facebook.com/reel/123"))
            else respond("""<script>{"id":"123","browser_native_hd_url":"https://video.fbcdn.net/selected.mp4"}</script>""")
        }
        val client = HttpClient(engine) { followRedirects = false }
        try {
            val result = PageInspector(client).inspect(VideoSource.parse("https://facebook.com/share/v/abc"))
            assertEquals(listOf("facebook.com", "m.facebook.com"), hosts)
            assertEquals("https://video.fbcdn.net/selected.mp4", result.qualities.single().directUrl)
        } finally { client.close() }
    }

    @Test fun rejectsCrossPlatformRedirectBeforeRequestingIt() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.Found, headersOf("Location", "https://evil.example/post/abc")) }) { followRedirects = false }
        try {
            assertFailsWith<IllegalArgumentException> { PageInspector(client).inspect(VideoSource.parse("https://threads.com/@u/post/abc")) }
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun detectsRedirectLoopsAndDifferentPostIds() = runTest {
        for (destination in listOf("https://www.threads.com/@u/post/abc", "https://www.threads.com/@u/post/other")) {
            var calls = 0
            val client = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.Found, headersOf("Location", destination)) }) { followRedirects = false }
            try {
                assertFailsWith<IllegalArgumentException> { PageInspector(client).inspect(VideoSource.parse("https://threads.com/@u/post/abc")) }
                assertEquals(1, calls)
            } finally { client.close() }
        }
    }

    @Test fun activeThreadsRestrictionNeverTriggersPreviewFallback() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""<script type="application/json">{"initialRouteInfo":{"route":{"canonicalRouteName":"comet.barcelonawebloggedout.BarcelonaGeoBlockRoute"}}}</script>""")
        }) { followRedirects = false }
        try {
            val error = assertFailsWith<okio.IOException> { PageInspector(client).inspect(VideoSource.parse("https://threads.com/@u/post/abc")) }
            assertTrue(error.message!!.contains("This content isn't available to everyone"))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun engineMustBeExplicitHttpsAndCannotRedirectCredentials() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.Found, headersOf("Location", "https://other.example/")) }) { followRedirects = false }
        try {
            assertFailsWith<IllegalArgumentException> { EngineService(client, "http://engine.example", "secret") }
            assertFailsWith<IllegalArgumentException> {
                EngineService(client, "https://engine.example", "secret").inspect(VideoSource.parse("https://youtu.be/abcdefghijk"))
            }
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
