package com.vget.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.vget.app.databinding.ActivityMainBinding
import com.vget.app.network.DownloadErrors
import com.vget.app.network.VideoDownloadService
import com.vget.app.network.VideoDownloader.DownloadProgress
import com.vget.app.network.VideoSource
import com.vget.app.utils.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var downloads: VideoDownloadService
    private var activeJob: Job? = null
    private var pendingSource: VideoSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        downloads = VideoDownloadService(applicationContext)
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { requestDownload() }
        binding.cancelButton.setOnClickListener { activeJob?.cancel() }
        binding.updateEngineButton.setOnClickListener { updateEngine() }
        if (savedInstanceState == null) receiveShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (activeJob?.isActive == true) {
            showError(getString(R.string.busy))
        } else {
            receiveShare(intent)
        }
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.urlEditText.setText(it) }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this) else null
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            binding.urlEditText.setText(text)
            Toast.makeText(this, R.string.link_pasted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDownload() {
        if (activeJob?.isActive == true) return
        val source = try {
            VideoSource.parse(binding.urlEditText.text.toString())
        } catch (e: IllegalArgumentException) {
            binding.urlInputLayout.error = e.message ?: getString(R.string.invalid_url)
            return
        }
        binding.urlInputLayout.error = null
        if (PermissionHelper.hasStoragePermission(this)) {
            startDownload(source)
        } else {
            pendingSource = source
            PermissionHelper.requestStoragePermission(this)
        }
    }

    private fun startDownload(source: VideoSource) {
        activeJob = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.text = getString(R.string.analyzing_platform, source.platform.displayName)
            try {
                downloads.download(source).collect { progress ->
                    when (progress) {
                        DownloadProgress.Starting -> {
                            binding.statusText.setText(R.string.downloading)
                        }
                        is DownloadProgress.Processing -> {
                            binding.statusText.text = progress.message
                            binding.progressBar.isIndeterminate = true
                        }
                        is DownloadProgress.Progress -> {
                            binding.statusText.setText(R.string.downloading)
                            // yt-dlp reports percentages for each audio/video stream separately.
                            binding.progressBar.isIndeterminate = progress.totalBytes <= 0 && progress.percentage == 0
                            binding.progressBar.progress = progress.percentage
                            binding.progressText.text = if (progress.totalBytes > 0) {
                                "${progress.percentage}% (${formatFileSize(progress.downloadedBytes)} / ${formatFileSize(progress.totalBytes)})"
                            } else "${progress.percentage}%"
                        }
                        is DownloadProgress.Completed -> {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.progress = 100
                            binding.progressText.text = "100%"
                            binding.statusText.setText(R.string.download_complete)
                            showSuccess(getString(R.string.video_saved, progress.filePath))
                        }
                        is DownloadProgress.Error -> {
                            binding.statusText.setText(R.string.download_failed)
                            showError(progress.message)
                        }
                    }
                }
            } catch (e: CancellationException) {
                binding.statusText.setText(R.string.download_cancelled)
                throw e
            } catch (e: Exception) {
                binding.statusText.setText(R.string.download_failed)
                showError(DownloadErrors.message(e, source.platform))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun updateEngine() {
        if (activeJob?.isActive == true) return
        activeJob = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.setText(R.string.updating_engine)
            try {
                downloads.updateEngine()
                binding.statusText.setText(R.string.engine_updated)
                showSuccess(getString(R.string.engine_updated))
            } catch (e: CancellationException) {
                binding.statusText.setText(R.string.download_cancelled)
                throw e
            } catch (e: Exception) {
                binding.statusText.setText(R.string.engine_update_failed)
                showError(getString(R.string.engine_update_failed))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.downloadButton.isEnabled = !busy
        binding.pasteButton.isEnabled = !busy
        binding.urlEditText.isEnabled = !busy
        binding.updateEngineButton.isEnabled = !busy
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressCard.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = busy
        if (busy) {
            binding.progressBar.progress = 0
            binding.progressText.text = ""
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setTextMaxLines(5).setBackgroundTint(getColor(R.color.error)).show()
    }

    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setTextMaxLines(4).setBackgroundTint(getColor(R.color.success)).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PermissionHelper.STORAGE_PERMISSION_CODE) return
        val source = pendingSource
        pendingSource = null
        if (PermissionHelper.isPermissionGranted(grantResults)) {
            if (source != null && activeJob?.isActive != true) startDownload(source)
        } else {
            showError(getString(R.string.permission_required))
        }
    }
}
