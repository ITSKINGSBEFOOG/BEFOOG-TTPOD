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
    // FIX 2: Shake OFF by default
    private var shakeNextOn = false
    private var shakePrevOn = false
    private var playlists = mutableMapOf<String, MutableList<Song>>()
    private var currentFolder = "ALL SONGS"
    private var showCount = 100
    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var mediaSession: MediaSession? = null
    private var notifReceiver: BroadcastReceiver? = null

    private lateinit var root: LinearLayout
    private lateinit var homeView: LinearLayout
    private lateinit var listView: LinearLayout
    private lateinit var playerView: LinearLayout
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
    private lateinit var playlistHomeContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    seekBar.progress = mp.currentPosition
                    tvTime.text = formatTime(mp.currentPosition)
                    tvTimeLeft.text = formatTime(mp.duration)
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int { return (v * resources.displayMetrics.density).toInt() }

    override fun onBackPressed() {
        try {
            if (playerView.visibility == View.VISIBLE) { showList(); return }
            if (listView.visibility == View.VISIBLE) { showHome(); return }
        } catch (e: Exception) {}
        try { super.onBackPressed() } catch (e: Exception) { finish() }
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
        if (intent?.action?.startsWith("BEFOOG_") == true) intent.action = null
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
        } catch (e: Exception) {}
    }

    private fun loadPlaylists() {
        try {
            val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
            val names = prefs.getStringSet("playlist_names", null) ?: return
            for (name in names) {
                val saved = prefs.getString("playlist_$name", "") ?: ""
                val list = mutableListOf<Song>()
                if (saved.isNotEmpty()) {
                    val paths = saved.split("|||")
                    for (p in paths) {
                        val found = allSongs.find { it.path == p }
                        if (found != null) list.add(found)
                    }
                }
                playlists[name] = list
            }
        } catch (e: Exception) {}
    }

    private fun showPlaylistOptions(name: String) {
        val options = arrayOf("✏️  Rename Playlist", "🗑️  Delete Playlist")
        AlertDialog.Builder(this).setTitle(name).setItems(options) { _, which ->
            if (which == 0) renamePlaylist(name) else deletePlaylist(name)
        }.show()
    }

    private fun renamePlaylist(oldName: String) {
        if (oldName == "BEFOOG 1") {
            Toast.makeText(this, "BEFOOG 1 na Favorites, e no fit rename", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = oldName; setText(oldName) }
        AlertDialog.Builder(this).setTitle("Rename $oldName").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                if (playlists.containsKey(newName)) {
                    Toast.makeText(this, "$newName don already exist", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = playlists.remove(oldName) ?: mutableListOf()
                playlists[newName] = list
                try {
                    val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
                    prefs.edit().remove("playlist_$oldName").apply()
                } catch (e: Exception) {}
                savePlaylists()
                if (currentFolder == oldName) currentFolder = newName
                refreshPlaylistHome()
                refreshSongs()
                Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePlaylist(name: String) {
        if (name == "BEFOOG 1") {
            Toast.makeText(this, "BEFOOG 1 na Favorites, e no fit delete", Toast.LENGTH_SHORT).show()
            return
        }
        if (!playlists.containsKey(name)) return
        AlertDialog.Builder(this).setTitle("Delete $name?")
            .setMessage("Delete playlist $name? Songs inside no go delete, only playlist.")
            .setPositiveButton("Delete") { _, _ ->
                playlists.remove(name)
                try {
                    val prefs = getSharedPreferences("BEFOOG_PREFS", MODE_PRIVATE)
                    prefs.edit().remove("playlist_$name").apply()
                } catch (e: Exception) {}
                savePlaylists()
                refreshPlaylistHome()
                if (currentFolder == name) { showHome() }
                Toast.makeText(this, "Deleted $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeSongFromPlaylist(song: Song, playlistName: String) {
        val list = playlists[playlistName] ?: return
        AlertDialog.Builder(this).setTitle("Remove song?")
            .setMessage("Remove ${song.title} from $playlistName?")
            .setPositiveButton("Remove") { _, _ ->
                list.remove(song)
                savePlaylists()
                refreshPlaylistHome()
                refreshSongs()
                Toast.makeText(this, "Removed ${song.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun shareSong(song: Song) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, song.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_TEXT, "${song.title} - ${song.artist} • Shared from BEFOOG PLAYER")
            }
            startActivity(Intent.createChooser(intent, "Share ${song.title} via BEFOOG"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share this song", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSongOptions(song: Song) {
        val isPlaylist = playlists.containsKey(currentFolder)
        if (isPlaylist) {
            val options = arrayOf("↗️  Share Song", "🗑️  Remove from $currentFolder")
            AlertDialog.Builder(this).setTitle(song.title).setItems(options) { _, which ->
                if (which == 0) shareSong(song) else removeSongFromPlaylist(song, currentFolder)
            }.show()
        } else {
            val options = arrayOf("↗️  Share Song")
            AlertDialog.Builder(this).setTitle(song.title).setItems(options) { _, _ -> shareSong(song) }.show()
        }
    }

    private fun refreshPlaylistHome() {
        try {
            if (!::playlistHomeContainer.isInitialized) return
            playlistHomeContainer.removeAllViews()
            for ((name, list) in playlists) {
                val btn = Button(this).apply {
                    text = "⭐ $name  (${list.size} songs)  ▶"
                    setBackgroundColor(Color.parseColor("#143A3E"))
                    setTextColor(Color.WHITE)
                }
                btn.setOnClickListener { openFolder(name) }
                btn.setOnLongClickListener {
                    showPlaylistOptions(name)
                    true
                }
                playlistHomeContainer.addView(btn)
            }
            if (playlists.isEmpty()) {
                playlistHomeContainer.addView(TextView(this).apply { text = "No playlist yet - Create one"; setTextColor(Color.LTGRAY) })
            }
        } catch (e: Exception) {}
    }

    private fun smallBtn(text: String, size: Float = 16f): Button {
        return Button(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        playlists["BEFOOG 1"] = mutableListOf()
        playlists["BEFOOG 2"] = mutableListOf()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMediaSession()
        setupNotifReceiver()
        createNotifChannel()
        handleNotificationAction(intent)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B2A2E")) }
        setContentView(root)

        // FIX TOP: Come down well well - 80dp
        homeView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(80), dp(16), dp(16)) }
        val topTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val t1 = TextView(this).apply { text = "Library"; textSize = 18f; setTextColor(Color.WHITE); setPadding(20, 16, 20, 16); setBackgroundColor(Color.parseColor("#3A5A5E")) }
        val t2 = TextView(this).apply { text = "Online"; textSize = 18f; setTextColor(Color.LTGRAY); setPadding(20, 16, 20, 16) }
        val t3 = TextView(this).apply { text = "Recommend"; textSize = 18f; setTextColor(Color.LTGRAY); setPadding(20, 16, 20, 16) }
        topTabs.addView(t1); topTabs.addView(t2); topTabs.addView(t3)
        homeView.addView(topTabs)
        homeView.addView(TextView(this).apply { text = "🎵  BEFOOG PLAYER"; textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 4) })
        homeView.addView(TextView(this).apply { text = "Your music, your way - BEFOOG style"; textSize = 13f; setTextColor(Color.LTGRAY) })

        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 24, 0, 8) }
        val itemSongs = makeHomeItem("💿", "My Songs", "0 Medias") { openFolder("ALL SONGS") }
        tvCountSongs = itemSongs.getChildAt(2) as TextView
        grid1.addView(itemSongs)
        val itemFav = makeHomeItem("❤️", "My Favorites", "BEFOOG 1") { openFolder("BEFOOG 1") }
        tvCountFav = itemFav.getChildAt(2) as TextView
        grid1.addView(itemFav)
        val itemFolders = makeHomeItem("📁", "Folders", "0 Folders") { showAllFolders() }
        tvCountFolders = itemFolders.getChildAt(2) as TextView
        grid1.addView(itemFolders)
        grid1.addView(makeHomeItem("📄+", "New Playlist", "Create") { showCreatePlaylist() })
        homeView.addView(grid1)

        val grid2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        grid2.addView(makeHomeItem("⬇️", "Download", "0 Tasks") { Toast.makeText(this, "Download coming soon", Toast.LENGTH_SHORT).show() })
        grid2.addView(makeHomeItem("🎨", "Custom Ho..", "Theme") { Toast.makeText(this, "BEFOOG PLAYER Theme", Toast.LENGTH_SHORT).show() })
        grid2.addView(makeHomeItem("💤", "Sleep Timer", "OFF") { startSleepTimer() })
        grid2.addView(makeHomeItem("📂", "BEFOOG 2", "Playlist 2") { openFolder("BEFOOG 2") })
        homeView.addView(grid2)

        homeView.addView(TextView(this).apply { text = "⭐  MY PLAYLISTS"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 24, 0, 8) })
        playlistHomeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeView.addView(playlistHomeContainer)
        refreshPlaylistHome()

        homeView.addView(TextView(this).apply { text = "🔥  QUICK PLAY"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 24, 0, 8) })
        val quick = Button(this).apply { text = "▶  SHUFFLE PLAY ALL SONGS"; setBackgroundColor(Color.parseColor("#00C8A0")); setTextColor(Color.WHITE) }
        quick.setOnClickListener {
            if (allSongs.isNotEmpty()) {
                isShuffle = true; filteredSongs = allSongs.toMutableList()
                currentIndex = Random.nextInt(filteredSongs.size)
                playSong(filteredSongs[currentIndex]); showPlayer()
            } else Toast.makeText(this, "No songs found yet - check permission Allow all", Toast.LENGTH_SHORT).show()
        }
        homeView.addView(quick)
        homeView.addView(TextView(this).apply { text = "📱 Shake phone = Next song  •  Shake OFF"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0) })

        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(80), dp(16), dp(16)) }
        val backHome = Button(this).apply { text = "⬅  BACK TO LIBRARY" }
        backHome.setOnClickListener { showHome() }
        listView.addView(backHome)
        tvFolderTitle = TextView(this).apply { text = "📁 ALL SONGS"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }
        listView.addView(tvFolderTitle)
        etSearch = EditText(this).apply { hint = "Search inside this folder..."; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
        listView.addView(etSearch)
        val scrollList = ScrollView(this)
        songContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollList.addView(songContainer)
        listView.addView(scrollList)

        playerView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Color.parseColor("#0E3A3E")); setPadding(dp(12), dp(80), dp(12), dp(12)) }
        val playerTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val backBtn = smallBtn("⬅", 16f)
        backBtn.setOnClickListener { showList() }
        val topTitleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, dp(8), 0); minimumWidth = 0 }
        tvTitle = TextView(this).apply { text = "No song playing"; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        tvArtist = TextView(this).apply { text = "BEFOOG"; textSize = 13f; setTextColor(Color.LTGRAY); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        topTitleBox.addView(tvTitle); topTitleBox.addView(tvArtist)
        btnFav = smallBtn("♡", 18f)
        btnFav.setOnClickListener { toggleFav() }
        val btnAddPlayer = smallBtn("+", 18f)
        btnAddPlayer.setOnClickListener {
            if (currentIndex >= 0 && filteredSongs.isNotEmpty()) {
                showAddToPlaylist(filteredSongs[currentIndex])
            } else Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show()
        }
        val btnShare = smallBtn("↗", 16f)
        btnShare.setOnClickListener {
            if (currentIndex >= 0 && filteredSongs.isNotEmpty()) shareSong(filteredSongs[currentIndex]) else Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show()
        }
        playerTop.addView(backBtn); playerTop.addView(topTitleBox); playerTop.addView(btnFav); playerTop.addView(btnAddPlayer); playerTop.addView(btnShare)
        playerView.addView(playerTop)
        tvBigArt = TextView(this).apply { text = "💿"; textSize = 90f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, 60, 0, 60) }
        playerView.addView(tvBigArt)
        playerView.addView(TextView(this).apply { text = "Good Music"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 16) })
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvTime = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tvTimeLeft = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); gravity = Gravity.END }
        timeRow.addView(tvTime); timeRow.addView(tvTimeLeft)
        playerView.addView(timeRow)
        seekBar = SeekBar(this); playerView.addView(seekBar)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 20, 0, 12) }
        btnRepeat = Button(this).apply { text = "↺"; textSize = 22f }
        val btnPrev = Button(this).apply { text = "⏮"; textSize = 26f }
        btnPlay = Button(this).apply { text = "▶"; textSize = 30f }
        val btnNext = Button(this).apply { text = "⏭"; textSize = 26f }
        btnShuffle = Button(this).apply { text = "🔀"; textSize = 22f }
        controls.addView(btnRepeat); controls.addView(btnPrev); controls.addView(btnPlay); controls.addView(btnNext); controls.addView(btnShuffle)
        playerView.addView(controls)
        btnSleep = Button(this).apply { text = "💤 Sleep Timer: OFF" }
        playerView.addView(btnSleep)
        val shakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        // FIX: OFF by default
        val btnShakeNext = Button(this).apply { text = "Shake Next: OFF"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnShakePrev = Button(this).apply { text = "Shake Prev: OFF"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        shakeRow.addView(btnShakeNext); shakeRow.addView(btnShakePrev)
        playerView.addView(shakeRow)
        playerView.addView(TextView(this).apply { text = "BEFOOG 1  •  BEFOOG 2  •  BEFOOG PLAYER"; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0) })

        // FIX BOTTOM: Go UP well well - 70dp bottom + extra spacer
        val mini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK); setPadding(dp(10), dp(14), dp(10), dp(70)); gravity = Gravity.CENTER_VERTICAL }
        tvMiniTitle = TextView(this).apply { text = "No song"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setPadding(0, 0, dp(6), 0) }
        val miniPrev = Button(this).apply { text = "⏮"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        btnPlayMini = Button(this).apply { text = "▶"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        val miniNext = Button(this).apply { text = "⏭"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        val miniFav = Button(this).apply { text = "♡"; setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        mini.addView(tvMiniTitle); mini.addView(miniPrev); mini.addView(btnPlayMini); mini.addView(miniNext); mini.addView(miniFav)
        mini.setOnClickListener { if (currentIndex >= 0) showPlayer() }

        root.addView(homeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(mini)

        btnPlay.setOnClickListener { togglePlay() }
        btnPlayMini.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { playNext() }
        miniNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrev() }
        miniPrev.setOnClickListener { playPrev() }
        miniFav.setOnClickListener { toggleFav() }
        btnShuffle.setOnClickListener { isShuffle = !isShuffle; btnShuffle.text = if (isShuffle) "🔀 ON" else "🔀" }
        btnRepeat.setOnClickListener { repeatMode = (repeatMode + 1) % 3; btnRepeat.text = when (repeatMode) { 1 -> "↺ ALL"; 2 -> "↺ 1"; else -> "↺" } }
        btnShakeNext.setOnClickListener { shakeNextOn = !shakeNextOn; btnShakeNext.text = if (shakeNextOn) "Shake Next: ON" else "Shake Next: OFF" }
        btnShakePrev.setOnClickListener { shakePrevOn = !shakePrevOn; btnShakePrev.text = if (shakePrevOn) "Shake Prev: ON" else "Shake Prev: OFF" }
        btnSleep.setOnClickListener { startSleepTimer() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { showCount = 100; filterSongs(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { try { mediaPlayer?.seekTo(s?.progress ?: 0) } catch (e: Exception) {} }
        })
        checkPermission()
        handler.post(updateRunnable)
    }

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "BEFOOG_PLAYER")
            mediaSession?.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { togglePlay() }
                override fun onPause() { togglePlay() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrev() }
            })
            mediaSession?.isActive = true
        } catch (e: Exception) {}
    }
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("befoog_music", "BEFOOG Music", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
    }
    private fun setupNotifReceiver() {
        notifReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) { ACTION_PREV -> playPrev(); ACTION_NEXT -> playNext(); ACTION_PLAY -> togglePlay() }
            }
        }
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
            mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS).setState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, mediaPlayer?.currentPosition?.toLong() ?: 0, 1f).build())
            fun pi(action: String, code: Int): PendingIntent { return PendingIntent.getActivity(this, code, Intent(this, MainActivity::class.java).apply { this.action = action; flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "befoog_music") else Notification.Builder(this)
            val notif = builder.setContentTitle(song.title).setContentText(song.artist + " • BEFOOG PLAYER").setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(openIntent).setOngoing(isPlaying).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2)).addAction(android.R.drawable.ic_media_previous, "Prev", pi("BEFOOG_PREV", 1)).addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pause" else "Play", pi("BEFOOG_PLAY", 2)).addAction(android.R.drawable.ic_media_next, "Next", pi("BEFOOG_NEXT", 3)).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif)
        } catch (e: Exception) {}
    }

    private fun makeHomeItem(emoji: String, title: String, sub: String, onClick: ()
