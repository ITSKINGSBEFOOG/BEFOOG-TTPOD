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
                    try { lastSavedPosition = mp.currentPosition } catch (e: Exception) {}
                    if (mp.isPlaying) {
                        seekBar.progress = mp.currentPosition
                        tvTime.text = formatTime(mp.currentPosition)
                        tvTimeLeft.text = formatTime(mp.duration)
                    }
                    saveCurrentState()
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 500)
        }
    }
    private fun dp(v: Int): Int { return (v * resources.displayMetrics.density).toInt() }
    private fun saveCurrentState() {
        try {
            if (currentIndex < 0 || filteredSongs.isEmpty()) return
            val song = filteredSongs[currentIndex]
            val pos = try { mediaPlayer?.currentPosition ?: lastSavedPosition } catch (e: Exception) { lastSavedPosition }
            val playing = try { mediaPlayer?.isPlaying == true } catch (e: Exception) { false }
            getSharedPreferences("BEFOOG_STATE", MODE_PRIVATE).edit().putString("last_path", song.path).putString("last_folder", currentFolder).putInt("last_position", pos).putInt("last_index", currentIndex).putBoolean("was_playing", playing).putBoolean("has_last", true).commit()
            lastSavedPosition = pos
        } catch (e: Exception) {}
    }
    private fun restoreLastPlayed() {
        try {
            if (isRestoring) return
            val prefs = getSharedPreferences("BEFOOG_STATE", MODE_PRIVATE)
            if (!prefs.getBoolean("has_last", false)) return
            val path = prefs.getString("last_path", "") ?: ""
            if (path.isEmpty()) return
            val folder = prefs.getString("last_folder", "ALL SONGS") ?: "ALL SONGS"
            val pos = prefs.getInt("last_position", 0)
            val wasPlaying = prefs.getBoolean("was_playing", true)
            val song = allSongs.find { it.path == path } ?: return
            currentFolder = folder
            cameFromBefoog2 = playlists.containsKey(folder)
            val base: List<Song> = when {
                folder == "ALL SONGS" -> allSongs
                folder.startsWith("FOLDER:") -> { val fname = folder.removePrefix("FOLDER:"); allSongs.filter { it.path.split("/").let { p -> p.size >= 2 && p[p.size - 2] == fname } } }
                else -> playlists[folder] ?: mutableListOf()
            }
            filteredSongs = base.toMutableList()
            currentIndex = filteredSongs.indexOf(song)
            if (currentIndex < 0) { filteredSongs = mutableListOf(song); currentIndex = 0 }
            isRestoring = true
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(this, song.uri)
            mediaPlayer?.prepare()
            var safePos = pos
            val dur = mediaPlayer?.duration ?: 0
            if (safePos > 0 && safePos < dur - 1000) mediaPlayer?.seekTo(safePos) else safePos = 0
            mediaPlayer?.setOnCompletionListener { if (repeatMode == 2) playSong(song) else playNext() }
            tvTitle.text = song.title; tvArtist.text = song.artist; tvMiniTitle.text = "${song.title} - ${song.artist}"
            seekBar.max = dur
            seekBar.progress = safePos
            tvTime.text = formatTime(safePos)
            tvTimeLeft.text = formatTime(dur)
            btnFav.text = if (playlists["BEFOOG 1"]!!.contains(song)) "♥" else "♡"
            tvBigArt.text = "💿"
            if (wasPlaying) { mediaPlayer?.start(); btnPlay.text = "⏸"; btnPlayMini.text = "⏸"; Toast.makeText(this, "Continuing: ${song.title} - ${formatTime(safePos)}", Toast.LENGTH_SHORT).show() } else { btnPlay.text = "▶"; btnPlayMini.text = "▶"; Toast.makeText(this, "Paused at ${formatTime(safePos)} - Press Play to continue", Toast.LENGTH_SHORT).show() }
            showMusicNotification()
            isRestoring = false
        } catch (e: Exception) { isRestoring = false }
    }
    private fun goBackFromList() { if (cameFromBefoog2) showBefoog2() else showHome() }
    override fun onBackPressed() {
        try {
            if (playerView.visibility == View.VISIBLE) { showList(); return }
            if (befoog2View.visibility == View.VISIBLE) { showHome(); return }
            if (listView.visibility == View.VISIBLE) { goBackFromList(); return }
        } catch (e: Exception) {}
        try { super.onBackPressed() } catch (e: Exception) { finish() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleNotificationAction(intent) }
    private fun handleNotificationAction(intent: Intent?) {
        when (intent?.action) {
            "BEFOOG_PREV", ACTION_PREV -> playPrev()
            "BEFOOG_PLAY", ACTION_PLAY -> togglePlay()
            "BEFOOG_NEXT", ACTION_NEXT -> playNext()
        }
        if (intent?.action?.startsWith("BEFOOG_") == true) intent.action = null
    }
    private fun savePlaylists() {
        try {
            val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putStringSet("playlist_names", playlists.keys)
            for ((name, list) in playlists) { editor.putString("playlist_$name", list.joinToString("|||") { it.path }) }
            editor.apply()
        } catch (e: Exception) {}
    }
    private fun loadPlaylists() {
        try {
            val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
            val names = prefs.getStringSet("playlist_names", null) ?: return
            for (name in names) {
                val saved = prefs.getString("playlist_$name", "") ?: ""
                val list = mutableListOf<Song>()
                if (saved.isNotEmpty()) { for (p in saved.split("|||")) { val found = allSongs.find { it.path == p }; if (found != null) list.add(found) } }
                playlists[name] = list
            }
        } catch (e: Exception) {}
    }
    private fun showPlaylistOptions(name: String) {
        val options = arrayOf("✏️  Rename Playlist", "🗑️  Delete Playlist")
        AlertDialog.Builder(this).setTitle(name).setItems(options) { _, which -> if (which == 0) renamePlaylist(name) else deletePlaylist(name) }.show()
    }
    private fun renamePlaylist(oldName: String) {
        if (oldName == "BEFOOG 1" || oldName == "BEFOOG 2") { Toast.makeText(this, "$oldName na system folder, e no fit rename", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(this).apply { hint = oldName; setText(oldName) }
        AlertDialog.Builder(this).setTitle("Rename $oldName").setView(input).setPositiveButton("Rename") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isEmpty() || newName == oldName) return@setPositiveButton
            if (playlists.containsKey(newName)) { Toast.makeText(this, "$newName don already exist", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val list = playlists.remove(oldName) ?: mutableListOf()
            playlists[newName] = list
            try { getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE).edit().remove("playlist_$oldName").apply() } catch (e: Exception) {}
            savePlaylists()
            if (currentFolder == oldName) currentFolder = newName
            refreshBefoog2(); refreshSongs()
            Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun deletePlaylist(name: String) {
        if (name == "BEFOOG 1" || name == "BEFOOG 2") { Toast.makeText(this, "$name na system folder, e no fit delete", Toast.LENGTH_SHORT).show(); return }
        if (!playlists.containsKey(name)) return
        AlertDialog.Builder(this).setTitle("Delete $name?").setMessage("Delete playlist $name? Songs inside no go delete, only playlist.").setPositiveButton("Delete") { _, _ ->
            playlists.remove(name)
            try { getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE).edit().remove("playlist_$name").apply() } catch (e: Exception) {}
            savePlaylists(); refreshBefoog2()
            if (currentFolder == name) { showBefoog2() }
            Toast.makeText(this, "Deleted $name", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun removeSongFromPlaylist(song: Song, playlistName: String) {
        val list = playlists[playlistName] ?: return
        AlertDialog.Builder(this).setTitle("Remove song?").setMessage("Remove ${song.title} from $playlistName?").setPositiveButton("Remove") { _, _ -> list.remove(song); savePlaylists(); refreshBefoog2(); refreshSongs(); Toast.makeText(this, "Removed ${song.title}", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancel", null).show()
    }
    private fun shareSong(song: Song) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, song.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); putExtra(Intent.EXTRA_TEXT, "${song.title} - ${song.artist} • Shared from BEFOOG PLAYER") }
            startActivity(Intent.createChooser(intent, "Share ${song.title} via BEFOOG"))
        } catch (e: Exception) { Toast.makeText(this, "Cannot share this song", Toast.LENGTH_SHORT).show() }
    }
    private fun showSongOptions(song: Song) {
        val isPlaylist = playlists.containsKey(currentFolder)
        if (isPlaylist) {
            val options = arrayOf("↗️  Share Song", "🗑️  Remove from $currentFolder")
            AlertDialog.Builder(this).setTitle(song.title).setItems(options) { _, which -> if (which == 0) shareSong(song) else removeSongFromPlaylist(song, currentFolder) }.show()
        } else {
            val options = arrayOf("↗️  Share Song")
            AlertDialog.Builder(this).setTitle(song.title).setItems(options) { _, _ -> shareSong(song) }.show()
        }
    }
    private fun refreshBefoog2() {
        try {
            if (!::befoog2Container.isInitialized) return
            befoog2Container.removeAllViews()
            val b1 = playlists["BEFOOG 1"] ?: mutableListOf()
            val btn1 = Button(this).apply { text = "❤️ BEFOOG 1  (${b1.size} songs)  ▶"; setBackgroundColor(Color.parseColor("#143A3E")); setTextColor(Color.WHITE) }
            btn1.setOnClickListener { cameFromBefoog2 = true; openFolder("BEFOOG 1") }
            befoog2Container.addView(btn1)
            val others = playlists.keys.filter { it != "BEFOOG 1" && it != "BEFOOG 2" }.sorted()
            if (others.isEmpty()) { befoog2Container.addView(TextView(this).apply { text = "\nNo other playlist yet\n\nTap + CREATE to add one"; setTextColor(Color.WHITE); setPadding(0, 20, 0, 20); gravity = Gravity.CENTER }) }
            for (name in others) {
                val list = playlists[name] ?: mutableListOf()
                val btn = Button(this).apply { text = "⭐ $name  (${list.size} songs)  ▶"; setBackgroundColor(Color.parseColor("#143A3E")); setTextColor(Color.WHITE) }
                btn.setOnClickListener { cameFromBefoog2 = true; openFolder(name) }
                btn.setOnLongClickListener { showPlaylistOptions(name); true }
                befoog2Container.addView(btn)
            }
        } catch (e: Exception) {}
    }
    private fun showBefoog2() { refreshBefoog2(); homeScroll.visibility = View.GONE; listView.visibility = View.GONE; playerView.visibility = View.GONE; befoog2View.visibility = View.VISIBLE }
    private fun smallBtn(text: String, size: Float = 16f): Button { return Button(this).apply { this.text = text; textSize = size; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) } }
    private fun controlBtn(text: String, size: Float): Button { return Button(this).apply { this.text = text; textSize = size; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        shakeNextOn = false; shakePrevOn = false
        playlists["BEFOOG 1"] = mutableListOf(); playlists["BEFOOG 2"] = mutableListOf()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMediaSession(); setupNotifReceiver(); createNotifChannel(); handleNotificationAction(intent)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B2A2E")) }
        setContentView(root)
        homeScroll = ScrollView(this)
        homeView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(100), dp(20), dp(40)) }
        homeScroll.addView(homeView)
        val topTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val t1 = TextView(this).apply { text = "Library"; textSize = 18f; setTextColor(Color.WHITE); setPadding(20, 16, 20, 16); setBackgroundColor(Color.parseColor("#3A5A5E")) }
        val t2 = TextView(this).apply { text = "Online"; textSize = 18f; setTextColor(Color.LTGRAY); setPadding(20, 16, 20, 16) }
        val t3 = TextView(this).apply { text = "Recommend"; textSize = 18f; setTextColor(Color.LTGRAY); setPadding(20, 16, 20, 16) }
        topTabs.addView(t1); topTabs.addView(t2); topTabs.addView(t3); homeView.addView(topTabs)
        homeView.addView(TextView(this).apply { text = "🎵  BEFOOG PLAYER"; textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 4) })
        homeView.addView(TextView(this).apply { text = "Your music, your way - BEFOOG style"; textSize = 13f; setTextColor(Color.LTGRAY) })
        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 24, 0, 8) }
        val itemSongs = makeHomeItem("💿", "My Songs", "0 Medias") { cameFromBefoog2 = false; openFolder("ALL SONGS") }
        tvCountSongs = itemSongs.getChildAt(2) as TextView; grid1.addView(itemSongs)
        val itemFav = makeHomeItem("❤️", "My Favorites", "BEFOOG 1") { cameFromBefoog2 = true; openFolder("BEFOOG 1") }
        tvCountFav = itemFav.getChildAt(2) as TextView; grid1.addView(itemFav)
        val itemFolders = makeHomeItem("📁", "Folders", "0 Folders") { showAllFolders() }
        tvCountFolders = itemFolders.getChildAt(2) as TextView; grid1.addView(itemFolders)
        grid1.addView(makeHomeItem("📄+", "New Playlist", "Create") { showCreatePlaylist() }); homeView.addView(grid1)
        val grid2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        grid2.addView(makeHomeItem("⬇️", "Download", "0 Tasks") { Toast.makeText(this, "Download coming soon", Toast.LENGTH_SHORT).show() })
        grid2.addView(makeHomeItem("🎨", "Custom Ho..", "Theme") { Toast.makeText(this, "BEFOOG PLAYER Theme", Toast.LENGTH_SHORT).show() })
        grid2.addView(makeHomeItem("💤", "Sleep Timer", "OFF") { startSleepTimer() })
        grid2.addView(makeHomeItem("📂", "BEFOOG 2", "All Playlists") { showBefoog2() }); homeView.addView(grid2)
        homeView.addView(TextView(this).apply { text = "🔥  QUICK PLAY"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 32, 0, 8) })
        val quick = Button(this).apply { text = "▶  SHUFFLE PLAY ALL SONGS"; setBackgroundColor(Color.parseColor("#00C8A0")); setTextColor(Color.WHITE) }
        quick.setOnClickListener { if (allSongs.isNotEmpty()) { cameFromBefoog2 = false; isShuffle = true; filteredSongs = allSongs.toMutableList(); currentIndex = Random.nextInt(filteredSongs.size); playSong(filteredSongs[currentIndex]); showPlayer() } else Toast.makeText(this, "No songs found yet - check permission Allow all", Toast.LENGTH_SHORT).show() }
        homeView.addView(quick)
        homeView.addView(TextView(this).apply { text = "📱 Shake phone = Next song  •  Shake OFF"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0) })
        homeView.addView(TextView(this).apply { text = "\n📁 All your playlists dey inside BEFOOG 2\nTap BEFOOG 2 to see them"; textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) })
        befoog2View = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(20), dp(100), dp(20), dp(20)) }
        val backB2 = Button(this).apply { text = "⬅  BACK TO LIBRARY" }; backB2.setOnClickListener { showHome() }; befoog2View.addView(backB2)
        befoog2View.addView(TextView(this).apply { text = "📁 BEFOOG 2 - ALL PLAYLISTS"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 4) })
        befoog2View.addView(TextView(this).apply { text = "All your playlists dey inside here"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 12) })
        val btnNewInside = Button(this).apply { text = "+  CREATE NEW PLAYLIST INSIDE BEFOOG 2"; setBackgroundColor(Color.parseColor("#00C8A0")); setTextColor(Color.WHITE) }
        btnNewInside.setOnClickListener { showCreatePlaylist() }; befoog2View.addView(btnNewInside)
        val scrollB2 = ScrollView(this); befoog2Container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 0) }; scrollB2.addView(befoog2Container); befoog2View.addView(scrollB2); refreshBefoog2()
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(20), dp(100), dp(20), dp(16)) }
        val backHome = Button(this).apply { text = "⬅  BACK" }; backHome.setOnClickListener { goBackFromList() }; listView.addView(backHome)
        tvFolderTitle = TextView(this).apply { text = "📁 ALL SONGS"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }; listView.addView(tvFolderTitle)
        etSearch = EditText(this).apply { hint = "Search inside this folder..."; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }; listView.addView(etSearch)
        val scrollList = ScrollView(this); songContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scrollList.addView(songContainer); listView.addView(scrollList)
        playerView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Color.parseColor("#0E3A3E")); setPadding(dp(24), dp(100), dp(24), dp(16)) }
        val playerTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val backBtn = smallBtn("⬅", 16f); backBtn.setOnClickListener { showList() }
        val topTitleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, dp(8), 0); minimumWidth = 0 }
        tvTitle = TextView(this).apply { text = "No song playing"; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        tvArtist = TextView(this).apply { text = "BEFOOG"; textSize = 13f; setTextColor(Color.LTGRAY); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        topTitleBox.addView(tvTitle); topTitleBox.addView(tvArtist)
        btnFav = smallBtn("♡", 18f); btnFav.setOnClickListener { toggleFav() }
        val btnAddPlayer = smallBtn("+", 18f); btnAddPlayer.setOnClickListener { if (currentIndex >= 0 && filteredSongs.isNotEmpty()) { showAddToPlaylist(filteredSongs[currentIndex]) } else Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show() }
        val btnShare = smallBtn("↗", 16f); btnShare.setOnClickListener { if (currentIndex >= 0 && filteredSongs.isNotEmpty()) shareSong(filteredSongs[currentIndex]) else Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show() }
        playerTop.addView(backBtn); playerTop.addView(topTitleBox); playerTop.addView(btnFav); playerTop.addView(btnAddPlayer); playerTop.addView(btnShare); playerView.addView(playerTop)
        tvBigArt = TextView(this).apply { text = "💿"; textSize = 80f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, 50, 0, 40) }; playerView.addView(tvBigArt)
        playerView.addView(TextView(this).apply { text = "Good Music"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 16) })
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        tvTime = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tvTimeLeft = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.END }
        timeRow.addView(tvTime); timeRow.addView(tvTimeLeft); playerView.addView(timeRow)
        seekBar = SeekBar(this); seekBar.setPadding(dp(4), 0, dp(4), 0); playerView.addView(seekBar)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 20, 0, 12) }
        btnRepeat = controlBtn("↺", 20f); val btnPrev = controlBtn("⏮", 24f); btnPlay = controlBtn("▶", 28f); val btnNext = controlBtn("⏭", 24f); btnShuffle = controlBtn("🔀", 20f)
        controls.addView(btnRepeat); controls.addView(btnPrev); controls.addView(btnPlay); controls.addView(btnNext); controls.addView(btnShuffle); playerView.addView(controls)
        btnSleep = Button(this).apply { text = "💤 Sleep Timer: OFF" }; playerView.addView(btnSleep)
        val shakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        val btnShakeNext = Button(this).apply { text = "Shake Next: OFF"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnShakePrev = Button(this).apply { text = "Shake Prev: OFF"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        shakeRow.addView(btnShakeNext); shakeRow.addView(btnShakePrev); playerView.addView(shakeRow)
        playerView.addView(TextView(this).apply { text = "BEFOOG 1  •  BEFOOG 2"; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); textSize = 13f; setPadding(dp(16), 16, dp(16), 0) })
        val mini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK); setPadding(dp(16), dp(14), dp(16), dp(120)); gravity = Gravity.CENTER_VERTICAL }
        tvMiniTitle = TextView(this).apply { text = "No song"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setPadding(0, 0, dp(6), 0) }
        val miniPrev = Button(this).apply { text = "⏮"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        btnPlayMini = Button(this).apply { text = "▶"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        val miniNext = Button(this).apply { text = "⏭"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        val miniFav = Button(this).apply { text = "♡"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        mini.addView(tvMiniTitle); mini.addView(miniPrev); mini.addView(btnPlayMini); mini.addView(miniNext); mini.addView(miniFav); mini.setOnClickListener { if (currentIndex >= 0) showPlayer() }
        root.addView(homeScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(befoog2View, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(mini)
        btnPlay.setOnClickListener { togglePlay() }; btnPlayMini.setOnClickListener { togglePlay() }; btnNext.setOnClickListener { playNext() }; miniNext.setOnClickListener { playNext() }; btnPrev.setOnClickListener { playPrev() }; miniPrev.setOnClickListener { playPrev() }; miniFav.setOnClickListener { toggleFav() }
        btnShuffle.setOnClickListener { isShuffle = !isShuffle; btnShuffle.text = if (isShuffle) "🔀 ON" else "🔀"; saveCurrentState() }
        btnRepeat.setOnClickListener { repeatMode = (repeatMode + 1) % 3; btnRepeat.text = when (repeatMode) { 1 -> "↺ ALL"; 2 -> "↺ 1"; else -> "↺" } }
        btnShakeNext.setOnClickListener { shakeNextOn = !shakeNextOn; btnShakeNext.text = if (shakeNextOn) "Shake Next: ON" else "Shake Next: OFF" }
        btnShakePrev.setOnClickListener { shakePrevOn = !shakePrevOn; btnShakePrev.text = if (shakePrevOn) "Shake Prev: ON" else "Shake Prev: OFF" }
        btnSleep.setOnClickListener { startSleepTimer() }
        etSearch.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { showCount = 100; filterSongs(s.toString()) }; override fun afterTextChanged(s: Editable?) {} })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { try { mediaPlayer?.seekTo(s?.progress ?: 0); lastSavedPosition = s?.progress ?: 0; saveCurrentState() } catch (e: Exception) {} } })
        checkPermission(); handler.post(updateRunnable)
    }
    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "BEFOOG_PLAYER")
            mediaSession?.setCallback(object : MediaSession.Callback() { override fun onPlay() { togglePlay() }; override fun onPause() { togglePlay() }; override fun onSkipToNext() { playNext() }; override fun onSkipToPrevious() { playPrev() } })
            mediaSession?.isActive = true
        } catch (e: Exception) {}
    }
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel("befoog_music", "BEFOOG Music", NotificationManager.IMPORTANCE_LOW); (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) }
        if (Build.VERSION.SDK_INT >= 33) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102) }
    }
    private fun setupNotifReceiver() {
        notifReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { when (intent?.action) { ACTION_PREV -> playPrev(); ACTION_NEXT -> playNext(); ACTION_PLAY -> togglePlay() } } }
        val f = IntentFilter(); f.addAction(ACTION_PREV); f.addAction(ACTION_PLAY); f.addAction(ACTION_NEXT)
        try { if (Build.VERSION.SDK_INT >= 33) registerReceiver(notifReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(notifReceiver, f) } catch (e: Exception) {}
    }
    private fun showMusicNotification() {
        try {
            if (currentIndex < 0 || filteredSongs.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val song = filteredSongs[currentIndex]
            val isPlaying = mediaPlayer?.isPlaying == true
            mediaSession?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, song.title).putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist).build())
            val playState = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val playPos = try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L }
            mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS).setState(playState, playPos, 1f).build())
            fun pi(action: String, code: Int): PendingIntent { return PendingIntent.getActivity(this, code, Intent(this, MainActivity::class.java).apply { this.action = action; flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "befoog_music") else Notification.Builder(this)
            val notif = builder.setContentTitle(song.title).setContentText(song.artist + " • BEFOOG PLAYER").setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(openIntent).setOngoing(isPlaying).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2)).addAction(android.R.drawable.ic_media_previous, "Prev", pi("BEFOOG_PREV", 1)).addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pause" else "Play", pi("BEFOOG_PLAY", 2)).addAction(android.R.drawable.ic_media_next, "Next", pi("BEFOOG_NEXT", 3)).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif)
        } catch (e: Exception) {}
    }
    private fun makeHomeItem(emoji: String, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(8, 8, 8, 8); isClickable = true; isFocusable = true }
        box.addView(TextView(this).apply { text = emoji; textSize = 36f; gravity = Gravity.CENTER })
        box.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply { text = sub; textSize = 11f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER })
        box.setOnClickListener { onClick() }
        return box
    }
    private fun showHome() { homeScroll.visibility = View.VISIBLE; befoog2View.visibility = View.GONE; listView.visibility = View.GONE; playerView.visibility = View.GONE }
    private fun showList() { homeScroll.visibility = View.GONE; befoog2View.visibility = View.GONE; listView.visibility = View.VISIBLE; playerView.visibility = View.GONE }
    private fun showPlayer() { homeScroll.visibility = View.GONE; befoog2View.visibility = View.GONE; listView.visibility = View.GONE; playerView.visibility = View.VISIBLE }
    private fun openFolder(name: String) { currentFolder = name; showCount = 100; etSearch.setText(""); refreshSongs(); showList() }
    private fun getRealFolders(): List<String> { val set = linkedSetOf<String>(); for (s in allSongs) { try { val parts = s.path.split("/"); if (parts.size >= 2) set.add(parts[parts.size - 2]) } catch (e: Exception) {} }; return set.toList().sorted() }
    private fun showAllFolders() {
        val folders = getRealFolders()
        if (folders.isEmpty()) { Toast.makeText(this, "No folders found yet - allow permission Allow all", Toast.LENGTH_SHORT).show(); return }
        val display = mutableListOf("ALL SONGS"); display.addAll(folders.map { "📁 $it" })
        AlertDialog.Builder(this).setTitle("Folders (${folders.size} Folders)").setItems(display.toTypedArray()) { _, w -> when { w == 0 -> { cameFromBefoog2 = false; openFolder("ALL SONGS") }; else -> { cameFromBefoog2 = false; openFolder("FOLDER:" + folders[w - 1]) } } }.show()
    }
    private fun showCreatePlaylist() {
        val input = EditText(this).apply { hint = "e.g. BLUES" }
        AlertDialog.Builder(this).setTitle("Create Playlist Inside BEFOOG 2").setView(input).setPositiveButton("Create") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty() && !playlists.containsKey(n)) { playlists[n] = mutableListOf(); savePlaylists(); refreshBefoog2(); showBefoog2(); Toast.makeText(this, "Created $n inside BEFOOG 2", Toast.LENGTH_SHORT).show() } }.setNegativeButton("Cancel", null).show()
    }
    private fun checkPermission() { val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE; if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(perm), 101) else loadSongs() }
    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) { super.onRequestPermissionsResult(code, perms, res); if (code == 101) loadSongs() }
    private fun loadSongs() {
        allSongs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
        try { contentResolver.query(uri, proj, MediaStore.Audio.Media.DURATION + " > 10000", null, null)?.use { c -> val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA); while (c.moveToNext()) { val id = c.getLong(iId); val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); allSongs.add(Song(id, c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown", c.getString(iD) ?: "", contentUri)) } } } catch (e: Exception) {}
        loadPlaylists(); tvCountSongs.text = "${allSongs.size} Medias"; tvCountFav.text = "${playlists["BEFOOG 1"]?.size ?: 0} Medias"; tvCountFolders.text = "${getRealFolders().size} Folders"; refreshBefoog2(); refreshSongs(); handler.postDelayed({ restoreLastPlayed() }, 800)
    }
    private fun filterSongs(q: String) {
        val base: List<Song> = when { currentFolder == "ALL SONGS" -> allSongs; currentFolder.startsWith("FOLDER:") -> { val fname = currentFolder.removePrefix("FOLDER:"); allSongs.filter { it.path.split("/").let { p -> p.size >= 2 && p[p.size - 2] == fname } } }; else -> playlists[currentFolder] ?: mutableListOf() }
        filteredSongs = if (q.isEmpty()) base.toMutableList() else base.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.toMutableList(); showSongs()
    }
    private fun refreshSongs() { filterSongs(etSearch.text.toString()) }
    private fun showSongs() {
        songContainer.removeAllViews()
        val displayName = if (currentFolder.startsWith("FOLDER:")) currentFolder.removePrefix("FOLDER:") else currentFolder
        tvFolderTitle.text = "📁 $displayName (${filteredSongs.size} songs)"
        if (filteredSongs.isEmpty()) { songContainer.addView(TextView(this).apply { text = "No songs in this folder yet"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 16) }); return }
        val toShow = filteredSongs.take(showCount)
        for (song in toShow) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 12, 8, 12); gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(this).apply { text = "${song.title}\n${song.artist}"; textSize = 15f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnAdd = Button(this).apply { text = "+ BEFOOG" }; btnAdd.setOnClickListener { showAddToPlaylist(song) }
            tv.setOnClickListener { currentIndex = filteredSongs.indexOf(song); playSong(song); showPlayer() }
            tv.setOnLongClickListener { showSongOptions(song); true }
            row.setOnLongClickListener { showSongOptions(song); true }
            row.addView(tv); row.addView(btnAdd); songContainer.addView(row)
        }
        if (filteredSongs.size > showCount) { val more = Button(this).apply { text = "⬇  LOAD MORE (${filteredSongs.size - showCount} remaining)" }; more.setOnClickListener { showCount += 200; showSongs() }; songContainer.addView(more) }
    }
    private fun showAddToPlaylist(song: Song) {
        val names = playlists.keys.filter { it != "BEFOOG 2" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to BEFOOG").setItems(names) { _, which -> val name = names[which]; if (!playlists[name]!!.contains(song)) { playlists[name]!!.add(song); savePlaylists(); refreshBefoog2(); Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show(); tvCountFav.text = "${playlists["BEFOOG 1"]?.size ?: 0} Medias" } }.show()
    }
    private fun toggleFav() {
        if (currentIndex < 0 || filteredSongs.isEmpty()) return
        val song = filteredSongs[currentIndex]; val list = playlists["BEFOOG 1"]!!
        if (list.contains(song)) { list.remove(song); btnFav.text = "♡"; Toast.makeText(this, "Removed from BEFOOG 1", Toast.LENGTH_SHORT).show() } else { list.add(song); btnFav.text = "♥"; Toast.makeText(this, "Added to BEFOOG 1 ♥", Toast.LENGTH_SHORT).show() }
        savePlaylists(); refreshBefoog2(); saveCurrentState()
    }
    private fun playSong(song: Song) {
        try {
            mediaPlayer?.release(); mediaPlayer = MediaPlayer(); mediaPlayer?.setDataSource(this, song.uri); mediaPlayer?.prepare(); mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener { if (repeatMode == 2) playSong(song) else playNext() }
            tvTitle.text = song.title; tvArtist.text = song.artist; tvMiniTitle.text = "${song.title} - ${song.artist}"; btnPlay.text = "⏸"; btnPlayMini.text = "⏸"; seekBar.max = mediaPlayer?.duration ?: 0; tvBigArt.text = "💿"; btnFav.text = if (playlists["BEFOOG 1"]!!.contains(song)) "♥" else "♡"; showMusicNotification(); saveCurrentState()
        } catch (e: Exception) { Toast.makeText(this, "Cannot play this song - try another", Toast.LENGTH_SHORT).show(); try { playNext() } catch (ex: Exception) {} }
    }
    private fun togglePlay() { val mp = mediaPlayer ?: return; try { if (mp.isPlaying) { mp.pause(); btnPlay.text = "▶"; btnPlayMini.text = "▶" } else { mp.start(); btnPlay.text = "⏸"; btnPlayMini.text = "⏸" }; showMusicNotification(); saveCurrentState() } catch (e: Exception) {} }
    private fun playNext() { if (filteredSongs.isEmpty()) return; currentIndex = if (isShuffle) Random.nextInt(filteredSongs.size) else (currentIndex + 1) % filteredSongs.size; playSong(filteredSongs[currentIndex]) }
    private fun playPrev() { if (filteredSongs.isEmpty()) return; currentIndex = if (currentIndex <= 0) filteredSongs.size - 1 else currentIndex - 1; playSong(filteredSongs[currentIndex]) }
    private fun startSleepTimer() {
        val options = arrayOf("Cancel Timer", "5 mins", "10 mins", "15 mins", "30 mins", "60 mins")
        AlertDialog.Builder(this).setTitle("Sleep Timer").setItems(options) { _, which -> val mins = when (which) { 1 -> 5L; 2 -> 10L; 3 -> 15L; 4 -> 30L; 5 -> 60L; else -> 0L }; if (mins == 0L) { btnSleep.text = "💤 Sleep Timer: OFF"; return@setItems }; btnSleep.text = "💤 Sleep Timer: ${mins} min"; handler.postDelayed({ try { mediaPlayer?.pause() } catch (e: Exception) {}; btnPlay.text = "▶"; btnPlayMini.text = "▶"; btnSleep.text = "💤 Sleep Timer: OFF"; showMusicNotification(); saveCurrentState() }, mins * 60 * 1000L); Toast.makeText(this, "Music will stop in $mins mins", Toast.LENGTH_SHORT).show() }.show()
    }
    private fun formatTime(ms: Int): String { val t = ms / 1000; return String.format("%02d:%02d", t / 60, t % 60) }
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (!shakeNextOn && !shakePrevOn) return
        val now = System.currentTimeMillis()
        if (now - lastShakeTime < 800) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val dx = x - lastX
        val dy = y - lastY
        val dz = z - lastZ
        val speed = sqrt(dx * dx + dy * dy + dz * dz)
        lastX = x
        lastY = y
        lastZ = z
        if (speed > 12) {
            lastShakeTime = now
            if (shakeNextOn) playNext() else if (shakePrevOn) playPrev()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
    override fun onResume() { super.onResume(); try { sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL) } catch (e: Exception) {}; try { if (currentIndex >= 0 && mediaPlayer != null && wasPlayingBeforePause) { mediaPlayer?.start(); btnPlay.text = "⏸"; btnPlayMini.text = "⏸" } } catch (e: Exception) {} }
    override fun onPause() { super.onPause(); try { wasPlayingBeforePause = mediaPlayer?.isPlaying == true } catch (e: Exception) { wasPlayingBeforePause = false }; try { sensorManager.unregisterListener(this) } catch (e: Exception) {}; saveCurrentState() }
    override fun onDestroy() {
        super.onDestroy()
        saveCurrentState()
        try { notifReceiver?.let { unregisterReceiver(it) } catch (e: Exception) {}
        try { mediaSession?.release() } catch (e: Exception) {}
        try { mediaPlayer?.release() } catch (e: Exception) {}
        try { handler.removeCallbacks(updateRunnable) } catch (e: Exception) {}
    }
}
