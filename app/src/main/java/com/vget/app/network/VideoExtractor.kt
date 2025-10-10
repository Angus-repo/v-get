package com.vget.app.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets

class VideoExtractor {

    private val TAG = "VideoExtractor"
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val REFERER = "https://www.facebook.com/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 從 Facebook URL 中提取影片下載連結
     * @param url Facebook 影片網址
     * @return VideoInfo 包含影片下載連結和資訊
     */
    suspend fun extractVideoUrl(url: String): Result<VideoInfo> {
        return try {
            Log.d(TAG, "=== 開始解析 URL ===")
            Log.d(TAG, "URL: $url")
            
            // 驗證 URL
            if (!isValidFacebookUrl(url)) {
                Log.e(TAG, "URL 驗證失敗: 不是有效的 Facebook 網址")
                return Result.failure(IllegalArgumentException("無效的 Facebook 網址"))
            }

            // 標準化 URL
            val normalizedUrl = normalizeFacebookUrl(url)
            Log.d(TAG, "標準化後的 URL: $normalizedUrl")

            // 取得網頁內容
            Log.d(TAG, "開始取得網頁內容...")
            val response = fetchPage(normalizedUrl)
            val html = response.body?.string() ?: ""
            Log.d(TAG, "取得網頁內容，長度: ${html.length} bytes")
            
            // 檢查 HTML 內容是否有效
            if (html.isEmpty()) {
                Log.e(TAG, "網頁內容為空")
                return Result.failure(Exception("無法取得網頁內容"))
            }
            
            // 檢查是否是有效的 HTML
            if (!html.contains("<html", ignoreCase = true) && !html.contains("<!DOCTYPE", ignoreCase = true)) {
                Log.e(TAG, "取得的內容不是有效的 HTML")
                val preview = html.take(200).filter { it.isLetterOrDigit() || it.isWhitespace() }
                Log.d(TAG, "內容預覽: $preview")
                return Result.failure(Exception("無法取得有效的網頁內容"))
            }

            // 解析影片 URL
            Log.d(TAG, "開始解析影片 URL...")
            val videoUrl = parseVideoUrl(html)
            if (videoUrl == null) {
                Log.e(TAG, "無法找到影片連結")
                // 嘗試記錄 HTML 中是否包含 video 關鍵字
                val hasVideo = html.contains("video", ignoreCase = true)
                val hasFbcdn = html.contains("fbcdn", ignoreCase = true)
                Log.d(TAG, "HTML 包含 'video': $hasVideo, 包含 'fbcdn': $hasFbcdn")
                return Result.failure(Exception("無法找到影片連結，請確認該影片為公開影片"))
            }

            Log.d(TAG, "成功找到影片連結，長度: ${videoUrl.length}")
            Log.d(TAG, "=== 解析完成 ===")
            
            Result.success(VideoInfo(
                videoUrl = videoUrl,
                sourceUrl = normalizedUrl,
                title = extractTitle(html)
            ))

        } catch (e: Exception) {
            Log.e(TAG, "=== 解析失敗 ===")
            Log.e(TAG, "異常類型: ${e.javaClass.name}")
            Log.e(TAG, "異常訊息: ${e.message}", e)
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun isValidFacebookUrl(url: String): Boolean {
        val facebookPatterns = listOf(
            "facebook.com/",
            "fb.com/",
            "fb.watch/",
            "m.facebook.com/"
        )
        return facebookPatterns.any { url.contains(it, ignoreCase = true) }
    }

    private fun normalizeFacebookUrl(url: String): String {
        var normalized = url.trim()
        
        // 將 mobile URL 轉換為桌面版
        normalized = normalized.replace("m.facebook.com", "www.facebook.com")
        
        // 確保使用 https
        if (!normalized.startsWith("http")) {
            normalized = "https://$normalized"
        }
        
        return normalized
    }

    private fun fetchPage(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .applyCommonHeaders()
            // 不要手動設置 Accept-Encoding，讓 OkHttp 自動處理壓縮
            .addHeader("Upgrade-Insecure-Requests", "1")
            .addHeader("Sec-Fetch-Dest", "document")
            .addHeader("Sec-Fetch-Mode", "navigate")
            .addHeader("Sec-Fetch-Site", "none")
            .addHeader("Cache-Control", "max-age=0")
            .build()

        val response = client.newCall(request).execute()
        
        // 確認回應是否成功
        if (!response.isSuccessful) {
            Log.e(TAG, "HTTP 請求失敗: ${response.code}")
            throw Exception("無法連線到 Facebook (錯誤代碼: ${response.code})")
        }
        
        return response
    }

    private fun parseVideoUrl(html: String): String? {
        Log.d(TAG, "開始解析影片 URL")

        val visited = mutableSetOf<String>()

        fun handleCandidate(rawUrl: String, label: String): String? {
            val unescaped = unescapeUrl(rawUrl)
            val key = unescaped.trim()
            if (!visited.add(key)) {
                Log.d(TAG, "$label 已檢查過，跳過重複")
                return null
            }

            Log.d(TAG, "檢查候選連結：$label，長度: ${unescaped.length}")

            if (!isValidVideoUrl(unescaped)) {
                Log.w(TAG, "$label 未通過字串規則驗證")
                return null
            }

            if (verifyVideoStream(unescaped)) {
                Log.d(TAG, "$label 內容驗證通過")
                return unescaped
            }

            Log.w(TAG, "$label 內容驗證失敗，繼續尋找其他候選")
            return null
        }

        // 方法 1: 先嘗試從 HTML 中直接搜尋包含 video 和長 URL 的模式
        Log.d(TAG, "嘗試方法 1: 搜尋長 URL 模式...")
        val longUrlPattern = """(https?:\\?/\\?/[^\s"']{200,})""".toRegex()
        val longUrls = longUrlPattern.findAll(html).take(20).toList()
        for ((index, match) in longUrls.withIndex()) {
            val rawUrl = match.value
            Log.e(TAG, "找到長 URL ${index + 1}, 原始長度: ${rawUrl.length}")
            Log.e(TAG, "前 150 字符: ${rawUrl.take(150)}")
            handleCandidate(rawUrl, "長 URL ${index + 1}")?.let { return it }
        }

        // 方法 2: 直接從 HTML 中提取 fbcdn URL
        Log.d(TAG, "嘗試方法 2: 搜尋 fbcdn 影片 URL...")
        val fbcdnPattern = """(https?:\\?/\\?/[^"\s]*video[^"\s]*\.fbcdn\.net[^"\s]*)""".toRegex()
        val fbcdnMatches = fbcdnPattern.findAll(html).take(10).toList()

        if (fbcdnMatches.isNotEmpty()) {
            Log.d(TAG, "找到 ${fbcdnMatches.size} 個潛在的 fbcdn 影片 URL")
            for ((index, match) in fbcdnMatches.withIndex()) {
                val rawUrl = match.value
                Log.e(TAG, "fbcdn URL ${index + 1} 原始: $rawUrl")
                handleCandidate(rawUrl, "fbcdn URL ${index + 1}")?.let { return it }
            }
        }

        // JSON/屬性模式
        val patterns = listOf(
            """"hd_src"\s*:\s*"([^"]+)"""" to "hd_src",
            """"sd_src"\s*:\s*"([^"]+)"""" to "sd_src",
            """"playable_url"\s*:\s*"([^"]+)"""" to "playable_url",
            """"playable_url_quality_hd"\s*:\s*"([^"]+)"""" to "playable_url_quality_hd",
            """"browser_native_hd_url"\s*:\s*"([^"]+)"""" to "browser_native_hd_url",
            """"browser_native_sd_url"\s*:\s*"([^"]+)"""" to "browser_native_sd_url",
            """"video_url"\s*:\s*"([^"]+)"""" to "video_url",
            """src"\s*:\s*"([^"]*video[^"]+)"""" to "src (video)",
            """"(https?:[^"]*\.mp4[^"]*)"""" to "mp4 file",
            """"(https?:[^"]*video[^"]*fbcdn\.net[^"]+)"""" to "fbcdn video url",
            """"(https?:[^"]*fbcdn\.net[^"]*video[^"]+)"""" to "fbcdn with video",
            """"(https?:[^"]*fbcdn[^"]+)"""" to "fbcdn quoted (fallback)"
        )

        for ((pattern, name) in patterns) {
            val videoUrl = extractUrlPattern(html, pattern)
            if (videoUrl != null) {
                Log.d(TAG, "找到影片使用模式: $name")
                val chars = videoUrl.toCharArray()
                Log.e(TAG, "原始 URL 字符數: ${chars.size}")
                Log.e(TAG, "前 50 字符: ${chars.take(50).joinToString("")}")
                if (chars.size > 50) {
                    Log.e(TAG, "中間 50 字符 (位置 ${chars.size/2}): ${chars.drop(chars.size/2).take(50).joinToString("")}")
                }

                handleCandidate(videoUrl, name)?.let { return it }
            }
        }

        logVideoRelatedContent(html)

        Log.w(TAG, "所有解析方法都失敗")
        return null
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        // 檢查 URL 是否包含必要的部分
        if (!url.startsWith("http")) return false
        
        // 如果包含 .mp4 肯定是影片
        if (url.contains(".mp4")) return true
        
        // fbcdn URL 必須包含 video 關鍵字或查詢參數
        if (url.contains("fbcdn.net")) {
            return url.contains("video") || 
                   url.contains("bytestart") || 
                   url.contains("_nc_") ||  // Facebook 的內容識別碼
                   url.length > 100  // 影片 URL 通常很長
        }
        
        // 其他包含 video 關鍵字的 URL
        return url.contains("video")
    }

    private fun verifyVideoStream(url: String): Boolean {
        if (performHeadCheck(url)) {
            return true
        }

        return performRangeCheck(url)
    }

    private fun performHeadCheck(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .applyCommonHeaders()
                .header("Accept", "*/*")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HEAD 檢查失敗: ${response.code}")
                    return false
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                val validType = contentType.isBlank() ||
                        contentType.startsWith("video/") ||
                        contentType.contains("mp4") ||
                        contentType.contains("octet-stream")
                val validLength = contentLength == null || contentLength > 0

                if (!validType) {
                    Log.w(TAG, "HEAD Content-Type 非影片: $contentType")
                }

                validType && validLength
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD 檢查異常: ${e.message}")
            false
        }
    }

    private fun performRangeCheck(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .applyCommonHeaders()
                .header("Accept", "*/*")
                .addHeader("Range", "bytes=0-1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Range 檢查失敗: ${response.code}")
                    return false
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                val validType = contentType.isBlank() ||
                        contentType.startsWith("video/") ||
                        contentType.contains("mp4") ||
                        contentType.contains("octet-stream")

                if (!validType) {
                    Log.w(TAG, "Range 回應 Content-Type 非影片: $contentType")
                    response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(64)
                        val read = stream.read(buffer)
                        if (read > 0) {
                            val preview = buffer.copyOf(read).toString(Charsets.UTF_8)
                            Log.d(TAG, "Range 回應內容預覽: $preview")
                        }
                    }
                }

                validType
            }
        } catch (e: Exception) {
            Log.w(TAG, "Range 檢查異常: ${e.message}")
            false
        }
    }
    
    private fun logVideoRelatedContent(html: String) {
        try {
            Log.d(TAG, "開始記錄除錯資訊...")
            
            // 方法 1: 尋找所有包含 fbcdn.net 的 URL
            val fbcdnPattern = """(https?:[^"\s]{0,500}fbcdn\.net[^"\s]{0,500})""".toRegex()
            val fbcdnMatches = fbcdnPattern.findAll(html).take(5).toList()
            if (fbcdnMatches.isNotEmpty()) {
                Log.d(TAG, "找到 ${fbcdnMatches.size} 個 fbcdn URLs:")
                fbcdnMatches.forEachIndexed { index, match ->
                    val url = match.value
                    val chars = url.toCharArray()
                    Log.e(TAG, "  fbcdn ${index + 1} 長度: ${chars.size}")
                    Log.e(TAG, "  fbcdn ${index + 1} 前100: ${chars.take(100).joinToString("")}")
                    if (chars.size > 100) {
                        Log.e(TAG, "  fbcdn ${index + 1} 後100: ${chars.takeLast(100).joinToString("")}")
                    }
                    Log.e(TAG, "  含video: ${url.contains("video")}, 含mp4: ${url.contains(".mp4")}")
                }
            }
            
            // 方法 2: 尋找所有 video 相關的 JSON 鍵值對
            val videoJsonPattern = """"([^"]*video[^"]*)"[:\s]+"([^"]{0,200})"""".toRegex(RegexOption.IGNORE_CASE)
            val videoJsonMatches = videoJsonPattern.findAll(html).take(5).toList()
            if (videoJsonMatches.isNotEmpty()) {
                Log.d(TAG, "找到 ${videoJsonMatches.size} 個 video JSON 鍵值對:")
                videoJsonMatches.forEachIndexed { index, match ->
                    val key = match.groupValues.getOrNull(1) ?: ""
                    val value = match.groupValues.getOrNull(2) ?: ""
                    val valueChars = value.toCharArray()
                    Log.e(TAG, "  JSON ${index + 1} key: $key")
                    Log.e(TAG, "  JSON ${index + 1} 值長度: ${valueChars.size}")
                    Log.e(TAG, "  JSON ${index + 1} 值: ${valueChars.take(150).joinToString("")}")
                }
            }
            
            // 方法 3: 尋找 .mp4 URL
            val mp4Pattern = """(https?:[^"\s]{0,500}\.mp4[^"\s]{0,200})""".toRegex()
            val mp4Matches = mp4Pattern.findAll(html).take(3).toList()
            if (mp4Matches.isNotEmpty()) {
                Log.d(TAG, "找到 ${mp4Matches.size} 個 MP4 URLs:")
                mp4Matches.forEachIndexed { index, match ->
                    val url = match.value
                    val chars = url.toCharArray()
                    Log.e(TAG, "  MP4 ${index + 1} 長度: ${chars.size}")
                    Log.e(TAG, "  MP4 ${index + 1}: ${chars.take(200).joinToString("")}")
                }
            }
            
            // 如果什麼都沒找到
            if (fbcdnMatches.isEmpty() && videoJsonMatches.isEmpty() && mp4Matches.isEmpty()) {
                Log.w(TAG, "未找到任何 video/fbcdn/mp4 相關內容")
                // 記錄 HTML 的一小部分來檢查格式
                val sample = html.substring(0, minOf(1000, html.length))
                    .replace(Regex("[\\x00-\\x1F]"), " ") // 移除控制字符
                Log.d(TAG, "HTML 樣本: ${sample.take(500)}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "記錄除錯資訊時出錯: ${e.message}", e)
        }
    }

    private fun extractUrlPattern(html: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(html)
        return match?.groupValues?.getOrNull(1)
    }

    private fun Request.Builder.applyCommonHeaders(): Request.Builder {
        return this
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", ACCEPT_HEADER)
            .addHeader("Accept-Language", ACCEPT_LANGUAGE)
            .addHeader("Connection", "keep-alive")
            .addHeader("Referer", REFERER)
    }

    private fun unescapeUrl(url: String): String {
        var unescaped = url
            .replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\\\", "")
        
        // 如果 URL 是被編碼的，嘗試解碼
        try {
            if (unescaped.contains("%")) {
                unescaped = URLDecoder.decode(unescaped, "UTF-8")
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL 解碼失敗: ${e.message}")
        }
        
        return unescaped
    }

    private fun extractTitle(html: String): String {
        // 方法 1: 尋找 og:title
        var title = extractUrlPattern(html, """<meta property="og:title" content="([^"]+)"""")
        if (title != null) return title.trim()
        
        // 方法 2: 尋找 <title> 標籤
        val titlePattern = """<title>(.*?)</title>"""
        val match = Regex(titlePattern).find(html)
        title = match?.groupValues?.getOrNull(1)?.trim()
        if (!title.isNullOrEmpty()) return title
        
        return "Facebook Video"
    }

    data class VideoInfo(
        val videoUrl: String,
        val sourceUrl: String,
        val title: String
    )
}
