package com.itskings.ttpodreborn

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt
import kotlin.random.Random

data class Song(val id: Long, val title: String, val artist: String, val path: String)

class MainActivity : AppCompatActivity(), SensorEventListener {
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
    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnNext: Button
    private lateinit var btnPrev: Button
    private lateinit var btnShuffle: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnShakeNext: Button
    private lateinit var btnShakePrev: Button
    private lateinit var etSearch: EditText
    private lateinit var songContainer: LinearLayout
    private lateinit var folderRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var btnSleep: Button
    private lateinit var tvFolderTitle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    seekBar.progress = mp.currentPosition
                    tvTime.text = "${formatTime(mp.currentPosition)} / ${formatTime(mp.duration)}"
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlists["Big Papa"] = mutableListOf()
        playlists["Big Mama"] = mutableListOf()
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        scroll.addView(layout)
        setContentView(scroll)
        layout.addView(TextView(this).apply { text = "🎵 BEFOOG TTPOD"; textSize = 26f })
        tvTitle = TextView(this).apply { text = "No song playing"; textSize = 20f }
        tvArtist = TextView(this).apply { text = "BEFOOG - Big Papa & Big Mama"; textSize = 14f }
        layout.addView(tvTitle); layout.addView(tvArtist)
        seekBar = SeekBar(this); layout.addView(seekBar)
        tvTime = TextView(this).apply { text = "00:00 / 00:00" }; layout.addView(tvTime)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnPrev = Button(this).apply { text = "⏮ PREV" }
        btnPlay = Button(this).apply { text = "▶ Play" }
        btnNext = Button(this).apply { text = "NEXT ⏭" }
        r1.addView(btnPrev); r1.addView(btnPlay); r1.addView(btnNext); layout.addView(r1)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnShuffle = Button(this).apply { text = "Shuffle: OFF" }
        btnRepeat = Button(this).apply { text = "Repeat: OFF" }
        r2.addView(btnShuffle); r2.addView(btnRepeat); layout.addView(r2)
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnShakeNext = Button(this).apply { text = "Shake Next: ON" }
        btnShakePrev = Button(this).apply { text = "Shake Prev: ON" }
        r3.addView(btnShakeNext); r3.addView(btnShakePrev); layout.addView(r3)
        btnSleep = Button(this).apply { text = "💤 Sleep Timer: OFF" }; layout.addView(btnSleep)
        layout.addView(TextView(this).apply { text = "📂 FOLDERS - Tap to open"; textSize = 18f; setPadding(0, 24, 0, 8) })
        folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; layout.addView(folderRow)
        layout.addView(TextView(this).apply { text = "🔍 Search Inside This Folder"; textSize = 16f; setPadding(0, 16, 0, 4) })
        etSearch = EditText(this).apply { hint = "Search inside this folder..." }; layout.addView(etSearch)
        tvFolderTitle = TextView(this).apply { text = "📁 ALL SONGS"; textSize = 18f; setPadding(0, 16, 0, 8) }; layout.addView(tvFolderTitle)
        songContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(songContainer)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        btnPlay.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrev() }
        btnShuffle.setOnClickListener { isShuffle = !isShuffle; btnShuffle.text = if (isShuffle) "Shuffle: ON" else "Shuffle: OFF" }
        btnRepeat.setOnClickListener { repeatMode = (repeatMode + 1) % 3; btnRepeat.text = when (repeatMode) { 1 -> "Repeat: ALL"; 2 -> "Repeat: ONE"; else -> "Repeat: OFF" } }
        btnShakeNext.setOnClickListener { shakeNextOn = !shakeNextOn; btnShakeNext.text = if (shakeNextOn) "Shake Next: ON" else "Shake Next: OFF" }
        btnShakePrev.setOnClickListener { shakePrevOn = !shakePrevOn; btnShakePrev.text = if (shakePrevOn) "Shake Prev: ON" else "Shake Prev: OFF" }
        btnSleep.setOnClickListener { startSleepTimer() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filterSongs(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { try { mediaPlayer?.seekTo(s?.progress ?: 0) } catch (e: Exception) {} }
        })
        refreshFolders(); checkPermission(); handler.post(updateRunnable)
    }

    private fun refreshFolders() {
        folderRow.removeAllViews()
        val names = mutableListOf("ALL SONGS"); names.addAll(playlists.keys)
        for (name in names) {
            val b = Button(this).apply { text = if (name == currentFolder) "✅ $name" else name; textSize = 12f }
            b.setOnClickListener { currentFolder = name; etSearch.setText(""); refreshFolders(); refreshSongs() }
            folderRow.addView(b)
        }
        val btnNew = Button(this).apply { text = "+ NEW"; textSize = 12f }
        btnNew.setOnClickListener { showCreatePlaylist() }
        folderRow.addView(btnNew)
    }

    private fun showCreatePlaylist() {
        val input = EditText(this).apply { hint = "e.g. Big Papa 2" }
        AlertDialog.Builder(this).setTitle("Create New Folder / Playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && !playlists.containsKey(n)) {
                    playlists[n] = mutableListOf(); currentFolder = n; refreshFolders(); refreshSongs()
                    Toast.makeText(this, "Created $n", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun checkPermission() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
        } else { loadSongs() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 101) loadSongs()
    }

    private fun loadSongs() {
        allSongs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
        try {
            contentResolver.query(uri, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) { allSongs.add(Song(c.getLong(iId), c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown", c.getString(iD) ?: "")) }
            }
        } catch (e: Exception) {}
        refreshSongs()
    }

    private fun filterSongs(q: String) {
        val base = if (currentFolder == "ALL SONGS") allSongs else playlists[currentFolder] ?: mutableListOf()
        filteredSongs = if (q.isEmpty()) base.toMutableList() else base.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.toMutableList()
        showSongs()
    }

    private fun refreshSongs() { filterSongs(etSearch.text.toString()) }

    private fun showSongs() {
        songContainer.removeAllViews()
        tvFolderTitle.text = "📁 $currentFolder (${filteredSongs.size} songs)"
        if (filteredSongs.isEmpty()) {
            songContainer.addView(TextView(this).apply { text = if (currentFolder == "ALL SONGS") "No songs found" else "No songs in this folder yet"; setPadding(0, 16, 0, 16) })
            return
        }
        for (song in filteredSongs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 12) }
            val tv = TextView(this).apply { text = "${song.title}\n${song.artist}"; textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnAdd = Button(this).apply { text = "+"; textSize = 12f }
            btnAdd.setOnClickListener { showAddToPlaylist(song) }
            tv.setOnClickListener { currentIndex = filteredSongs.indexOf(song); playSong(song) }
            row.addView(tv); row.addView(btnAdd); songContainer.addView(row)
        }
    }

    private fun showAddToPlaylist(song: Song) {
        if (playlists.isEmpty()) { Toast.makeText(this, "Create playlist first", Toast.LENGTH_SHORT).show(); return }
        val names = playlists.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names) { _, which ->
            val name = names[which]
            if (!playlists[name]!!.contains(song)) { playlists[name]!!.add(song); Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show() }
        }.show()
    }

    private fun playSong(song: Song) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(song.path)
                prepare()
                start()
                setOnCompletionListener { playNext() }
            }
            tvTitle.text = song.title; tvArtist.text = song.artist
            btnPlay.text = "⏸ Pause"
            seekBar.max = mediaPlayer?.duration ?: 0
        } catch (e: Exception) { Toast.makeText(this, "Cannot play this song", Toast.LENGTH_SHORT).show() }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) { mp.pause(); btnPlay.text = "▶ Play" } else { mp.start(); btnPlay.text = "⏸ Pause" }
    }

    private fun playNext() {
        if (filteredSongs.isEmpty()) return
        currentIndex = if (isShuffle) Random.nextInt(filteredSongs.size) else (currentIndex + 1) % filteredSongs.size
        playSong(filteredSongs[currentIndex])
    }

    private fun playPrev() {
        if (filteredSongs.isEmpty()) return
        currentIndex = if (currentIndex <= 0) filteredSongs.size - 1 else currentIndex - 1
        playSong(filteredSongs[currentIndex])
    }

    private fun startSleepTimer() {
        val options = arrayOf("Cancel Timer", "5 mins", "10 mins", "15 mins", "30 mins", "60 mins")
        AlertDialog.Builder(this).setTitle("Sleep Timer").setItems(options) { _, which ->
            val mins = when (which) { 1 -> 5L; 2 -> 10L; 3 -> 15L; 4 -> 30L; 5 -> 60L; else -> 0L }
            if (mins == 0L) { btnSleep.text = "💤 Sleep Timer: OFF"; Toast.makeText(this, "Timer cancelled", Toast.LENGTH_SHORT).show(); return@setItems }
            btnSleep.text = "💤 Sleep Timer: ${mins} min"
            handler.postDelayed({
                try { mediaPlayer?.pause() } catch (e: Exception) {}
                btnPlay.text = "▶ Play"; btnSleep.text = "💤 Sleep Timer: OFF"
            }, mins * 60 * 1000L)
            Toast.makeText(this, "Music will stop in $mins mins", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun formatTime(ms: Int): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()
        if (now - lastShakeTime < 800) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val speed = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ))
        lastX = x; lastY = y; lastZ = z
        if (speed > 12) {
            lastShakeTime = now
            if (shakeNextOn) playNext() else if (shakePrevOn) playPrev()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        try { sensorManager.unregisterListener(this) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaPlayer?.release() } catch (e: Exception) {}
        handler.removeCallbacks(updateRunnable)
    }
}
