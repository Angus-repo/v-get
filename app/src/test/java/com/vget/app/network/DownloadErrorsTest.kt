package com.vget.app.network

import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class DownloadErrorsTest {
    private val platform = VideoPlatform.XIAOHONGSHU
    @Test fun chineseHttpErrorsKeepTheStatusInsteadOfBecomingTheGenericScreenshotMessage() {
        for (code in listOf(403, 404, 429, 461, 503)) {
            val message = DownloadErrors.message(IOException("小紅書連線失敗（HTTP $code）"), platform)
            assertTrue(message.contains("HTTP $code"))
            assertFalse(message.contains("YouTube"))
        }
    }

    @Test fun engineStatusIsRetainedWithoutLeakingSignedUrls() {
        val error = Exception("ERROR: unable to download https://cdn.example/video?xsec_token=secret : HTTP Error 403: Forbidden")
        val message = DownloadErrors.message(error, platform)
        assertTrue(message.contains("HTTP 403"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("cdn.example"))
        assertTrue(DownloadErrors.canRetryMedia(error))
    }

    @Test fun urlContentsNeverBecomeAnErrorCodeOrAppearInTheMessage() {
        val message = DownloadErrors.message(Exception("下載 https://cdn.example/HTTP403?signature=secret 失敗"), platform)
        assertFalse(message.contains("HTTP 403"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("cdn.example"))
    }

    @Test fun nestedDnsTlsAndConnectionErrorsGiveDistinctSafeMessages() {
        assertTrue(DownloadErrors.message(IOException("failed", UnknownHostException("secret-host")), platform).contains("解析"))
        assertTrue(DownloadErrors.message(SSLHandshakeException("certificate problem"), platform).contains("安全連線"))
        assertTrue(DownloadErrors.message(SocketException("Connection reset"), platform).contains("連線中斷"))
    }

    @Test fun localStorageAndConversionFailuresDoNotTriggerReplicaDownloads() {
        val full = LocalMediaException(IOException("ENOSPC: no space left on device"))
        assertFalse(DownloadErrors.canRetryMedia(full))
        assertTrue(DownloadErrors.message(full, platform).contains("儲存空間不足"))
        assertFalse(DownloadErrors.canRetryMedia(Exception("FFmpeg conversion failed")))
        assertFalse(DownloadErrors.canRetryMedia(HttpStatusException(429, "下載失敗")))
        assertFalse(DownloadErrors.canRetryMedia(HttpStatusException(401, "下載失敗")))
    }
}
