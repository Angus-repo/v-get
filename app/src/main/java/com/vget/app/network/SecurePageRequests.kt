package com.vget.app.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resumeWithException

/** Retry an XHS connection failure with a different DNS route, never weaker TLS. */
internal class SecurePageRequests(
    private val primary: OkHttpClient,
    fallbackFactory: (OkHttpClient) -> OkHttpClient = XhsEncryptedDns::client
) {
    private val fallback by lazy { fallbackFactory(primary) }

    suspend fun execute(request: Request, platform: VideoPlatform): Response {
        try {
            return awaitResponse(primary.newCall(request))
        } catch (first: IOException) {
            currentCoroutineContext().ensureActive()
            if (platform != VideoPlatform.XIAOHONGSHU || !request.url.isHttps ||
                VideoSource.platformForHost(request.url.host) != VideoPlatform.XIAOHONGSHU ||
                !isConnectionFailure(first)) throw first
            try {
                // Same URL, hostname, headers and platform certificate checks.
                // HTTP errors (including 401/403/429) never trigger DNS retries.
                return awaitResponse(fallback.newCall(request))
            } catch (second: IOException) {
                currentCoroutineContext().ensureActive()
                throw PageConnectionException(request.url.host, first, second)
            }
        }
    }

    private fun isConnectionFailure(error: IOException) = error is SSLException ||
        error is UnknownHostException || error is ConnectException || error is SocketTimeoutException

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
}

internal object XhsEncryptedDns {
    fun client(original: OkHttpClient): OkHttpClient {
        // Honour the app/device proxy route for the DNS HTTPS request as well.
        val dnsClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            .proxy(original.proxy).proxySelector(original.proxySelector).build()
        val resolver = DnsOverHttps.Builder().client(dnsClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)))
            .build()
        return original.newBuilder().connectionPool(ConnectionPool())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (VideoSource.platformForHost(hostname) == VideoPlatform.XIAOHONGSHU) resolver.lookup(hostname)
                    else original.dns.lookup(hostname)
            }).build()
    }
}

internal class PageConnectionException(
    val host: String,
    val primaryFailure: IOException,
    val fallbackFailure: IOException
) : IOException("分享頁連線失敗", fallbackFailure) {
    init { addSuppressed(primaryFailure) }

    fun userMessage(): String = "無法連線至 $host。\n" +
        "一般連線：${connectionFailureLabel(primaryFailure)}\n" +
        "加密 DNS 重試：${connectionFailureLabel(fallbackFailure)}"
}

/** Only fixed descriptions and codes reach the UI, never raw exception text or signed URLs. */
internal fun connectionFailureLabel(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        chain.any { it is CertificateExpiredException } -> "憑證已過期（TLS_EXPIRED）"
        chain.any { it is CertificateNotYetValidException } -> "憑證尚未生效（TLS_NOT_YET_VALID）"
        chain.any { it is SSLPeerUnverifiedException } -> "伺服器身分驗證失敗（TLS_IDENTITY）"
        chain.any { it is CertPathValidatorException || it is CertificateException } -> "憑證鏈驗證失敗（TLS_CERTIFICATE）"
        chain.any { it is SSLProtocolException } -> "安全連線協定錯誤（TLS_PROTOCOL）"
        chain.any { it is SSLHandshakeException } -> "安全連線握手失敗（TLS_HANDSHAKE）"
        chain.any { it is SSLException } -> "安全連線中斷（TLS_CONNECTION）"
        chain.any { it is UnknownHostException } -> "無法解析網址（DNS_LOOKUP）"
        chain.any { it is SocketTimeoutException } -> "連線逾時（CONNECTION_TIMEOUT）"
        chain.any { it is ConnectException } -> "無法連線（CONNECTION_REFUSED）"
        else -> "連線失敗（NETWORK_IO）"
    }
}
