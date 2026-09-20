package com.vget.app.network

import org.junit.Assert.*
import org.junit.Test

class FacebookQualityParserTest {
    private val source = VideoSource.parse("https://www.facebook.com/watch/?v=123")
    @Test fun hdAndSdRemainSeparateWithoutInventingPixelDimensions() {
        val qualities = FacebookQualityParser.parse("""{"browser_native_sd_url":"https:\/\/video.fbcdn.net\/sd.mp4","browser_native_hd_url":"https:\/\/video.fbcdn.net\/hd.mp4?sig=A%2FB+C\u0026token=1"}""", source)
        assertEquals(listOf("hd", "sd"), qualities.map { it.id })
        assertEquals("https://video.fbcdn.net/hd.mp4?sig=A%2FB+C&token=1", qualities.first().directUrl)
        assertTrue(qualities.first().label.contains("HD"))
        assertNull(qualities.first().height)
        assertEquals(qualities.first().directUrl, qualities.first().preview?.video?.url)
    }

    @Test fun duplicateVariantsAndSingleSourceDoNotPretendToOfferMoreQualities() {
        assertEquals(1, FacebookQualityParser.parse("""{"hd_src":"https://video.fbcdn.net/a.mp4","sd_src":"https://video.fbcdn.net/a.mp4"}""", source).size)
        val quality = FacebookQualityParser.parse("""<meta content="https://video.fbcdn.net/a.mp4?a=%2F&amp;b=+" property="og:video">""", source).single()
        assertTrue(quality.label.contains("解析度未提供"))
        assertEquals("https://video.fbcdn.net/a.mp4?a=%2F&b=+", quality.directUrl)
    }
}
