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
    private var shakeNextOn = true
    private var shakePrevOn = true
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

    // FIXED BACK: Player -> List, List -> Home, Home -> Exit (no crash)
    override fun onBackPressed() {
        try {
            if (playerView.visibility == View.VISIBLE) { showList(); return }
            if (listView.visibility == View.VISIBLE) { showHome(); return }
        } catch (e: Exception) {}
        try { super.onBackPressed() } catch (e: Exception) { finish() }
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

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B2A2E")) }
        setContentView(root)

        homeView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
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
        homeView.addView(TextView(this).apply { text = "📱 Shake phone = Next song  •  Shake ON"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0) })

        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(20, 20, 20, 20) }
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

        playerView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Color.parseColor("#0E3A3E")); setPadding(20, 16, 20, 16) }
        val playerTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // FIXED: Back from Player goes to List, not Home
        val backBtn = Button(this).apply { text = "⬅"; textSize = 16f }
        backBtn.setOnClickListener { showList() }
        val topTitleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tvTitle = TextView(this).apply { text = "No song playing"; textSize = 19f; setTextColor(Color.WHITE) }
        tvArtist = TextView(this).apply { text = "BEFOOG"; textSize = 14f; setTextColor(Color.LTGRAY) }
        topTitleBox.addView(tvTitle); topTitleBox.addView(tvArtist)
        btnFav = Button(this).apply { text = "♡"; textSize = 20f }
        btnFav.setOnClickListener { toggleFav() }
        val btnShare = Button(this).apply { text = "↗"; textSize = 18f }
        btnShare.setOnClickListener { Toast.makeText(this, "Share coming soon", Toast.LENGTH_SHORT).show() }
        playerTop.addView(backBtn); playerTop.addView(topTitleBox); playerTop.addView(btnFav); playerTop.addView(btnShare)
        playerView.addView(playerTop)
        tvBigArt = TextView(this).apply { text = "💿\n\nBEFOOG\nPLAYER"; textSize = 44f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, 80, 0, 80) }
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
        val btnShakeNext = Button(this).apply { text = "Shake Next: ON"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnShakePrev = Button(this).apply { text = "Shake Prev: ON"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        shakeRow.addView(btnShakeNext); shakeRow.addView(btnShakePrev)
        playerView.addView(shakeRow)
        playerView.addView(TextView(this).apply { text = "BEFOOG 1  •  BEFOOG 2  •  BEFOOG PLAYER"; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0) })

        val mini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK); setPadding(12, 12, 12, 12); gravity = Gravity.CENTER_VERTICAL }
        tvMiniTitle = TextView(this).apply { text = "No song"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val miniPrev = Button(this).apply { text = "⏮" }
        btnPlayMini = Button(this).apply { text = "▶" }
        val miniNext = Button(this).apply { text = "⏭" }
        val miniFav = Button(this).apply { text = "♡" }
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
        try { mediaSession = MediaSession(this, "BEFOOG_PLAYER"); mediaSession?.isActive = true } catch (e: Exception) {}
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
            mediaSession?.setPlaybackState(PlaybackState.Builder().setState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, mediaPlayer?.currentPosition?.toLong() ?: 0, 1f).build())
            fun pi(action: String, code: Int): PendingIntent { return PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "befoog_music") else Notification.Builder(this)
            val notif = builder.setContentTitle(song.title).setContentText(song.artist + " • BEFOOG PLAYER").setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(openIntent).setOngoing(isPlaying).setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2)).addAction(android.R.drawable.ic_media_previous, "Prev", pi(ACTION_PREV, 1)).addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pause" else "Play", pi(ACTION_PLAY, 2)).addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT, 3)).build()
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

    private fun showHome() { homeView.visibility = View.VISIBLE; listView.visibility = View.GONE; playerView.visibility = View.GONE }
    private fun showList() { homeView.visibility = View.GONE; listView.visibility = View.VISIBLE; playerView.visibility = View.GONE }
    private fun showPlayer() { homeView.visibility = View.GONE; listView.visibility = View.GONE; playerView.visibility = View.VISIBLE }
    private fun openFolder(name: String) { currentFolder = name; showCount = 100; etSearch.setText(""); refreshSongs(); showList() }

    private fun getRealFolders(): List<String> {
        val set = linkedSetOf<String>()
        for (s in allSongs) { try { val parts = s.path.split("/"); if (parts.size >= 2) set.add(parts[parts.size - 2]) } catch (e: Exception) {} }
        return set.toList().sorted()
    }
    private fun showAllFolders() {
        val folders = getRealFolders()
        if (folders.isEmpty()) { Toast.makeText(this, "No folders found yet - allow permission Allow all", Toast.LENGTH_SHORT).show(); return }
        val display = mutableListOf("ALL SONGS")
        display.addAll(folders.map { "📁 $it" })
        display.addAll(playlists.keys.map { "⭐ $it" })
        AlertDialog.Builder(this).setTitle("Folders (${folders.size} Folders)").setItems(display.toTypedArray()) { _, w ->
            when { w == 0 -> openFolder("ALL SONGS"); w <= folders.size -> openFolder("FOLDER:" + folders[w - 1]); else -> openFolder(display[w].removePrefix("⭐ ")) }
        }.show()
    }

    private fun showCreatePlaylist() {
        val input = EditText(this).apply { hint = "e.g. BEFOOG 3" }
        AlertDialog.Builder(this).setTitle("Create New Playlist").setView(input).setPositiveButton("Create") { _, _ ->
            val n = input.text.toString().trim()
            if (n.isNotEmpty() && !playlists.containsKey(n)) { playlists[n] = mutableListOf(); currentFolder = n; refreshSongs(); showList(); Toast.makeText(this, "Created $n", Toast.LENGTH_SHORT).show() }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(perm), 101) else loadSongs()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) { super.onRequestPermissionsResult(code, perms, res); if (code == 101) loadSongs() }

    private fun loadSongs() {
        allSongs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
        try {
            contentResolver.query(uri, proj, MediaStore.Audio.Media.DURATION + " > 10000", null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    allSongs.add(Song(id, c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown", c.getString(iD) ?: "", contentUri))
                }
            }
        } catch (e: Exception) {}
        tvCountSongs.text = "${allSongs.size} Medias"
        tvCountFav.text = "${playlists["BEFOOG 1"]?.size ?: 0} Medias"
        tvCountFolders.text = "${getRealFolders().size} Folders"
        refreshSongs()
    }

    private fun filterSongs(q: String) {
        val base: List<Song> = when {
            currentFolder == "ALL SONGS" -> allSongs
            currentFolder.startsWith("FOLDER:") -> { val fname = currentFolder.removePrefix("FOLDER:"); allSongs.filter { it.path.split("/").let { p -> p.size >= 2 && p[p.size - 2] == fname } } }
            else -> playlists[currentFolder] ?: mutableListOf()
        }
        filteredSongs = if (q.isEmpty()) base.toMutableList() else base.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.toMutableList()
        showSongs()
    }
    private fun refreshSongs() { filterSongs(etSearch.text.toString()) }

    // FIXED: Fast load - show 100 first, Load More button
    private fun showSongs() {
        songContainer.removeAllViews()
        val displayName = if (currentFolder.startsWith("FOLDER:")) currentFolder.removePrefix("FOLDER:") else currentFolder
        tvFolderTitle.text = "📁 $displayName (${filteredSongs.size} songs)"
        if (filteredSongs.isEmpty()) { songContainer.addView(TextView(this).apply { text = "No songs in this folder yet"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 16) }); return }
        val toShow = filteredSongs.take(showCount)
        for (song in toShow) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 12, 8, 12); gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(this).apply { text = "${song.title}\n${song.artist}"; textSize = 15f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnAdd = Button(this).apply { text = "+ BEFOOG" }
            btnAdd.setOnClickListener { showAddToPlaylist(song) }
            tv.setOnClickListener { currentIndex = filteredSongs.indexOf(song); playSong(song); showPlayer() }
            row.addView(tv); row.addView(btnAdd); songContainer.addView(row)
        }
        if (filteredSongs.size > showCount) {
            val more = Button(this).apply { text = "⬇  LOAD MORE (${filteredSongs.size - showCount} remaining)" }
            more.setOnClickListener { showCount += 200; showSongs() }
            songContainer.addView(more)
        }
    }

    private fun showAddToPlaylist(song: Song) {
        val names = playlists.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to BEFOOG").setItems(names) { _, which ->
            val name = names[which]
            if (!playlists[name]!!.contains(song)) { playlists[name]!!.add(song); Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show(); tvCountFav.text = "${playlists["BEFOOG 1"]?.size ?: 0} Medias" }
        }.show()
    }

    private fun toggleFav() {
        if (currentIndex < 0 || filteredSongs.isEmpty()) return
        val song = filteredSongs[currentIndex]
        val list = playlists["BEFOOG 1"]!!
        if (list.contains(song)) { list.remove(song); btnFav.text = "♡"; Toast.makeText(this, "Removed from BEFOOG 1", Toast.LENGTH_SHORT).show() }
        else { list.add(song); btnFav.text = "♥"; Toast.makeText(this, "Added to BEFOOG 1 ♥", Toast.LENGTH_SHORT).show() }
    }

    private fun playSong(song: Song) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(this, song.uri)
            mediaPlayer?.prepare(); mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener { if (repeatMode == 2) playSong(song) else playNext() }
            tvTitle.text = song.title; tvArtist.text = song.artist; tvMiniTitle.text = "${song.title} - ${song.artist}"
            btnPlay.text = "⏸"; btnPlayMini.text = "⏸"
            seekBar.max = mediaPlayer?.duration ?: 0
            tvBigArt.text = "🎵\n\n${song.title.take(18)}"
            btnFav.text = if (playlists["BEFOOG 1"]!!.contains(song)) "♥" else "♡"
            showMusicNotification()
        } catch (e: Exception) { Toast.makeText(this, "Cannot play this song - try another", Toast.LENGTH_SHORT).show(); try { playNext() } catch (ex: Exception) {} }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) { mp.pause(); btnPlay.text = "▶"; btnPlayMini.text = "▶" } else { mp.start(); btnPlay.text = "⏸"; btnPlayMini.text = "⏸" }
            showMusicNotification()
        } catch (e: Exception) {}
    }
    private fun playNext() { if (filteredSongs.isEmpty()) return; currentIndex = if (isShuffle) Random.nextInt(filteredSongs.size) else (currentIndex + 1) % filteredSongs.size; playSong(filteredSongs[currentIndex]) }
    private fun playPrev() { if (filteredSongs.isEmpty()) return; currentIndex = if (currentIndex <= 0) filteredSongs.size - 1 else currentIndex - 1; playSong(filteredSongs[currentIndex]) }

    private fun startSleepTimer() {
        val options = arrayOf("Cancel Timer", "5 mins", "10 mins", "15 mins", "30 mins", "60 mins")
        AlertDialog.Builder(this).setTitle("Sleep Timer").setItems(options) { _, which ->
            val mins = when (which) { 1 -> 5L; 2 -> 10L; 3 -> 15L; 4 -> 30L; 5 -> 60L; else -> 0L }
            if (mins == 0L) { btnSleep.text = "💤 Sleep Timer: OFF"; return@setItems }
            btnSleep.text = "💤 Sleep Timer: ${mins} min"
            handler.postDelayed({ try { mediaPlayer?.pause() } catch (e: Exception) {}; btnPlay.text = "▶"; btnPlayMini.text = "▶"; btnSleep.text = "💤 Sleep Timer: OFF"; showMusicNotification() }, mins * 60 * 1000L)
            Toast.makeText(this, "Music will stop in $mins mins", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun formatTime(ms: Int): String { val t = ms / 1000; return String.format("%02d:%02d", t / 60, t % 60) }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis(); if (now - lastShakeTime < 800) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val speed = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ))
        lastX = x; lastY = y; lastZ = z
        if (speed > 12) { lastShakeTime = now; if (shakeNextOn) playNext() else if (shakePrevOn) playPrev() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() { super.onResume(); try { sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL) } catch (e: Exception) {} }
    override fun onPause() { super.onPause(); try { sensorManager.unregisterListener(this) } catch (e: Exception) {} }
    override fun onDestroy() {
        super.onDestroy()
        try { notifReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { mediaSession?.release() } catch (e: Exception) {}
        try { mediaPlayer?.release() } catch (e: Exception) {}
        try { handler.removeCallbacks(updateRunnable) } catch (e: Exception) {}
    }
}
