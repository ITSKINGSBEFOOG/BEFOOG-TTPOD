package com.itskings.ttpodreborn

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var player: ExoPlayer
    private lateinit var sensors: SensorManager
    private val songs = mutableListOf<Song>()
    private val playlists = linkedMapOf<String, MutableList<Long>>()
    private var queue = mutableListOf<Song>()
    private var currentIndex = -1
    private var lastShakeAt = 0L
    private var shakeNextOn = true
    private var shakePreviousOn = false
    private var shuffleOn = false
    private var repeatOn = false
    private lateinit var status: TextView
    private lateinit var librarySearch: EditText
    private lateinit var libraryRows: LinearLayout
    private lateinit var playlistArea: LinearLayout
    private lateinit var nextSwitch: Switch
    private lateinit var previousSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        loadPlaylists()
        buildUi()
        requestAudioPermission()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF11101F.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = TextView(this).apply {
            text = "BEFOOG TTPOD"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 8, 0, 4)
        }
        status = TextView(this).apply {
            text = "Your music. Your playlists."
            textSize = 13f
            setTextColor(0xFFB9B7C9.toInt())
            setPadding(0, 0, 0, 10)
        }
        librarySearch = makeSearch("Search all songs")
        val playback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playback.addView(makeButton("⏮") { previous() }, weightParams())
        playback.addView(makeButton("Play / Pause") {
            if (player.isPlaying) player.pause() else player.play()
        }, weightParams(2f))
        playback.addView(makeButton("⏭") { next() }, weightParams())
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(makeButton("Shuffle: OFF") {
            shuffleOn = !shuffleOn
            (it as Button).text = "Shuffle: ${if (shuffleOn) "ON" else "OFF"}"
        }, weightParams())
        modes.addView(makeButton("Repeat: OFF") {
            repeatOn = !repeatOn
            player.repeatMode = if (repeatOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            (it as Button).text = "Repeat: ${if (repeatOn) "ON" else "OFF"}"
        }, weightParams())
        nextSwitch = Switch(this).apply {
            text = "Shake to Next"
            isChecked = shakeNextOn
            setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, checked -> shakeNextOn = checked }
        }
        previousSwitch = Switch(this).apply {
            text = "Shake to Previous"
            isChecked = shakePreviousOn
            setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, checked -> shakePreviousOn = checked }
        }
        val playlistHeader = TextView(this).apply {
            text = "MY PLAYLISTS"
            textSize = 17f
            setTextColor(0xFF00C98D.toInt())
            setPadding(0, 18, 0, 6)
        }
        val create = makeButton("+ Create playlist") { createPlaylist() }
        playlistArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val libraryHeader = TextView(this).apply {
            text = "ALL SONGS"
            textSize = 17f
            setTextColor(0xFF00C98D.toInt())
            setPadding(0, 18, 0, 6)
        }
        libraryRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(heading); content.addView(status); content.addView(librarySearch)
        content.addView(playback); content.addView(modes)
        content.addView(nextSwitch); content.addView(previousSwitch)
        content.addView(playlistHeader); content.addView(create); content.addView(playlistArea)
        content.addView(libraryHeader); content.addView(libraryRows)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        librarySearch.addTextChangedListener(SimpleTextWatcher { renderLibrary() })
        renderPlaylists()
    }

    private fun makeSearch(hintText: String) = EditText(this).apply {
        hint = hintText; singleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAA7B8.toInt())
        setPadding(12, 8, 12, 8)
        setBackgroundColor(0xFF242238.toInt())
    }

    private fun makeButton(label: String, action: (Button) -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action(this) }
    }

    private fun weightParams(weight: Float = 1f) = LinearLayout.LayoutParams(0, -2, weight)

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                         else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanMusic()
        } else ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanMusic()
        else status.text = "Allow music access to scan songs on your phone."
    }

    private fun scanMusic() {
        songs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val filter = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, columns, filter, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                songs.add(Song(id, c.getString(titleCol) ?: "Unknown title",
                    c.getString(artistCol) ?: "Unknown artist", ContentUris.withAppendedId(collection, id)))
            }
        }
        status.text = "${songs.size} songs found"
        renderLibrary(); renderPlaylists()
    }

    private fun renderLibrary() {
        if (!::libraryRows.isInitialized) return
        val q = librarySearch.text.toString().trim()
        val matches = songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        libraryRows.removeAllViews()
        matches.forEach { song ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                text = "${song.title}\n${song.artist}"; setTextColor(0xFFFFFFFF.toInt())
                setPadding(10, 12, 6, 12)
                setOnClickListener { startQueue(matches, song) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(makeButton("＋") { addSongToPlaylist(song) })
            libraryRows.addView(row, ViewGroup.LayoutParams(-1, -2))
        }
        if (matches.isEmpty()) libraryRows.addView(emptyLabel("No matching songs."))
    }

    private fun createPlaylist() {
        val input = EditText(this).apply { hint = "Playlist name"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Create playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !playlists.containsKey(name)) {
                    playlists[name] = mutableListOf(); savePlaylists(); renderPlaylists()
                } else Toast.makeText(this, "Enter a unique playlist name", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addSongToPlaylist(song: Song) {
        if (playlists.isEmpty()) { createPlaylist(); Toast.makeText(this, "Create a playlist first, then tap + again", Toast.LENGTH_LONG).show(); return }
        val names = playlists.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add ${song.title} to...")
            .setItems(names) { _, which ->
                val ids = playlists[names[which]]!!
                if (!ids.contains(song.id)) ids.add(song.id)
                savePlaylists(); renderPlaylists()
            }.show()
    }

    private fun renderPlaylists() {
        if (!::playlistArea.isInitialized) return
        playlistArea.removeAllViews()
        playlists.forEach { (name, ids) ->
            val heading = TextView(this).apply {
                text = "$name  (${ids.size} songs)"; textSize = 15f
                setTextColor(0xFFFFFFFF.toInt()); setPadding(8, 12, 8, 4)
            }
            val search = makeSearch("Search only in $name")
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            section.addView(heading); section.addView(search); section.addView(rows)
            val playlistSongs = { ids.mapNotNull { id -> songs.find { it.id == id } } }
            val render = {
                val query = search.text.toString().trim()
                val filtered = playlistSongs().filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                rows.removeAllViews()
                filtered.forEach { song ->
                    val item = TextView(this).apply {
                        text = "  ${song.title} — ${song.artist}"
                        setTextColor(0xFFCBC9D8.toInt()); setPadding(12, 8, 8, 8)
                        setOnClickListener { startQueue(filtered, song) }
                    }
                    rows.addView(item)
                }
                if (filtered.isEmpty()) rows.addView(emptyLabel("No matching songs in this playlist."))
            }
            search.addTextChangedListener(SimpleTextWatcher { render() })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(makeButton("Play playlist") { val list = playlistSongs(); if (list.isNotEmpty()) startQueue(list, list.first()) })
            actions.addView(makeButton("Delete") {
                AlertDialog.Builder(this).setMessage("Delete playlist '$name'?")
                    .setPositiveButton("Delete") { _, _ -> playlists.remove(name); savePlaylists(); renderPlaylists() }
                    .setNegativeButton("Cancel", null).show()
            })
            section.addView(actions)
            playlistArea.addView(section)
            render()
        }
        if (playlists.isEmpty()) playlistArea.addView(emptyLabel("No playlists yet. Create one to get started."))
    }

    private fun emptyLabel(message: String) = TextView(this).apply {
        text = message; setTextColor(0xFFAAA7B8.toInt()); setPadding(12, 10, 8, 10)
    }

    private fun startQueue(items: List<Song>, selected: Song) {
        queue = items.toMutableList()
        currentIndex = queue.indexOf(selected).coerceAtLeast(0)
        playCurrent()
    }

    private fun playCurrent() {
        val song = queue.getOrNull(currentIndex) ?: return
        player.setMediaItem(MediaItem.fromUri(song.uri)); player.prepare(); player.play()
        status.text = "Playing: ${song.title}"
    }

    private fun next() {
        if (queue.isEmpty()) return
        currentIndex = if (shuffleOn && queue.size > 1) {
            (queue.indices.filter { it != currentIndex }.random())
        } else if (currentIndex < queue.lastIndex) currentIndex + 1 else if (repeatOn) 0 else currentIndex
        playCurrent()
    }

    private fun previous() {
        if (queue.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else if (repeatOn) queue.lastIndex else 0
        playCurrent()
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    override fun onPause() { sensors.unregisterListener(this); super.onPause() }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val force = sqrt(x*x + y*y + z*z)
        val now = System.currentTimeMillis()
        if (force > 23f && now - lastShakeAt > 900) {
            lastShakeAt = now
            // Each switch is independent. If both are enabled, the shake's lateral direction chooses the action.
            when {
                shakeNextOn && shakePreviousOn -> if (x >= 0f) next() else previous()
                shakeNextOn -> next()
                shakePreviousOn -> previous()
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun savePlaylists() {
        val json = JSONObject()
        playlists.forEach { (name, ids) -> json.put(name, JSONArray(ids)) }
        getSharedPreferences("befoog_ttpod", MODE_PRIVATE).edit().putString("playlists", json.toString()).apply()
    }
    private fun loadPlaylists() {
        val raw = getSharedPreferences("befoog_ttpod", MODE_PRIVATE).getString("playlists", null) ?: return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { name ->
                val array = json.getJSONArray(name)
                val ids = mutableListOf<Long>()
                for (i in 0 until array.length()) ids.add(array.getLong(i))
                playlists[name] = ids
            }
        } catch (_: Exception) { playlists.clear() }
    }

    override fun onDestroy() { player.release(); super.onDestroy() }
}
