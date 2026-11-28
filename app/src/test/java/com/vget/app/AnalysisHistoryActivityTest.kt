package com.vget.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vget.app.history.AnalysisHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisHistoryActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun opensDedicatedPageFromHomeAndDisplaysEmptyState() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { home ->
            home.get().findViewById<View>(R.id.historyButton).performClick()
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(AnalysisHistoryActivity::class.java.name, intent.component?.className)
            Robolectric.buildActivity(AnalysisHistoryActivity::class.java, intent).setup().use { history ->
                assertEquals(View.VISIBLE, history.get().findViewById<View>(R.id.emptyState).visibility)
            }
        }
    }

    @Test
    fun copyingAndOpeningUseIndependentTargetsAndTheExactSavedUrl() {
        val url = "https://www.facebook.com/share/v/Example/?s=A%2FB+C"
        // The history was persisted by 2.0.1 before this screen existed.
        val oldRecord = """[{"title":"第一部影片","pageUrl":"$url"}]"""
        app.getSharedPreferences("analysis_history", Context.MODE_PRIVATE)
            .edit().putString(AnalysisHistory.storageKey, oldRecord).commit()
        Robolectric.buildActivity(AnalysisHistoryActivity::class.java).setup().use { history ->
            val activity = history.get()
            val row = activity.findViewById<LinearLayout>(R.id.entries).getChildAt(0)
            assertEquals("第一部影片", row.findViewById<TextView>(R.id.videoTitle).text.toString())
            row.findViewById<View>(R.id.copyLink).performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(url, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertNull(shadowOf(app).nextStartedActivity)
            row.findViewById<View>(R.id.openVideo).performClick()
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
    }
}
