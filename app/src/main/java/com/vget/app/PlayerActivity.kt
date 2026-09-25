package com.vget.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.vget.app.databinding.ActivityPlayerBinding
import com.vget.app.network.MediaStream
import com.vget.app.network.VideoPreview

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var position = 0L
    private var playWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        position = savedInstanceState?.getLong("position") ?: 0L
        playWhenReady = savedInstanceState?.getBoolean("playing") ?: true
        binding.playerTitle.text = intent.getStringExtra("title")
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        if (player != null) return
        try {
            val video = requireNotNull(readStream("video"))
            val audio = readStream("audio")
            val source = if (audio == null) mediaSource(video)
                else MergingMediaSource(true, mediaSource(video), mediaSource(audio))
            player = ExoPlayer.Builder(this).build().also {
                binding.playerView.player = it
                it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(if (video.mimeType?.startsWith("audio/") == true) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                it.setHandleAudioBecomingNoisy(true)
                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        binding.playerError.setText(if (video.url.startsWith("content:")) R.string.local_playback_failed else R.string.preview_failed)
                        binding.playerError.visibility = View.VISIBLE
                    }
                })
                it.setMediaSource(source)
                it.seekTo(position)
                it.playWhenReady = playWhenReady
                it.prepare()
            }
        } catch (_: Exception) {
            binding.playerError.setText(R.string.preview_failed)
            binding.playerError.visibility = View.VISIBLE
        }
    }

    private fun mediaSource(stream: MediaStream): MediaSource {
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(stream.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val item = MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType).build()
        return factory.createMediaSource(item)
    }

    private fun readStream(prefix: String): MediaStream? {
        val url = intent.getStringExtra("${prefix}_url") ?: return null
        val bundle = intent.getBundleExtra("${prefix}_headers")
        val headers = bundle?.keySet()?.associateWith { bundle.getString(it).orEmpty() }.orEmpty()
        return MediaStream(url, headers, intent.getStringExtra("${prefix}_mime"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", player?.playWhenReady ?: playWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        player?.let {
            position = it.currentPosition
            playWhenReady = it.playWhenReady
            binding.playerView.player = null
            it.release()
        }
        player = null
        super.onStop()
    }

    companion object {
        fun open(context: Context, preview: VideoPreview, title: String) {
            val intent = Intent(context, PlayerActivity::class.java).putExtra("title", title)
            fun put(prefix: String, stream: MediaStream) {
                intent.putExtra("${prefix}_url", stream.url)
                intent.putExtra("${prefix}_mime", stream.mimeType)
                intent.putExtra("${prefix}_headers", Bundle().apply { stream.headers.forEach { (key, value) -> putString(key, value) } })
            }
            put("video", preview.video)
            preview.audio?.let { put("audio", it) }
            context.startActivity(intent)
        }
    }
}
