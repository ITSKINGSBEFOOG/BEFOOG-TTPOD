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
            if (befoog2View.visibility == View.VISIBLE) { showHome(); return }
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
        if (oldName == "BEFOOG 1" || oldName == "BEFOOG 2") {
            Toast.makeText(this, "$oldName na system folder, e no fit rename", Toast.LENGTH_SHORT).show()
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
                refreshBefoog2()
                refreshSongs()
                Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePlaylist(name: String) {
        if (name == "BEFOOG 1" || name == "BEFOOG 2") {
            Toast.makeText(this, "$name na system folder, e no fit delete", Toast.LENGTH_SHORT).show()
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
                refreshBefoog2()
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
                refreshBefoog2()
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

    // BEFOOG 2 - All playlists dey inside here only
    private fun refreshBefoog2() {
        try {
            if (!::befoog2Container.isInitialized) return
            befoog2Container.removeAllViews()
            // BEFOOG 1 - Favorites, e dey inside BEFOOG 2 too so Home go free
            val b1 = playlists["BEFOOG 1"] ?: mutableListOf()
            val btn1 = Button(this).apply {
                text = "❤️ BEFOOG 1  (${b1.size} songs)  ▶"
                setBackgroundColor(Color.parseColor("#143A3E"))
                setTextColor(Color.WHITE)
            }
            btn1.setOnClickListener { openFolder("BEFOOG 1") }
            befoog2Container.addView(btn1)
            val others = playlists.keys.filter { it != "BEFOOG 1" && it != "BEFOOG 2" }.sorted()
            if (others.isEmpty()) {
                befoog2Container.addView(TextView(this).apply { text = "\nNo other playlist yet\n\nTap + CREATE to add one"; setTextColor(Color.WHITE); setPadding(0, 20, 0, 20); gravity = Gravity.CENTER })
            }
            for (name in others) {
                val list = playlists[name] ?: mutableListOf()
                val btn = Button(this).apply {
                    text = "⭐ $name  (${list.size} songs)  ▶"
                    setBackgroundColor(Color.parseColor("#143A3E"))
                    setTextColor(Color.WHITE)
                }
                btn.setOnClickListener { openFolder(name) }
                btn.setOnLongClickListener { showPlaylistOptions(name); true }
                befoog2Container.addView(btn)
            }
        } catch (e: Exception) {}
    }

    private fun showBefoog2() {
        refreshBefoog2()
        homeScroll.visibility = View.GONE
        listView.visibility = View.GONE
        playerView.visibility = View.GONE
        befoog2View.visibility = View.VISIBLE
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
        shakeNextOn = false
        shakePrevOn = false
        playlists["BEFOOG 1"] = mutableListOf()
        playlists["BEFOOG 2"] = mutableListOf()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMediaSession()
        setupNotifReceiver()
        createNotifChannel()
        handleNotificationAction(intent)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B2A2E")) }
        setContentView(root)

        homeScroll = ScrollView(this)
        homeView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(100), dp(20), dp(40)) }
        homeScroll.addView(homeView)

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
        grid2.addView(makeHomeItem("📂", "BEFOOG 2", "All Playlists") { showBefoog2() })
        homeView.addView(grid2)

        // HOME FREE - No MY PLAYLISTS here again! All playlists don move to BEFOOG 2
        homeView.addView(TextView(this).apply { text = "🔥  QUICK PLAY"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 32, 0, 8) })
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
        homeView.addView(TextView(this).apply { text = "\n📁 All your playlists dey inside BEFOOG 2\nTap BEFOOG 2 to see them"; textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) })

        befoog2View = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(20), dp(100), dp(20), dp(20)) }
        val backB2 = Button(this).apply { text = "⬅  BACK TO LIBRARY" }
        backB2.setOnClickListener { showHome() }
        befoog2View.addView(backB2)
        befoog2View.addView(TextView(this).apply { text = "📁 BEFOOG 2 - ALL PLAYLISTS"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 4) })
        befoog2View.addView(TextView(this).apply { text = "All your playlists dey inside here"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 12) })
        val btnNewInside = Button(this).apply { text = "+  CREATE NEW PLAYLIST INSIDE BEFOOG 2"; setBackgroundColor(Color.parseColor("#00C8A0")); setTextColor(Color.WHITE
