package com.vget.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.vget.app.databinding.ActivityMainBinding
import com.vget.app.network.VideoDownloader
import com.vget.app.network.VideoExtractor
import com.vget.app.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoExtractor: VideoExtractor
    private lateinit var videoDownloader: VideoDownloader
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化
        videoExtractor = VideoExtractor()
        videoDownloader = VideoDownloader(this)

        setupViews()
        checkPermissions()
    }

    private fun setupViews() {
        // 貼上按鈕
        binding.pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        // 下載按鈕
        binding.downloadButton.setOnClickListener {
            val url = binding.urlEditText.text.toString().trim()
            if (url.isNotEmpty()) {
                if (PermissionHelper.hasStoragePermission(this)) {
                    startDownload(url)
                } else {
                    PermissionHelper.requestStoragePermission(this)
                }
            } else {
                showError(getString(R.string.enter_facebook_url))
            }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text.toString()
            binding.urlEditText.setText(text)
            Toast.makeText(this, "已貼上連結", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "剪貼簿是空的", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (!PermissionHelper.hasStoragePermission(this)) {
            showPermissionDialog()
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage("此應用程式需要儲存空間權限來下載影片")
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                PermissionHelper.requestStoragePermission(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(url: String) {
        if (isDownloading) {
            showError("正在下載中，請稍候")
            return
        }

        isDownloading = true
        setDownloadingState(true)
        updateStatus(getString(R.string.status_analyzing))

        lifecycleScope.launch {
            try {
                // 步驟 1: 提取影片 URL
                val result = withContext(Dispatchers.IO) {
                    videoExtractor.extractVideoUrl(url)
                }

                result.onSuccess { videoInfo ->
                    updateStatus("找到影片，開始下載...")
                    downloadVideo(videoInfo.videoUrl)
                }.onFailure { error ->
                    val errorMessage = when {
                        error.message?.contains("無效的 Facebook") == true -> 
                            "請輸入正確的 Facebook 影片連結"
                        error.message?.contains("無法找到影片") == true -> 
                            "無法找到影片，請確認：\n• 影片為公開狀態\n• 連結正確\n• 不是直播或限時動態"
                        error.message?.contains("timeout") == true -> 
                            "連線逾時，請檢查網路連線"
                        else -> 
                            error.message ?: getString(R.string.error_parse)
                    }
                    showError(errorMessage)
                    setDownloadingState(false)
                    isDownloading = false
                }

            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("Unable to resolve host") == true -> 
                        "無法連線到 Facebook，請檢查網路"
                    e.message?.contains("timeout") == true -> 
                        "連線逾時，請稍後再試"
                    else -> 
                        e.message ?: getString(R.string.error_network)
                }
                showError(errorMessage)
                setDownloadingState(false)
                isDownloading = false
            }
        }
    }

    private fun downloadVideo(videoUrl: String) {
        android.util.Log.e("MainActivity", "=== 準備下載影片 ===")
        android.util.Log.e("MainActivity", "影片 URL 長度: ${videoUrl.length}")
        
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                android.util.Log.e("MainActivity", "開始收集下載進度...")
                
                videoDownloader.downloadVideo(videoUrl)
                    .collect { progress ->
                        android.util.Log.e("MainActivity", "收到進度更新: ${progress.javaClass.simpleName}")

                        when (progress) {
                            is VideoDownloader.DownloadProgress.Starting -> {
                                android.util.Log.e("MainActivity", "下載開始")
                                updateStatus(getString(R.string.downloading))
                                updateProgress(0)
                            }
                            is VideoDownloader.DownloadProgress.Progress -> {
                                android.util.Log.e("MainActivity", "進度: ${progress.percentage}%")
                                updateProgress(progress.percentage)
                                updateProgressText(progress)
                            }
                            is VideoDownloader.DownloadProgress.Completed -> {
                                android.util.Log.e("MainActivity", "下載完成: ${progress.filePath}")
                                updateStatus(getString(R.string.download_complete))
                                updateProgress(100)
                                showSuccess(getString(R.string.video_saved, progress.filePath))
                                setDownloadingState(false)
                                isDownloading = false
                            }
                            is VideoDownloader.DownloadProgress.Error -> {
                                android.util.Log.e("MainActivity", "下載錯誤: ${progress.message}")
                                showError(progress.message)
                                setDownloadingState(false)
                                isDownloading = false
                            }
                        }
                    }
                android.util.Log.e("MainActivity", "=== Flow 收集完成 ===")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "=== 下載流程發生異常 ===")
                android.util.Log.e("MainActivity", "異常類型: ${e.javaClass.name}")
                android.util.Log.e("MainActivity", "異常訊息: ${e.message}")
                android.util.Log.e("MainActivity", "Stack trace: ${e.stackTraceToString()}")
                e.printStackTrace()

                showError("下載失敗: ${e.message}")
                setDownloadingState(false)
                isDownloading = false
            }
        }
    }

    private fun setDownloadingState(downloading: Boolean) {
        binding.downloadButton.isEnabled = !downloading
        binding.urlEditText.isEnabled = !downloading
        binding.pasteButton.isEnabled = !downloading
        binding.progressCard.visibility = if (downloading) View.VISIBLE else View.GONE
    }

    private fun updateStatus(status: String) {
        binding.statusText.text = status
    }

    private fun updateProgress(percentage: Int) {
        binding.progressBar.progress = percentage
        binding.progressText.text = "$percentage%"
    }

    private fun updateProgressText(progress: VideoDownloader.DownloadProgress.Progress) {
        val downloaded = formatFileSize(progress.downloadedBytes)
        val total = formatFileSize(progress.totalBytes)
        binding.progressText.text = "${progress.percentage}% ($downloaded / $total)"
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.error))
            .show()
    }

    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.success))
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionHelper.STORAGE_PERMISSION_CODE -> {
                if (PermissionHelper.isPermissionGranted(grantResults)) {
                    Toast.makeText(this, "權限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    showError("需要儲存空間權限才能下載影片")
                }
            }
        }
    }
}
