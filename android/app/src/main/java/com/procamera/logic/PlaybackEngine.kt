package com.procamera.logic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlaybackEngine(private val context: Context) {

    private var player: ExoPlayer? = null

    fun initializePlayer(playerView: PlayerView, videoUri: Uri) {
        player = ExoPlayer.Builder(context).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(videoUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    fun setSlowMotion(isEnabled: Boolean, captureFps: Int) {
        // Calculate speed to targets 30fps playback
        // e.g. 240 fps -> 30/240 = 0.125 speed
        // e.g. 120 fps -> 30/120 = 0.25 speed
        val targetSpeed = 30f / captureFps.coerceAtLeast(30)
        val speed = if (isEnabled) targetSpeed else 1.0f
        player?.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        player?.release()
        player = null
    }
}
