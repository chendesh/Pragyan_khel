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

    fun setSlowMotion(isEnabled: Boolean) {
        // 240 fps at 0.125 speed = plays like perfect slow motion at 30fps
        val speed = if (isEnabled) 0.125f else 1.0f
        player?.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        player?.release()
        player = null
    }
}
