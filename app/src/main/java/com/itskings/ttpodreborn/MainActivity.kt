package com.itskings.ttpodreborn

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.*
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
    private var repeatMode = 0 // 0=off, 1=all, 2=one
    private var shakeNextOn = true
    private var shakePrevOn = true
    private var playlists = mutableMapOf<String, MutableList<Song>>()
    private var currentPlaylistName: String? = null

    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f

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
    private lateinit var listView: ListView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var etPlaylistName: EditText
    private lateinit var btnCreatePlaylist: Button
    private lateinit var btnAddToPlaylist: Button
    private lateinit var spinnerPlaylist: Spinner
    private lateinit var btnSleep: Button

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
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(layout)
        setContentView(scroll)

        val tvLogo = TextView(this).apply { text = "🎵 BEFOOG TTPOD"; textSize = 26f; setPadding(0,0,0,16) }
        layout.addView(tvLogo)

        tvTitle = TextView(this).apply { text = "No song playing"; textSize = 20f }
        tvArtist = TextView(this).apply { text = "BEFOOG - Big Papa & Big Mama"; textSize = 14f }
        layout.addView(tvTitle); layout.addView(tvArtist)

        seekBar = SeekBar(this)
        layout.addView(seekBar)
        tvTime = TextView(this).apply { text = "00:00 / 00:00" }
        layout.addView(tvTime)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnPrev = Button(this).apply { text = "⏮ Prev" }
        btnPlay = Button(this).apply { text = "▶ Play" }
        btnNext = Button(this).apply { text = "Next ⏭" }
        row1.addView(btnPrev); row1.addView(btnPlay); row1.addView(btnNext)
        layout.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnShuffle = Button(this).apply { text = "Shuffle: OFF" }
        btnRepeat = Button(this).apply { text = "Repeat: OFF" }
        row2.addView(btnShuffle); row2.addView(btnRepeat)
        layout.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnShakeNext = Button(this).apply { text = "Shake Next: ON" }
        btnShakePrev = Button(this).apply { text = "Shake Prev: ON" }
        row3.addView(btnShakeNext); row3.addView(btnShakePrev)
        layout.addView(row3)

        btnSleep = Button(this).apply { text = "💤 Sleep Timer: OFF" }
        layout.addView(btnSleep)

        layout.addView(TextView(this).apply { text = "🔍 Search All Songs"; textSize = 18f; setPadding(0,24,0,8) })
        etSearch = EditText(this).apply { hint = "Search song / artist..." }
        layout.addView(etSearch)

        listView = ListView(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800) }
        layout.addView(listView)

        layout.addView(TextView(this).apply { text = "📂 Playlists"; textSize = 18f; setPadding(0,24,0,8) })
        etPlaylistName = EditText(this).apply { hint = "New playlist name e.g Big Papa" }
        layout.addView(etPlaylistName)
        btnCreatePlaylist = Button(this).apply { text = "Create Playlist" }
        layout.addView(btnCreatePlaylist)
        spinnerPlaylist = Spinner(this)
        layout.addView(spinnerPlaylist)
        btnAddToPlaylist = Button(this).apply { text = "Add Current Song To Playlist" }
        layout.addView(btnAddToPlaylist)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        btnPlay.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrev() }
        btnShuffle.setOnClickListener { isShuffle = !isShuffle; btnShuffle.text = if(isShuffle) "Shuffle: ON" else "Shuffle: OFF" }
        btnRepeat.setOnClickListener { repeatMode = (repeatMode+1)%3; btnRepeat.text = when(repeatMode){1->"Repeat: ALL";2->"Repeat: ONE";else->"Repeat: OFF"} }
        btnShakeNext.setOnClickListener { shakeNextOn = !shakeNextOn; btnShakeNext.text = if(shakeNextOn) "Shake Next: ON" else "Shake Next: OFF" }
        btnShakePrev.setOnClickListener { shakePrevOn = !shakePrevOn; btnShakePrev.text = if(shakePrevOn) "Shake Prev: ON" else "Shake Prev: OFF" }
        btnSleep.setOnClickListener { startSleepTimer() }
        btnCreatePlaylist.setOnClickListener { createPlaylist() }
        btnAddToPlaylist.setOnClickListener { addCurrentToPlaylist() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filterSongs(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, pos, _ ->
            val song = filteredSongs[pos]
            currentIndex = allSongs.indexOf(song)
            currentPlaylistName = null
            playSong(song)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) mediaPlayer?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        checkPermission()
        handler.post(updateRunnable)
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 100)
        } else loadSongs()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code==100) loadSongs()
    }

    private fun loadSongs() {
        allSongs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(uri, proj, sel, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val title = c.getString(1) ?: "Unknown"
                val artist = c.getString(2) ?: "Unknown"
                val path = c.getString(3) ?: ""
                if (path.isNotEmpty()) allSongs.add(Song(id, title, artist, path))
            }
        }
        filteredSongs.clear(); filteredSongs.addAll(allSongs)
        refreshList()
        if (allSongs.isNotEmpty()) tvTitle.text = "${allSongs.size} songs found - Tap one to play"
    }

    private fun filterSongs(q: String) {
        filteredSongs.clear()
        if (q.isEmpty()) filteredSongs.addAll(allSongs)
        else filteredSongs.addAll(allSongs.filter { it.title.contains(q, true) || it.artist.contains(q, true) })
        refreshList()
    }

    private fun refreshList() {
        val names = filteredSongs.map { "${it.title} - ${it.artist}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    private fun playSong(song: Song) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(song.path))
                prepare()
                start()
                setOnCompletionListener { onSongFinished() }
            }
            tvTitle.text = song.title; tvArtist.text = song.artist
            btnPlay.text = "⏸ Pause"
            seekBar.max = mediaPlayer!!.duration
        } catch (e: Exception) { Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun onSongFinished() {
        if (repeatMode==2 && currentIndex>=0) { playSong(allSongs[currentIndex]); return }
        if (repeatMode==1 && currentIndex==allSongs.size-1) { currentIndex=0; playSong(allSongs[0]); return }
        playNext()
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) { mp.pause(); btnPlay.text="▶ Play" } else { mp.start(); btnPlay.text="⏸ Pause" }
    }

    private fun playNext() {
        if (allSongs.isEmpty()) return
        currentIndex = if (isShuffle) Random.nextInt(allSongs.size) else (currentIndex+1)%allSongs.size
        playSong(allSongs[currentIndex])
    }

    private fun playPrev() {
        if (allSongs.isEmpty()) return
        currentIndex = if (isShuffle) Random.nextInt(allSongs.size) else if (currentIndex<=0) allSongs.size-1 else currentIndex-1
        playSong(allSongs[currentIndex])
    }

    private fun createPlaylist() {
        val name = etPlaylistName.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this,"Type playlist name",Toast.LENGTH_SHORT).show(); return }
        if (!playlists.containsKey(name)) playlists[name]=mutableListOf()
        refreshSpinner()
        etPlaylistName.setText("")
        Toast.makeText(this,"Playlist '$name' created - Big Papa / Big Mama",Toast.LENGTH_SHORT).show()
    }

    private fun refreshSpinner() {
        val list = playlists.keys.toList()
        spinnerPlaylist.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if(list.isEmpty()) listOf("No playlist yet") else list)
    }

    private fun addCurrentToPlaylist() {
        if (currentIndex<0) { Toast.makeText(this,"Play a song first",Toast.LENGTH_SHORT).show(); return }
        val name = spinnerPlaylist.selectedItem?.toString() ?: return
        if (!playlists.containsKey(name)) { Toast.makeText(this,"Create playlist first",Toast.LENGTH_SHORT).show(); return }
        playlists[name]?.add(allSongs[currentIndex])
        Toast.makeText(this,"Added to $name",Toast.LENGTH_SHORT).show()
    }

    private fun startSleepTimer() {
        handler.postDelayed({ mediaPlayer?.pause(); btnPlay.text="▶ Play"; btnSleep.text="💤 Sleep Timer: OFF"; Toast.makeText(this,"Sleep timer done",Toast.LENGTH_SHORT).show() }, 15*60*1000)
        btnSleep.text="💤 Sleep Timer: 15 MIN"
        Toast.makeText(this,"Sleep in 15 mins",Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ms: Int): String { val s=ms/1000; return String.format("%02d:%02d", s/60, s%60) }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()
        if (now-lastShakeTime < 800) return
        val dx = e.values[0]-lastX; val dy = e.values[1]-lastY; val dz = e.values[2]-lastZ
        lastX=e.values[0]; lastY=e.values[1]; lastZ=e.values[2]
        val speed = sqrt((dx*dx+dy*dy+dz*dz).toDouble())
        if (speed > 12) {
            lastShakeTime = now
            // Shake right = Next, Shake left = Prev - we use Next if Next ON, else Prev
            if (shakeNextOn) playNext() else if (shakePrevOn) playPrev()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onDestroy() { super.onDestroy(); mediaPlayer?.release(); handler.removeCallbacks(updateRunnable) }
}
