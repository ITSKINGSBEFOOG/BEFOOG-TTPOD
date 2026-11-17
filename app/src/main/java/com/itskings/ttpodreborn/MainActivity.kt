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
    private var lastX=0f; private var lastY=0f; private var lastZ=0f
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
    private val updateRunnable = object: Runnable {
        override fun run() {
            try { val mp=mediaPlayer; if(mp!=null && mp.isPlaying){ seekBar.progress=mp.currentPosition; tvTime.text="${formatTime(mp.currentPosition)} / ${formatTime(mp.duration)}" } } catch(e:Exception){}
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlists["Big Papa"]=mutableListOf(); playlists["Big Mama"]=mutableListOf()
        val scroll=ScrollView(this); val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(28,28,28,28)}; scroll.addView(layout); setContentView(scroll)
        layout.addView(TextView(this).apply{text="🎵 BEFOOG TTPOD"; textSize=26f})
        tvTitle=TextView(this).apply{text="No song playing"; textSize=20f}; tvArtist=TextView(this).apply{text="BEFOOG - Big Papa & Big Mama"; textSize=14f}
        layout.addView(tvTitle); layout.addView(tvArtist)
        seekBar=SeekBar(this); layout.addView(seekBar); tvTime=TextView(this).apply{text="00:00 / 00:00"}; layout.addView(tvTime)
        val r1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; btnPrev=Button(this).apply{text="⏮ PREV"}; btnPlay=Button(this).apply{text="▶ Play"}; btnNext=Button(this).apply{text="NEXT ⏭"}; r1.addView(btnPrev); r1.addView(btnPlay); r1.addView(btnNext); layout.addView(r1)
        val r2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; btnShuffle=Button(this).apply{text="Shuffle: OFF"}; btnRepeat=Button(this).apply{text="Repeat: OFF"}; r2.addView(btnShuffle); r2.addView(btnRepeat); layout.addView(r2)
        val r3=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; btnShakeNext=Button(this).apply{text="Shake Next: ON"}; btnShakePrev=Button(this).apply{text="Shake Prev: ON"}; r3.addView(btnShakeNext); r3.addView(btnShakePrev); layout.addView(r3)
        btnSleep=Button(this).apply{text="💤 Sleep Timer: OFF"}; layout.addView(btnSleep)
        layout.addView(TextView(this).apply{text="📂 FOLDERS - Tap to open"; textSize=18f; setPadding(0,24,0,8)})
        folderRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; layout.addView(folderRow)
        layout.addView(TextView(this).apply{text="🔍 Search Inside This Folder"; textSize=16f; setPadding(0,16,0,4)})
        etSearch=EditText(this).apply{hint="Search inside this folder..."}; layout.addView(etSearch)
        tvFolderTitle=TextView(this).apply{text="📁 ALL SONGS"; textSize=18f; setPadding(0,16,0,8)}; layout.addView(tvFolderTitle)
        songContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; layout.addView(songContainer)
        sensorManager=getSystemService(SENSOR_SERVICE) as SensorManager
        btnPlay.setOnClickListener{togglePlay()}; btnNext.setOnClickListener{playNext()}; btnPrev.setOnClickListener{playPrev()}
        btnShuffle.setOnClickListener{isShuffle=!isShuffle; btnShuffle.text=if(isShuffle)"Shuffle: ON" else "Shuffle: OFF"}
        btnRepeat.setOnClickListener{repeatMode=(repeatMode+1)%3; btnRepeat.text=when(repeatMode){1->"Repeat: ALL";2->"Repeat: ONE";else->"Repeat: OFF"}}
        btnShakeNext.setOnClickListener{shakeNextOn=!shakeNextOn; btnShakeNext.text=if(shakeNextOn)"Shake Next: ON" else "Shake Next: OFF"}
        btnShakePrev.setOnClickListener{shakePrevOn=!shakePrevOn; btnShakePrev.text=if(shakePrevOn)"Shake Prev: ON" else "Shake Prev: OFF"}
        btnSleep.setOnClickListener{startSleepTimer()}
        etSearch.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}; override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){filterSongs(s.toString())}; override fun afterTextChanged(s:Editable?){}})
        refreshFolders(); checkPermission(); handler.post(updateRunnable)
    }
    private fun refreshFolders(){
        folderRow.removeAllViews()
        val names=mutableListOf("ALL SONGS"); names.addAll(playlists.keys)
        for(name in names){
            val b=Button(this).apply{text=if(name==currentFolder)"✅ $name" else name; textSize=12f}
            b.setOnClickListener{currentFolder=name; etSearch.setText(""); refreshFolders(); refreshSongs()}
            folderRow.addView(b)
        }
        val btnNew=Button(this).apply{text="+ NEW"; textSize=12f}
        btnNew.setOnClickListener{showCreatePlaylist()}
        folderRow.addView(btnNew)
    }
    private fun showCreatePlaylist(){
        val input=EditText(this).apply{hint="e.g. Big Papa 2"}
        AlertDialog.Builder(this).setTitle("Create New Folder / Playlist").setView(input).setPositiveButton("Create"){_,_->val n=input.text.toString().trim(); if(n.isNotEmpty() && !playlists.containsKey(n)){playlists[n]=mutableListOf(); currentFolder=n; refreshFolders(); refreshSongs(); Toast.makeText(this,"Created $n",Toast.LENGTH_SHORT).show()}}.setNegativeButton("Cancel",null).show()
    }
    private fun checkPermission(){val perm=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE; if(ContextCompat.checkSelfPermission(this,perm)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(perm),100) else loadSongs()}
    override fun onRequestPermissionsResult(c:Int,p:Array<out String>,r:IntArray){super.onRequestPermissionsResult(c,p,r); if(c==100) loadSongs()}
    private fun loadSongs(){allSongs.clear(); val uri=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; val proj=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.DATA); contentResolver.query(uri,proj,"${MediaStore.Audio.Media.IS_MUSIC} != 0",null,"${MediaStore.Audio.Media.TITLE} ASC")?.use{cur->while(cur.moveToNext()){val path=cur.getString(3)?:""; if(path.isNotEmpty()) allSongs.add(Song(cur.getLong(0),cur.getString(1)?:"Unknown",cur.getString(2)?:"Unknown",path))}}; filteredSongs.clear(); filteredSongs.addAll(allSongs); refreshSongs(); if(allSongs.isNotEmpty()) tvTitle.text="${allSongs.size} songs found - Tap one"}
    private fun filterSongs(q:String){
        if(currentFolder=="ALL SONGS"){filteredSongs.clear(); if(q.isEmpty()) filteredSongs.addAll(allSongs) else filteredSongs.addAll(allSongs.filter{it.title.contains(q,true)||it.artist.contains(q,true)}); refreshSongs()}
        else{refreshSongs(q)}
    }
    private fun refreshSongs(search:String=""){
        songContainer.removeAllViews()
        val list=if(currentFolder=="ALL SONGS") filteredSongs else {val base=playlists[currentFolder]?:mutableListOf(); if(search.isEmpty()) base else base.filter{it.title.contains(search,true)||it.artist.contains(search,true)}}
        tvFolderTitle.text="📁 $currentFolder (${list.size} songs)"
        if(list.isEmpty()){songContainer.addView(TextView(this).apply{text=if(currentFolder=="ALL SONGS")"No songs found" else "Empty folder - Tap + on any song in ALL SONGS to add here"; setPadding(0,16,0,16)}); return}
        for(song in list){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL; setPadding(8,12,8,12)}
            val tv=TextView(this).apply{text="${song.title} - ${song.artist}"; textSize=15f; layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)}
            tv.setOnClickListener{currentIndex=allSongs.indexOf(song); playSong(song)}
            val btnPlus=Button(this).apply{text="+"; textSize=16f}
            btnPlus.setOnClickListener{showAddToPlaylist(song)}
            row.addView(tv); row.addView(btnPlus); songContainer.addView(row)
            songContainer.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,2); setBackgroundColor(0xFFDDDDDD.toInt())})
        }
    }
    private fun showAddToPlaylist(song:Song){
        if(playlists.isEmpty()){Toast.makeText(this,"Create playlist first",Toast.LENGTH_SHORT).show(); return}
        val names=playlists.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add '${song.title}' to:").setItems(names){_,i->val n=names[i]; if(!playlists[n]!!.contains(song)){playlists[n]!!.add(song); Toast.makeText(this,"Added to $n ✅",Toast.LENGTH_SHORT).show(); if(currentFolder==n) refreshSongs()} else Toast.makeText(this,"Already in $n",Toast.LENGTH_SHORT).show()}.show()
    }
    private fun playSong(song:Song){try{mediaPlayer?.release(); mediaPlayer=MediaPlayer().apply{setDataSource(this@MainActivity,Uri.parse(song.path)); prepare(); start(); setOnCompletionListener{onSongFinished()}}; tvTitle.text=song.title; tvArtist.text=song.artist; btnPlay.text="⏸ Pause"; seekBar.max=mediaPlayer!!.duration}catch(e:Exception){Toast.makeText(this,"Cannot play: ${e.message}",Toast.LENGTH_SHORT).show()}}
    private fun onSongFinished(){if(repeatMode==2 && currentIndex>=0){playSong(allSongs[currentIndex]); return}; if(repeatMode==1 && currentIndex==allSongs.size-1){currentIndex=0; playSong(allSongs[0]); return}; playNext()}
    private fun togglePlay(){val mp=mediaPlayer?:return; if(mp.isPlaying){mp.pause(); btnPlay.text="▶ Play"} else {mp.start(); btnPlay.text="⏸ Pause"}}
    private fun playNext(){if(allSongs.isEmpty())return; currentIndex=if(isShuffle)Random.nextInt(allSongs.size) else (currentIndex+1)%allSongs.size; playSong(allSongs[currentIndex])}
    private fun playPrev(){if(allSongs.isEmpty())return; currentIndex=if(isShuffle)Random.nextInt(allSongs.size) else if(currentIndex<=0) allSongs.size-1 else currentIndex-1; playSong(allSongs[currentIndex])}
    private fun startSleepTimer(){handler.postDelayed({mediaPlayer?.pause(); btnPlay.text="▶ Play"; btn
