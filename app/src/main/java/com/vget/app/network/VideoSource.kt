package com.vget.app.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class VideoPlatform(val displayName: String, val referer: String) {
    FACEBOOK("Facebook", "https://www.facebook.com/"),
    YOUTUBE("YouTube", "https://www.youtube.com/"),
    INSTAGRAM("Instagram", "https://www.instagram.com/"),
    THREADS("Threads", "https://www.threads.com/"),
    XIAOHONGSHU("小紅書", "https://www.xiaohongshu.com/")
}

data class VideoSource(val url: String, val platform: VideoPlatform, val postId: String? = null) {
    companion object {
        private val sharedUrl = Regex("https?://[^\\s<>\"“”‘’「」『』\\u3000-\\u303f\\uff00-\\uffef]+", RegexOption.IGNORE_CASE)
        private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        private val shortcode = Regex("[A-Za-z0-9_-]+")
        private val noteId = Regex("[0-9a-fA-F]{24}")

        /** Accept a pasted URL or the text produced by an Android share sheet. */
        fun parse(input: String): VideoSource {
            val text = input.trim()
            val rawCandidate = sharedUrl.find(text)?.value?.trimEnd('.', ',', ')', ']', '}', '。', '，', '）', '」', '』')
                ?: text.let { if ("://" in it) it else "https://$it" }
            val candidate = if (rawCandidate.toHttpUrlOrNull()?.host?.let(::platformForHost) == VideoPlatform.XIAOHONGSHU) {
                // XHS share tokens are ASCII; the app may attach Chinese copy
                // instructions without a space. Keep other platforms' Unicode paths.
                Regex("\\p{IsHan}").find(rawCandidate)?.range?.first?.let { rawCandidate.substring(0, it) } ?: rawCandidate
            } else rawCandidate
            val parsed = candidate.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("請輸入有效的影片網址")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "不支援包含帳號密碼的網址" }
            require(parsed.port == 80 || parsed.port == 443) { "不支援此網址的連接埠" }
            val platform = platformForHost(parsed.host)
                ?: throw IllegalArgumentException("僅支援 Facebook、YouTube、Instagram、Threads 與小紅書影片連結")
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            val builder = parsed.newBuilder().scheme("https").port(443).fragment(null)

            return when (platform) {
                VideoPlatform.XIAOHONGSHU -> {
                    val shortLink = parsed.host == "xhslink.com" || parsed.host.endsWith(".xhslink.com") ||
                        parsed.host == "xhslink.cn" || parsed.host.endsWith(".xhslink.cn")
                    val id = when {
                        !shortLink && segments.size == 2 && segments[0] == "explore" -> segments[1]
                        !shortLink && segments.size == 3 && segments.take(2) == listOf("discovery", "item") -> segments[2]
                        else -> null
                    }
                    val validShare = shortLink && ((segments.size == 1 && segments[0] !in setOf("o", "m", "a")) ||
                        (segments.size == 2 && segments[0] in setOf("o", "m", "a"))) &&
                        shortcode.matches(segments.last())
                    require(validShare || (id != null && noteId.matches(id))) { "請提供小紅書單篇影片筆記或分享連結" }
                    // xsec_token and xsec_source are needed to open shared notes.
                    VideoSource(builder.build().toString(), platform, id?.lowercase())
                }
                VideoPlatform.YOUTUBE -> {
                    val id = when {
                        parsed.host == "youtu.be" || parsed.host == "www.youtu.be" -> segments.singleOrNull()
                        segments == listOf("watch") -> parsed.queryParameter("v")
                        segments.size == 2 && segments[0] in setOf("shorts", "embed", "live") -> segments[1]
                        else -> null
                    }
                    require(id != null && youtubeId.matches(id)) { "請提供 YouTube 單支影片或 Shorts 連結" }
                    VideoSource("https://www.youtube.com/watch?v=$id", platform, id)
                }
                VideoPlatform.INSTAGRAM -> {
                    val postPath = if (segments.size == 3 && segments[0] != "share") segments.drop(1) else segments
                    val isPost = postPath.size == 2 && postPath[0] in setOf("p", "reel", "reels", "tv") && shortcode.matches(postPath[1])
                    val isShare = segments.size in 2..3 && segments[0] == "share" && shortcode.matches(segments.last())
                    require(isPost || isShare) { "請提供 Instagram 貼文或 Reels 影片連結" }
                    VideoSource(builder.host("www.instagram.com").query(null).build().toString(), platform,
                        if (isPost) postPath[1] else null)
                }
                VideoPlatform.THREADS -> {
                    val id = when {
                        segments.size == 3 && segments[0].startsWith("@") && segments[1] == "post" -> segments[2]
                        segments.size == 2 && segments[0] == "t" -> segments[1]
                        else -> null
                    }
                    val isShare = segments.size == 2 && segments[0] == "share" && shortcode.matches(segments[1])
                    require((id != null && shortcode.matches(id)) || isShare) { "請提供 Threads 單篇貼文連結" }
                    VideoSource(builder.host("www.threads.com").query(null).build().toString(), platform, id)
                }
                VideoPlatform.FACEBOOK -> {
                    require(segments.isNotEmpty()) { "請提供 Facebook 影片連結" }
                    if (parsed.host == "m.facebook.com" || parsed.host == "mobile.facebook.com") {
                        builder.host("www.facebook.com")
                    }
                    VideoSource(builder.build().toString(), platform)
                }
            }
        }

        internal fun platformForHost(host: String): VideoPlatform? {
            fun matches(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                matches("facebook.com") || matches("fb.com") || matches("fb.watch") -> VideoPlatform.FACEBOOK
                matches("youtube.com") || matches("youtu.be") || matches("youtube-nocookie.com") -> VideoPlatform.YOUTUBE
                matches("instagram.com") -> VideoPlatform.INSTAGRAM
                matches("threads.com") || matches("threads.net") -> VideoPlatform.THREADS
                matches("xiaohongshu.com") || matches("rednote.com") ||
                    matches("xhslink.com") || matches("xhslink.cn") -> VideoPlatform.XIAOHONGSHU
                else -> null
            }
        }
    }
}
