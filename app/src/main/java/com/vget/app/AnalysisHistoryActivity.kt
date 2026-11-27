package com.vget.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.vget.app.databinding.ActivityAnalysisHistoryBinding
import com.vget.app.databinding.RowAnalysisHistoryBinding
import com.vget.app.history.AnalysisHistory

class AnalysisHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAnalysisHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val entries = AnalysisHistory.entries(preferences.getString(AnalysisHistory.storageKey, null))
        binding.emptyState.isVisible = entries.isEmpty()
        binding.historyScroll.isVisible = entries.isNotEmpty()
        if (entries.isNotEmpty()) binding.toolbar.subtitle = getString(R.string.history_recent_count, entries.size)

        for (entry in entries) {
            val row = RowAnalysisHistoryBinding.inflate(layoutInflater, binding.entries, false)
            val title = entry.displayTitle
            val source = entry.sourceLabel
            row.videoTitle.text = title
            row.sourceLabel.text = source
            row.openVideo.contentDescription = getString(R.string.history_open_video, source, title)
            row.openVideo.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.pageUrl)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            row.copyLink.contentDescription = getString(R.string.history_copy_link, title)
            row.copyLink.setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, entry.pageUrl))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.history_link_copied, Toast.LENGTH_SHORT).show()
                }
            }
            binding.entries.addView(row.root)
        }
    }

    companion object {
        const val PREFERENCES = "analysis_history"
    }
}
