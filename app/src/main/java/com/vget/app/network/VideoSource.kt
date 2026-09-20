package com.vget.app.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class VideoPlatform(val displayName: String, val referer: String) {
    FACEBOOK("Facebook", "https://www.facebook.com/"),
    YOUTUBE("YouTube", "https://www.youtube.com/"),
    INSTAGRAM("Instagram", "https://www.instagram.com/"),
    THREADS("Threads", "https://www.threads.com/")
}

data class VideoSource(val url: String, val platform: VideoPlatform, val postId: String? = null) {
    companion object {
        private val sharedUrl = Regex("https?://[^\\s<>\"“”]+", RegexOption.IGNORE_CASE)
        private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        private val shortcode = Regex("[A-Za-z0-9_-]+")

        /** Accept a pasted URL or the text produced by an Android share sheet. */
        fun parse(input: String): VideoSource {
            val text = input.trim()
            val candidate = sharedUrl.find(text)?.value?.trimEnd('.', ',', ')', ']', '}', '。', '，', '）', '」', '』')
                ?: text.let { if ("://" in it) it else "https://$it" }
            val parsed = candidate.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("請輸入有效的影片網址")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "不支援包含帳號密碼的網址" }
            require(parsed.port == 80 || parsed.port == 443) { "不支援此網址的連接埠" }
            val platform = platformForHost(parsed.host)
                ?: throw IllegalArgumentException("僅支援 Facebook、YouTube、Instagram 與 Threads 影片連結")
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            val builder = parsed.newBuilder().scheme("https").port(443).fragment(null)

            return when (platform) {
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
                else -> null
            }
        }
    }
}
