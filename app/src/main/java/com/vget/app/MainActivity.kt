package com.vget.app

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.vget.app.databinding.ActivityMainBinding
import com.vget.app.network.DownloadErrors
import com.vget.app.network.DownloadFormat
import com.vget.app.network.FacebookPageException
import com.vget.app.network.formatBytes
import com.vget.app.network.MediaStream
import com.vget.app.network.SavedVideo
import com.vget.app.network.VideoDetails
import com.vget.app.network.VideoDownloadService
import com.vget.app.network.VideoDownloader.DownloadProgress
import com.vget.app.network.VideoPreview
import com.vget.app.network.VideoQuality
import com.vget.app.network.VideoSource
import com.vget.app.utils.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var downloads: VideoDownloadService
    private var activeJob: Job? = null
    private var busy = false
    private var preparedVideo: VideoDetails? = null
    private data class PendingDownload(val video: VideoDetails, val quality: VideoQuality, val format: DownloadFormat)
    private var pendingDownload: PendingDownload? = null
    private var savedVideo: SavedVideo? = null
    private var savedTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (resources.configuration.fontScale >= 1.5f) {
            binding.inputActions.orientation = LinearLayout.VERTICAL
            listOf(binding.pasteButton, binding.downloadButton).forEach { button ->
                button.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
        downloads = VideoDownloadService(applicationContext)
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { analyzeVideo() }
        binding.downloadSelectedButton.setOnClickListener { requestDownload() }
        binding.downloadMp3Button.setOnClickListener { requestDownload(DownloadFormat.MP3) }
        binding.previewButton.setOnClickListener { previewSelected() }
        binding.playDownloadedButton.setOnClickListener {
            savedVideo?.let { PlayerActivity.open(this, VideoPreview(MediaStream(it.uri, mimeType = it.mimeType)), savedTitle) }
        }
        binding.cancelButton.setOnClickListener { activeJob?.cancel() }
        binding.updateEngineButton.setOnClickListener { updateEngine() }
        binding.copyErrorButton.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.error_details), binding.errorDetails.text))
            Toast.makeText(this, R.string.error_copied, Toast.LENGTH_SHORT).show()
        }
        binding.urlEditText.doAfterTextChanged { invalidateSelection() }
        binding.qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateSelectionControls() }
            override fun onNothingSelected(parent: AdapterView<*>?) { updateSelectionControls() }
        }
        savedInstanceState?.getString("saved_uri")?.let { uri ->
            savedVideo = SavedVideo(savedInstanceState.getString("saved_path").orEmpty(), uri,
                savedInstanceState.getString("saved_mime") ?: "video/mp4")
            savedTitle = savedInstanceState.getString("saved_title").orEmpty()
            binding.progressCard.visibility = View.VISIBLE
        }
        updateSelectionControls()
        if (savedInstanceState == null) receiveShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (busy) showError(getString(R.string.busy)) else receiveShare(intent)
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
        if (text.isNullOrBlank()) Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
        else {
            binding.urlEditText.setText(text)
            Toast.makeText(this, R.string.link_pasted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun invalidateSelection() {
        preparedVideo = null
        pendingDownload = null
        binding.qualityCard.visibility = View.GONE
        binding.urlInputLayout.error = null
        updateSelectionControls()
    }

    private fun analyzeVideo() {
        if (busy) return
        val source = try {
            VideoSource.parse(binding.urlEditText.text.toString())
        } catch (e: IllegalArgumentException) {
            binding.urlInputLayout.error = e.message ?: getString(R.string.invalid_url)
            return
        }
        invalidateSelection()
        activeJob = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.text = getString(R.string.analyzing_platform, source.platform.displayName)
            try {
                val video = downloads.inspect(source)
                preparedVideo = video
                binding.videoTitle.text = video.title
                binding.qualitySpinner.adapter = QualityAdapter(this@MainActivity, video.qualities) {
                    binding.qualitySpinner.selectedItemPosition
                }
                binding.qualityCard.visibility = View.VISIBLE
                binding.statusText.setText(R.string.quality_ready)
            } catch (e: CancellationException) {
                binding.statusText.setText(R.string.download_cancelled)
                throw e
            } catch (e: Exception) {
                binding.statusText.setText(if ((e as? FacebookPageException)?.requiresLogin == true)
                    R.string.facebook_login_required else R.string.error_parse)
                showError(getString(R.string.analysis_error_detail, DownloadErrors.message(e, source.platform)))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun selectedQuality(): VideoQuality? = preparedVideo?.qualities?.getOrNull(binding.qualitySpinner.selectedItemPosition)

    private fun previewSelected() {
        if (busy) return
        val video = preparedVideo ?: return
        val quality = selectedQuality() ?: return
        val preview = quality.preview ?: return
        PlayerActivity.open(this, preview, "${video.title}\n${quality.label}")
    }

    private fun requestDownload(format: DownloadFormat = DownloadFormat.VIDEO) {
        if (busy) return
        val video = preparedVideo ?: return
        val quality = selectedQuality() ?: return
        if (format == DownloadFormat.MP3 && quality.silent) return
        if (PermissionHelper.hasStoragePermission(this)) startDownload(video, quality, format)
        else {
            pendingDownload = PendingDownload(video, quality, format)
            PermissionHelper.requestStoragePermission(this)
        }
    }

    private fun startDownload(video: VideoDetails, quality: VideoQuality, format: DownloadFormat) {
        activeJob = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.setText(R.string.downloading)
            // Position section 2 after the download controls have been laid out.
            if (format == DownloadFormat.VIDEO) binding.root.doOnLayout {
                binding.root.smoothScrollTo(0, binding.qualityCard.top)
            }
            try {
                downloads.download(video, quality, format).collect { progress ->
                    when (progress) {
                        DownloadProgress.Starting -> binding.statusText.setText(R.string.downloading)
                        is DownloadProgress.Processing -> {
                            binding.statusText.text = progress.message
                            binding.progressBar.isIndeterminate = true
                        }
                        is DownloadProgress.Progress -> {
                            binding.statusText.setText(R.string.downloading)
                            binding.progressBar.isIndeterminate = progress.totalBytes <= 0 && progress.percentage == 0
                            binding.progressBar.progress = progress.percentage
                            binding.progressText.text = if (progress.totalBytes > 0) {
                                "${progress.percentage}% (${formatFileSize(progress.downloadedBytes)} / ${formatFileSize(progress.totalBytes)})"
                            } else "${progress.percentage}%"
                        }
                        is DownloadProgress.Completed -> {
                            savedVideo = progress.video
                            savedTitle = "${video.title}\n${if (format == DownloadFormat.MP3) "MP3 · 192 kbps" else quality.label}"
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.progress = 100
                            binding.progressText.text = "100%"
                            binding.statusText.setText(R.string.download_complete)
                            showSuccess(getString(R.string.video_saved, progress.video.filePath))
                        }
                        is DownloadProgress.Error -> {
                            binding.statusText.setText(R.string.download_failed)
                            showError(getString(if (format == DownloadFormat.MP3) R.string.mp3_error_detail else R.string.video_error_detail, progress.message))
                        }
                    }
                }
            } catch (e: CancellationException) {
                binding.statusText.setText(R.string.download_cancelled)
                throw e
            } catch (e: Exception) {
                binding.statusText.setText(R.string.download_failed)
                showError(getString(if (format == DownloadFormat.MP3) R.string.mp3_error_detail else R.string.video_error_detail,
                    DownloadErrors.message(e, video.source.platform)))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun updateEngine() {
        if (busy) return
        invalidateSelection()
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
            } catch (_: Exception) {
                binding.statusText.setText(R.string.engine_update_failed)
                showError(getString(R.string.engine_update_failed))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.downloadButton.isEnabled = !busy
        binding.pasteButton.isEnabled = !busy
        binding.urlInputLayout.isEnabled = !busy
        binding.urlEditText.isEnabled = !busy
        binding.updateEngineButton.isEnabled = !busy
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressCard.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = busy
        if (busy) {
            binding.errorPanel.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = 0
            binding.progressText.text = ""
        }
        updateSelectionControls()
    }

    private fun updateSelectionControls() {
        val quality = selectedQuality()
        binding.qualitySpinner.isEnabled = !busy
        binding.downloadSelectedButton.isEnabled = !busy && quality != null
        binding.downloadMp3Button.isEnabled = !busy && quality != null && !quality.silent
        binding.mp3Hint.text = if (quality?.silent == true) getString(R.string.mp3_no_audio)
            else getString(R.string.mp3_hint, quality?.mp3Size?.label ?: getString(R.string.size_unavailable))
        binding.previewButton.isEnabled = !busy && quality?.preview != null
        binding.qualityHint.setText(if (quality?.preview == null) R.string.preview_unavailable
            else if (preparedVideo?.qualities?.size == 1) R.string.single_quality else R.string.choose_quality_hint)
        binding.playDownloadedButton.visibility = if (savedVideo != null) View.VISIBLE else View.GONE
        binding.playDownloadedButton.isEnabled = !busy
    }

    private fun formatFileSize(bytes: Long): String = formatBytes(bytes)

    private fun showError(message: String) {
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.errorDetails.text = "$message\n\nV-Get $version · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        binding.errorPanel.visibility = View.VISIBLE
        binding.progressCard.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setTextMaxLines(5)
            .setBackgroundTint(getColor(R.color.error)).setTextColor(getColor(R.color.on_error)).show()
    }

    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setTextMaxLines(4)
            .setBackgroundTint(getColor(R.color.success)).setTextColor(getColor(R.color.on_success)).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savedVideo?.let {
            outState.putString("saved_uri", it.uri)
            outState.putString("saved_path", it.filePath)
            outState.putString("saved_mime", it.mimeType)
            outState.putString("saved_title", savedTitle)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PermissionHelper.STORAGE_PERMISSION_CODE) return
        val pending = pendingDownload
        pendingDownload = null
        if (PermissionHelper.isPermissionGranted(grantResults)) {
            if (pending != null && pending.video == preparedVideo && !busy) startDownload(pending.video, pending.quality, pending.format)
        } else showError(getString(R.string.permission_required))
    }
}
