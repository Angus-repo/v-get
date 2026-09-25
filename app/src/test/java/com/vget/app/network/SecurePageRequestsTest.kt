package com.vget.app.network

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SecurePageRequestsTest {
    private val platform = VideoPlatform.XIAOHONGSHU
    private val request = Request.Builder().url("https://xhslink.cn/o/example?xsec_token=secret")
        .header("User-Agent", PlatformPageClient.USER_AGENT).header("Referer", platform.referer).build()

    @Test fun retriesDnsFailureWithTheExactHttpsRequest() = runBlocking {
        val primary = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("bad route") }.build()
        var fallbackCalls = 0
        val requests = SecurePageRequests(primary) {
            OkHttpClient.Builder().addInterceptor { chain ->
                fallbackCalls++
                assertEquals(request.url, chain.request().url)
                assertEquals(request.headers, chain.request().headers)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("fixture").body("page".toResponseBody()).build()
            }.build()
        }
        requests.execute(request, platform).use { assertEquals("page", it.body!!.string()) }
        assertEquals(1, fallbackCalls)
    }

    @Test fun httpErrorsAndOtherPlatformsDoNotUseDnsFallback() = runBlocking {
        for (code in listOf(200, 302, 401, 403, 429, 503)) {
            val primary = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("fixture").body("page".toResponseBody()).build()
            }.build()
            val requests = SecurePageRequests(primary) { error("HTTP status must not trigger fallback") }
            requests.execute(request, platform).use { assertEquals(code, it.code) }
        }
        val primary = OkHttpClient.Builder().addInterceptor { throw SSLHandshakeException("failed") }.build()
        val requests = SecurePageRequests(primary) { error("Other platforms must not trigger fallback") }
        try {
            requests.execute(Request.Builder().url("https://www.threads.com/t/abc").build(), VideoPlatform.THREADS)
            fail("Handshake must fail")
        } catch (_: SSLHandshakeException) { }
    }

    @Test fun actualTlsHostnameMismatchRecoversOnlyOnTheCorrectDnsRoute() = runBlocking {
        withTlsServers { primary, wrongAddress, goodAddress, url ->
            val requests = SecurePageRequests(primary) { original ->
                original.newBuilder().dns(fixedDns(goodAddress)).connectionPool(ConnectionPool()).build()
            }
            requests.execute(Request.Builder().url(url).build(), platform).use {
                assertEquals("verified origin", it.body!!.string())
                assertEquals("www.xiaohongshu.com", it.request.url.host)
                assertNotNull(it.handshake)
            }

            // Changing DNS must not accept an invalid hostname on the retry.
            val rejected = SecurePageRequests(primary) { original ->
                original.newBuilder().dns(fixedDns(wrongAddress)).connectionPool(ConnectionPool()).build()
            }
            try {
                rejected.execute(Request.Builder().url(url).build(), platform)
                fail("Invalid hostname was accepted")
            } catch (error: PageConnectionException) {
                assertTrue(error.primaryFailure is SSLPeerUnverifiedException)
                assertTrue(error.fallbackFailure is SSLPeerUnverifiedException)
                assertTrue(error.userMessage().contains("TLS_IDENTITY"))
            }

            // A matching hostname with an untrusted issuer must still fail.
            val systemTrust = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(fixedDns(goodAddress)).build()
            try {
                SecurePageRequests(systemTrust) { it.newBuilder().connectionPool(ConnectionPool()).build() }
                    .execute(Request.Builder().url(url).build(), platform)
                fail("Unknown issuer was accepted")
            } catch (error: PageConnectionException) {
                assertTrue(error.primaryFailure is SSLHandshakeException)
                assertTrue(error.fallbackFailure is SSLHandshakeException)
            }
        }
    }

    @Test fun failedCertificateRetryReportsBothAttemptsWithoutSecrets() = runBlocking {
        val primary = OkHttpClient.Builder().addInterceptor {
            throw SSLHandshakeException("https://xhslink.cn/?xsec_token=secret").apply {
                initCause(CertPathValidatorException("private diagnostics"))
            }
        }.build()
        val requests = SecurePageRequests(primary) {
            OkHttpClient.Builder().addInterceptor { throw UnknownHostException("dns-provider-internal-secret") }.build()
        }
        try {
            requests.execute(request, platform)
            fail("Both routes must fail")
        } catch (error: PageConnectionException) {
            val message = DownloadErrors.message(error, platform)
            assertTrue(message.contains("xhslink.cn"))
            assertTrue(message.contains("TLS_CERTIFICATE"))
            assertTrue(message.contains("DNS_LOOKUP"))
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("private diagnostics"))
        }
        assertTrue(connectionFailureLabel(SSLHandshakeException("expired").apply {
            initCause(CertificateExpiredException())
        }).contains("TLS_EXPIRED"))
    }

    @Test fun cancellationClosesTheActiveRequestWithoutStartingFallback() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        var active: Call? = null
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListener(object : EventListener() {
            override fun callStart(call: Call) { active = call }
        }).build()
        try {
            val requests = SecurePageRequests(client) { error("Cancellation must not retry") }
            try {
                withTimeout(500) { requests.execute(Request.Builder().url(server.url("/")).build(), platform) }
                fail("Expected cancellation")
            } catch (_: TimeoutCancellationException) { }
            assertTrue(active!!.isCanceled())
        } finally { server.shutdown() }
    }

    private fun fixedDns(address: InetAddress) = object : Dns {
        override fun lookup(hostname: String) = listOf(address)
    }

    private suspend fun withTlsServers(block: suspend (OkHttpClient, InetAddress, InetAddress, String) -> Unit) {
        val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Test root").build()
        fun credentials(name: String) = HandshakeCertificates.Builder().heldCertificate(
            HeldCertificate.Builder().addSubjectAlternativeName(name).signedBy(ca).build(), ca.certificate).build()
        val origin = MockWebServer()
        val wrong = MockWebServer()
        val goodAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val wrongAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        origin.useHttps(credentials("www.xiaohongshu.com").sslSocketFactory(), false)
        wrong.useHttps(credentials("unrelated.invalid").sslSocketFactory(), false)
        origin.start(goodAddress, 0)
        wrong.start(wrongAddress, origin.port)
        origin.enqueue(MockResponse().setBody("verified origin"))
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(fixedDns(wrongAddress))
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        try { block(client, wrongAddress, goodAddress, "https://www.xiaohongshu.com:${origin.port}/") }
        finally { origin.shutdown(); wrong.shutdown() }
    }
}
