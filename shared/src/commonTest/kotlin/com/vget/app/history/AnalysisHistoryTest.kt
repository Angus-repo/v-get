package com.vget.app.history

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisHistoryTest {
    @Test
    fun keepsTheNewestThirtyAnalysesInOrderAfterReload() {
        var stored: String? = null
        for (index in 1..35) {
            stored = AnalysisHistory.adding(stored, "影片 $index", "https://www.facebook.com/reel/$index")
        }
        val expected = (35 downTo 6).map { AnalysisEntry("影片 $it", "https://www.facebook.com/reel/$it") }
        assertEquals(expected, AnalysisHistory.entries(stored))
    }

    @Test
    fun repeatedAnalysesKeepTheirTitlesAndShareUrl() {
        val url = "https://www.xiaohongshu.com/explore/abcdef0123456789abcdef01?xsec_token=A%2FB+C"
        val title = "中文「片名」與 \"引號\""
        val first = AnalysisHistory.adding(null, title, url)
        val second = AnalysisHistory.adding(first, "更新後的片名", url)
        assertEquals(listOf(AnalysisEntry("更新後的片名", url), AnalysisEntry(title, url)),
            AnalysisHistory.entries(second))
    }

    @Test
    fun recordingCanResumeIfStorageIsEmptyOrDamaged() {
        val url = "https://www.threads.com/share/Example/"
        for (stored in listOf(null, "", "{")) {
            assertEquals(emptyList(), AnalysisHistory.entries(stored))
            val next = AnalysisHistory.adding(stored, "影片", url)
            assertEquals(listOf(AnalysisEntry("影片", url)), AnalysisHistory.entries(next))
        }
    }
}
