package com.vget.app

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.vget.app.MainViewModel.Phase
import com.vget.app.databinding.ActivityMainBinding
import com.vget.app.network.FacebookUrl
import com.vget.app.network.VideoInfo
import com.vget.app.utils.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: MainViewModel by viewModels()
    private var renderedVideo: VideoInfo? = null
    private var pendingShare: String? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.download() else message(getString(R.string.permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.urlEditText.setText(model.state.value.input)
        binding.urlEditText.doAfterTextChanged { model.input(it?.toString().orEmpty()) }
        binding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
            if (text.isNullOrBlank()) message(getString(R.string.clipboard_empty))
            else model.input(FacebookUrl.fromSharedText(text))
        }
        binding.clearButton.setOnClickListener { model.input("") }
        binding.analyzeButton.setOnClickListener { model.analyze() }
        binding.downloadButton.setOnClickListener {
            if (PermissionHelper.hasStoragePermission(this)) model.download()
            else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        binding.cancelButton.setOnClickListener { model.cancel() }
        binding.openButton.setOnClickListener {
            model.state.value.savedUri?.let { uri ->
                open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        binding.filesButton.setOnClickListener { open(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        binding.qualityGroup.setOnCheckedStateChangeListener { group, ids ->
            ids.firstOrNull()?.let { id -> model.select(group.findViewById<Chip>(id).tag as Int) }
        }
        if (savedInstanceState == null) receiveShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    render(state)
                    if (state.phase != Phase.RESTORING) {
                        pendingShare?.let { text ->
                            pendingShare = null
                            if (state.busy) message(getString(R.string.finish_current)) else model.input(text)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(FacebookUrl::fromSharedText) ?: return
        if (model.state.value.phase == Phase.RESTORING) pendingShare = text
        else if (model.state.value.busy) message(getString(R.string.finish_current)) else model.input(text)
    }

    private fun render(state: MainViewModel.UiState) = with(binding) {
        if (urlEditText.text.toString() != state.input) urlEditText.setText(state.input)
        urlEditText.isEnabled = !state.busy
        pasteButton.isEnabled = !state.busy
        clearButton.isEnabled = !state.busy && state.input.isNotEmpty()
        analyzeButton.isEnabled = !state.busy && state.input.isNotBlank()
        analyzeButton.setText(if (state.phase == Phase.ANALYZING) R.string.analyzing else R.string.analyze)
        videoCard.isVisible = state.video != null
        if (renderedVideo != state.video) {
            renderedVideo = state.video
            qualityGroup.removeAllViews()
            state.video?.let { video ->
                videoTitle.text = video.title
                video.formats.forEachIndexed { index, format ->
                    qualityGroup.addView(Chip(this@MainActivity).apply {
                        id = View.generateViewId()
                        tag = index
                        text = when (format.quality) {
                            "HD" -> getString(R.string.quality_hd)
                            "SD" -> getString(R.string.quality_sd)
                            else -> "MP4"
                        }
                        isCheckable = true
                        isChecked = index == state.selected
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                    })
                }
            }
        }
        for (i in 0 until qualityGroup.childCount) {
            (qualityGroup.getChildAt(i) as Chip).apply { isEnabled = !state.busy; isChecked = i == state.selected }
        }
        downloadButton.isEnabled = !state.busy
        statusCard.isVisible = state.phase !in listOf(Phase.IDLE, Phase.READY)
        progressBar.isVisible = state.busy
        val determinate = state.phase == Phase.DOWNLOADING && state.total > 0
        progressBar.isIndeterminate = !determinate
        if (determinate) progressBar.progress = (state.bytes * 100.0 / state.total).toInt().coerceIn(0, 100)
        statusText.text = when (state.phase) {
            Phase.RESTORING -> getString(R.string.restoring)
            Phase.ANALYZING -> getString(R.string.analyzing)
            Phase.DOWNLOADING -> state.message.ifBlank { getString(R.string.downloading) }
            Phase.CANCELLING -> getString(R.string.cancelling)
            Phase.COMPLETE -> getString(R.string.download_complete)
            Phase.ERROR -> getString(R.string.download_failed)
            else -> ""
        }
        progressText.text = when (state.phase) {
            Phase.DOWNLOADING -> {
                val bytes = Formatter.formatShortFileSize(this@MainActivity, state.bytes.coerceAtLeast(0))
                if (determinate) getString(R.string.progress_known, progressBar.progress, bytes,
                    Formatter.formatShortFileSize(this@MainActivity, state.total))
                else getString(R.string.progress_unknown, bytes)
            }
            Phase.COMPLETE -> getString(R.string.saved_location, state.fileName)
            Phase.ERROR -> state.message
            Phase.ANALYZING -> getString(R.string.analyzing_detail)
            else -> ""
        }
        progressText.isVisible = progressText.text.isNotEmpty()
        cancelButton.isVisible = state.phase == Phase.ANALYZING || state.phase == Phase.DOWNLOADING
        openButton.isVisible = state.savedUri != null && state.phase == Phase.COMPLETE
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { message(getString(R.string.no_viewer)) }
        catch (_: SecurityException) { message(getString(R.string.file_unavailable)) }
    }

    private fun message(text: String) { Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show() }
}
