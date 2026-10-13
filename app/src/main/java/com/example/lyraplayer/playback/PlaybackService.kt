package com.example.lyraplayer.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.lyraplayer.lyrics.LrcParser
import com.example.lyraplayer.widget.LyricsWidgetProvider

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastLyric = ""
    private var lastTitle = ""

    private val lyricTicker = object : Runnable {
        override fun run() {
            val session = mediaSession
            if (session != null) {
                val prefs = getSharedPreferences("lyra", MODE_PRIVATE)
                val lrc = prefs.getString("current_lrc", "").orEmpty()
                val lines = LrcParser.parse(lrc)
                val lyric = LrcParser.displayLine(lrc, session.player.currentPosition)
                val title = prefs.getString("current_title", "Lyra Player").orEmpty()
                if (lyric != lastLyric || title != lastTitle) {
                    lastLyric = lyric
                    lastTitle = title
                    LyricsWidgetProvider.updateAll(
                        this@PlaybackService,
                        title,
                        lyric.ifBlank { "Lirik belum tersedia" }
                    )
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
        handler.post(lyricTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(lyricTicker)
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (mediaSession?.player?.isPlaying != true) stopSelf()
    }
}
