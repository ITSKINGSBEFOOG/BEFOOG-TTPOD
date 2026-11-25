package com.itskings.ttpodreborn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt
import kotlin.random.Random

data class Song(val id: Long, val title: String, val artist: String, val path: String, val uri: Uri)

class MainActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        var instance: MainActivity? = null
        const val ACTION_PREV = "com.itskings.ttpodreborn.PREV"
        const val ACTION_PLAY = "com.itskings.ttpodreborn.PLAY"
        const val ACTION_NEXT = "com.itskings.ttpodreborn.NEXT"
    }
    private var mediaPlayer: MediaPlayer? = null
    private var allSongs = mutableListOf<Song>()
    private var filteredSongs = mutableListOf<Song>()
    private var currentIndex = -1
    private var isShuffle = false
    private var repeatMode = 0
    private var shakeNextOn = false
    private var shakePrevOn = false
    private var playlists = mutableMapOf<String, MutableList<Song>>()
    private var currentFolder = "ALL SONGS"
    private var showCount = 100
    private var cameFromBefoog2 = false
    private var lastSavedPosition = 0
    private var wasPlayingBeforePause = false
    private var isRestoring = false
    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var mediaSession: MediaSession? = null
    private var notifReceiver: BroadcastReceiver? = null
    private lateinit var root: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homeView: LinearLayout
    private lateinit var listView: LinearLayout
    private lateinit var playerView: LinearLayout
    private lateinit var befoog2View: LinearLayout
    private lateinit var befoog2Container: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvBigArt: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnPlayMini: Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var tvTimeLeft: TextView
    private lateinit var etSearch: EditText
    private lateinit var songContainer: LinearLayout
    private lateinit var tvFolderTitle: TextView
    private lateinit var tvMiniTitle: TextView
    private lateinit var btnShuffle: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnSleep: Button
    private lateinit var btnFav: Button
    private lateinit var tvCountSongs: TextView
    private lateinit var tvCountFav: TextView
    private lateinit var tvCountFolders: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null) {
                    try {
                        lastSavedPosition = mp.currentPosition
                    } catch (e: Exception) {
                    }
                    if (mp.isPlaying) {
                        seekBar.progress = mp.currentPosition
                        tvTime.text = formatTime(mp.currentPosition)
                        tvTimeLeft.text = formatTime(mp.duration)
                    }
                    saveCurrentState()
                }
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 500)
        }
    }
    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }
    private fun saveCurrentState() {
        try {
            if (currentIndex < 0 || filteredSongs.isEmpty()) {
                return
            }
            val song = filteredSongs[currentIndex]
            var pos = lastSavedPosition
            try {
                pos = mediaPlayer?.currentPosition ?: lastSavedPosition
            } catch (e: Exception) {
                pos = lastSavedPosition
            }
            var playing = false
            try {
                playing = mediaPlayer?.isPlaying == true
            } catch (e: Exception) {
                playing = false
            }
            getSharedPreferences("BEFOOG_STATE", MODE_PRIVATE).edit().putString("last_path", song.path).putString("last_folder", currentFolder).putInt("last_position", pos).putInt("last_index", currentIndex).putBoolean("was_playing", playing).putBoolean("has_last", true).commit()
            lastSavedPosition = pos
        } catch (e: Exception) {
        }
    }
    private fun restoreLastPlayed() {
        try {
            if (isRestoring) {
                return
            }
            val prefs = getSharedPreferences("BEFOOG_STATE", MODE_PRIVATE)
            if (!prefs.getBoolean("has_last", false)) {
                return
            }
            val path = prefs.getString("last_path", "") ?: ""
            if (path.isEmpty()) {
                return
            }
            val folder = prefs.getString("last_folder", "ALL SONGS") ?: "ALL SONGS"
            val pos = prefs.getInt("last_position", 0)
            val wasPlaying = prefs.getBoolean("was_playing", true)
            val song = allSongs.find { it.path == path } ?: return
            currentFolder = folder
            cameFromBefoog2 = playlists.containsKey(folder)
            val base: List<Song> = when {
                folder == "ALL SONGS" -> allSongs
                folder.startsWith("FOLDER:") -> {
                    val fname = folder.removePrefix("FOLDER:")
                    allSongs.filter {
                        it.path.split("/").let { p -> p.size >= 2 && p[p.size - 2] == fname }
                    }
                }
                else -> playlists[folder] ?: mutableListOf()
            }
            filteredSongs = base.toMutableList()
            currentIndex = filteredSongs.indexOf(song)
            if (currentIndex < 0) {
                filteredSongs = mutableListOf(song)
                currentIndex = 0
            }
            isRestoring = true
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(this, song.uri)
            mediaPlayer?.prepare()
            var safePos = pos
            val dur = mediaPlayer?.duration ?: 0
            if (safePos > 0 && safePos < dur - 1000) {
                mediaPlayer?.seekTo(safePos)
            } else {
                safePos = 0
            }
            mediaPlayer?.setOnCompletionListener {
                if (repeatMode == 2) {
                    playSong(song)
                } else {
                    playNext()
                }
            }
            tvTitle.text = song.title
            tvArtist.text = song.artist
            tvMiniTitle.text = "${song.title} - ${song.artist}"
            seekBar.max = dur
            seekBar.progress = safePos
            tvTime.text = formatTime(safePos)
            tvTimeLeft.text = formatTime(dur)
            btnFav.text = if (playlists["BEFOOG 1"]!!.contains(song)) "♥" else "♡"
            tvBigArt.text = "💿"
            if (wasPlaying) {
                mediaPlayer?.start()
                btnPlay.text = "⏸"
                btnPlayMini.text = "⏸"
                Toast.makeText(this, "Continuing: ${song.title} - ${formatTime(safePos)}", Toast.LENGTH_SHORT).show()
            } else {
                btnPlay.text = "▶"
                btnPlayMini.text = "▶"
                Toast.makeText(this, "Paused at ${formatTime(safePos)} - Press Play to continue", Toast.LENGTH_SHORT).show()
            }
            showMusicNotification()
            isRestoring = false
        } catch (e: Exception) {
            isRestoring = false
        }
    }
    private fun goBackFromList() {
        if (cameFromBefoog2) {
            showBefoog2()
        } else {
            showHome()
        }
    }
    override fun onBackPressed() {
        try {
            if (playerView.visibility == View.VISIBLE) {
                showList()
                return
            }
            if (befoog2View.visibility == View.VISIBLE) {
                showHome()
                return
            }
            if (listView.visibility == View.VISIBLE) {
                goBackFromList()
                return
            }
        } catch (e: Exception) {
        }
        try {
            super.onBackPressed()
        } catch (e: Exception) {
            finish()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationAction(intent)
    }
    private fun handleNotificationAction(intent: Intent?) {
        when (intent?.action) {
            "BEFOOG_PREV", ACTION_PREV -> playPrev()
            "BEFOOG_PLAY", ACTION_PLAY -> togglePlay()
            "BEFOOG_NEXT", ACTION_NEXT -> playNext()
        }
        if (intent?.action?.startsWith("BEFOOG_") == true) {
            intent.action = null
        }
    }
    private fun savePlaylists() {
        try {
            val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putStringSet("playlist_names", playlists.keys)
            for ((name, list) in playlists) {
                editor.putString("playlist_$name", list.joinToString("|||") { it.path })
            }
            editor.apply()
        } catch (e: Exception) {
        }
    }
    private fun loadPlaylists() {
        try {
            val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
            val names = prefs.getStringSet("playlist_names", null) ?: return
            for (name in names) {
                val saved = prefs.getString("playlist_$name", "") ?: ""
                val list = mutableListOf<Song>()
                if (saved.isNotEmpty()) {
                    for (p in saved.split("|||")) {
                        val found = allSongs.find { it.path == p }
                        if (found != null) {
                            list.add(found)
                        }
                    }
                }
                playlists[name] = list
            }
        } catch (e: Exception) {
        }
    }
    private fun showPlaylistOptions(name: String) {
        val options = arrayOf("✏️  Rename Playlist", "🗑️
