package com.hex.stenographer.ui.main

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** One player per screen. Async preparation keeps provider I/O off the UI thread. */
internal class FragmentPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
    fun play(uri: Uri, startMs: Long, endMs: Long, onError: () -> Unit) {
        stop()
        val next = MediaPlayer()
        player = next
        try {
            next.setDataSource(context, uri)
            next.setOnErrorListener { _, _, _ -> stop(); onError(); true }
            next.setOnPreparedListener {
                if (player !== next) return@setOnPreparedListener
                next.setOnSeekCompleteListener {
                    if (player === next) {
                        next.start()
                        handler.postDelayed(object : Runnable {
                            override fun run() {
                                if (player !== next) return
                                if (next.currentPosition.toLong() >= endMs) stop()
                                else handler.postDelayed(this, 40)
                            }
                        }, 40)
                    }
                }
                next.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            }
            next.setOnCompletionListener { stop() }
            next.prepareAsync()
        } catch (_: Exception) {
            stop()
            onError()
        }
    }
}
