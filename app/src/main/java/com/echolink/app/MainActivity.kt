package com.echolink.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var statusContainer: LinearLayout
    private lateinit var recordButton: TextView
    private lateinit var timerText: TextView
    private lateinit var timerCaption: TextView
    private lateinit var meter: ProgressBar
    private lateinit var liveWave: LiveWaveformView
    private lateinit var savedRecordingsButton: TextView
    private lateinit var libraryContainer: LinearLayout

    private var recording = false
    private var pulse: ObjectAnimator? = null
    private var player: ExoPlayer? = null
    private var activeFile: File? = null
    private var activePlayButton: TextView? = null
    private var activeSeek: SeekBar? = null
    private var activePosition: TextView? = null
    private var exportFile: File? = null
    private val processorExecutor = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1800)
        }
    }
    private val playbackTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val total = p.duration.coerceAtLeast(0L)
                val position = p.currentPosition.coerceIn(0L, total)
                activeSeek?.max = total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                activeSeek?.progress = position.coerceIn(0L, total).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                activePosition?.text = formatMillis(position) + " / " + formatMillis(total)
                if (p.isPlaying) handler.postDelayed(this, 200)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RecordingService.ACTION_STATE) return
            recording = intent.getBooleanExtra(RecordingService.EXTRA_RECORDING, false)
            timerText.text = intent.getStringExtra(RecordingService.EXTRA_TIMER) ?: "00:00"
            meter.progress = intent.getIntExtra(RecordingService.EXTRA_METER, 0)
            liveWave.setLevel(meter.progress / 100f)
            updateRecordingUi()
            if (!recording) {
                refreshStatus()
                refreshLibrary()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshStatus()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 8, 16)
        window.navigationBarColor = Color.rgb(5, 8, 16)
        buildUi()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, handler)
        requestPermissionsIfNeeded()
        refreshStatus()
        refreshLibrary()
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(RecordingService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, f)
        }
        refreshStatus()
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        releasePlayer()
        processorExecutor.shutdownNow()
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(5, 8, 16))
        }
        root.addView(label("ECHOLINK", 28f, Color.WHITE).apply { gravity=Gravity.CENTER; typeface=Typeface.DEFAULT_BOLD }, lp(-1,-2))
        root.addView(label("BLUETOOTH EAR BUD RECORDER",11f,0xFF82A1B8.toInt()).apply {
            gravity=Gravity.CENTER; letterSpacing=0.12f; setPadding(0,0,0,dp(8))
        },lp(-1,-2))

        val statusPanel=panel().apply { setPadding(dp(12),dp(8),dp(12),dp(8)) }
        statusContainer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        statusPanel.addView(statusContainer,lp(-1,-2))
        root.addView(statusPanel,lp(-1,-2))

        timerCaption=label("READY",11f,0xFF7890A5.toInt()).apply { gravity=Gravity.CENTER; letterSpacing=0.16f }
        root.addView(timerCaption,lp(-1,-2))
        timerText=label("00:00",30f,Color.WHITE).apply { gravity=Gravity.CENTER; typeface=Typeface.MONOSPACE }
        root.addView(timerText,lp(-1,-2))

        recordButton=TextView(this).apply {
            text="MIC\nREC"; textSize=17f; gravity=Gravity.CENTER; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); background=recordButtonBackground(false)
            setCompoundDrawablesWithIntrinsicBounds(0,android.R.drawable.ic_btn_speak_now,0,0)
            compoundDrawablePadding=dp(4); isClickable=true; isFocusable=true
            setOnClickListener { toggleRecording() }
        }
        root.addView(recordButton,LinearLayout.LayoutParams(dp(164),dp(164)).apply {
            gravity=Gravity.CENTER_HORIZONTAL; setMargins(0,dp(6),0,dp(10))
        })

        root.addView(label("LIVE MICROPHONE WAVEFORM",11f,0xFF7890A5.toInt()).apply {
            setPadding(dp(4),dp(2),dp(4),dp(4)); letterSpacing=0.12f
        },lp(-1,-2))
        liveWave=LiveWaveformView(this)
        root.addView(liveWave,lp(-1,64))
        root.addView(label("No captured audio is played live.",11f,0xFF71849A.toInt()).apply {
            setPadding(dp(4),0,dp(4),dp(4))
        },lp(-1,-2))

        val behaviour=panel()
        behaviour.addView(label("RECORDING",11f,0xFF78A8C4.toInt()).apply { letterSpacing=0.12f },lp(-1,-2))
        behaviour.addView(label(
            "Bluetooth microphone only • no phone-mic fallback • automatic reconnect • Android recording indicator remains visible.",
            11.5f,0xFF8799AB.toInt()
        ),lp(-1,-2))
        root.addView(behaviour,lp(-1,-2))

        savedRecordingsButton=smallButton("VIEW SAVED RECORDINGS").apply {
            textSize=12f
            visibility=View.GONE
            setOnClickListener { showLibraryScreen() }
        }
        root.addView(savedRecordingsButton,lp(-1,52))

        setContentView(root)
    }

    private fun refreshStatus() {
        if (!::statusContainer.isInitialized) return
        val enabled=BluetoothAdapter.getDefaultAdapter()?.isEnabled==true
        val input=findBluetoothInput()
        val permission=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val connected=enabled && permission && input!=null
        statusContainer.removeAllViews()
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(36) }
        row.addView(label("●",15f,if(connected)0xFF54E6A7.toInt() else 0xFFFF687D.toInt()),LinearLayout.LayoutParams(dp(22),-2))
        row.addView(label(if(connected)"CONNECTED" else "NOT CONNECTED",13f,if(connected)0xFFBDEBFF.toInt() else 0xFFFFA0AD.toInt()).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,0.42f))
        row.addView(label(if(connected)(input?.let{friendlyDeviceName(it)}?:"Bluetooth microphone ready") else "Connect earbuds with a microphone",11f,0xFF7F95AA.toInt()).apply{
            gravity=Gravity.END; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,-2,0.58f))
        statusContainer.addView(row)
        recordButton.isEnabled=recording||connected
        recordButton.alpha=if(recordButton.isEnabled)1f else .42f
    }

    private fun toggleRecording() {
        if (!recording) {
            if (!readyToRecord()) {
                refreshStatus()
                Toast.makeText(this, "Connect Bluetooth earbuds with an available microphone first.", Toast.LENGTH_SHORT).show()
                return
            }
            val i = Intent(this, RecordingService::class.java)
                .setAction(RecordingService.START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.STOP))
        }
    }

    private fun readyToRecord(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true &&
            findBluetoothInput() != null
    }

    private fun updateRecordingUi() {
        if (recording) {
            timerCaption.text = "RECORDING • BLUETOOTH MICROPHONE"
            recordButton.text = "■  STOP & SAVE"
            recordButton.background = recordButtonBackground(true)
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(recordButton, View.ALPHA, 1f, .72f, 1f).apply {
                    duration = 1100
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            timerCaption.text = "READY"
            recordButton.text = "●  START RECORDING"
            recordButton.background = recordButtonBackground(false)
            pulse?.cancel()
            pulse = null
            recordButton.alpha = if (recordButton.isEnabled) 1f else .42f
            meter.progress = 0
            liveWave.clear()
        }
    }

    private fun showLibraryScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(5, 8, 16))
        }

        val back = smallButton("‹  BACK TO RECORDER").apply {
            textSize = 12f
            setOnClickListener {
                buildUi()
                refreshStatus()
                refreshLibrary()
            }
        }
        root.addView(back, lp(-1, 52))
        root.addView(label("SAVED RECORDINGS", 22f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, dp(10), 2, dp(4))
        }, lp(-1, -2))
        root.addView(label("Your recordings are stored on this phone until you delete them.", 12f, 0xFF7D91A5.toInt()), lp(-1, -2))

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
        }
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(libraryContainer, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshLibrary()
    }

    private fun refreshLibrary() {
        val files = recordingsDir().listFiles { f -> f.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (::savedRecordingsButton.isInitialized) {
            savedRecordingsButton.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
            savedRecordingsButton.text = if (files.size == 1) "VIEW SAVED RECORDING" else "VIEW SAVED RECORDINGS (" + files.size + ")"
        }

        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()

        if (files.isEmpty()) {
            libraryContainer.addView(label("No recordings yet. Start a Bluetooth recording and it will appear here.", 13f, 0xFF6E8295.toInt()))
            return
        }

        files.forEach { addRecordingCard(it) }
    }

    private fun addRecordingCard(file: File) {
        val card = panel().apply {
            clipChildren = false
            clipToPadding = false
        }
        val title = humanFileTitle(file)
        card.addView(label(title, 15f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, lp(-1, -2))
        card.addView(label(
            formatSize(file.length()) + "  •  " + duration(file) + "  •  WAV PCM",
            11.5f, 0xFF8EA4B8.toInt()
        ), lp(-1, -2))

        val wave = WaveformView(this)
        card.addView(wave, compactLp(-1,58))
        processorExecutor.execute { wave.load(file); runOnUiThread { if (wave.isAttachedToWindow) wave.invalidate() } }

        val position = label("00:00 / " + duration(file), 10.5f, 0xFF8195A8.toInt()).apply {
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(position, compactLp(-1, 28))

        val seek = SeekBar(this).apply {
            max = durationMillis(file).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = 0
        }
        card.addView(seek, compactLp(-1, 40))

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val play = smallButton("PLAY")
        val back = smallButton("−10s")
        val fwd = smallButton("+10s")
        val speed = smallButton("1×")
        listOf(play, back, fwd, speed).forEach { row1.addView(it, rowButtonLp()) }
        card.addView(row1, controlRowLp())

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val normal=smallButton("NORMAL")
        val boost=smallButton("BOOST")
        val clear=smallButton("CLEAR")
        val export=smallButton("EXPORT")
        val del=smallButton("DELETE")
        listOf(normal,boost,clear,export,del).forEach{row2.addView(it,rowButtonLp())}
        card.addView(row2, controlRowLp())

        var rate = 1f
        var playbackMode = PlaybackMode.NORMAL

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && activeFile == file && player != null) {
                    player?.seekTo(progress.toLong())
                }
                if (fromUser && activeFile != file) position.text = formatMillis(progress.toLong()) + " / " + duration(file)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        play.setOnClickListener {
            if (activeFile == file && player != null) {
                releasePlayer()
                return@setOnClickListener
            }
            startPlayback(file, play, seek, position, rate, playbackMode)
        }

        back.setOnClickListener {
            if (activeFile == file) player?.seekTo((player?.currentPosition?.minus(10000L) ?: 0L).coerceAtLeast(0L))
        }
        fwd.setOnClickListener {
            if (activeFile == file) {
                val p = player
                if (p != null) p.seekTo((p.currentPosition + 10000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
            }
        }
        speed.setOnClickListener {
            rate = when (rate) {
                1f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2f
                else -> 1f
            }
            speed.text = String.format(Locale.UK, "%.2g×", rate)
            if (activeFile == file) player?.setPlaybackSpeed(rate)
        }
        normal.setOnClickListener { playbackMode=PlaybackMode.NORMAL; boost.text="BOOST"; clear.text="CLEAR"; if(activeFile==file) restartProcessedPlayback(file,play,seek,position,rate,playbackMode) }
        boost.setOnClickListener { playbackMode=PlaybackMode.BOOST; boost.text="BOOST ✓"; clear.text="CLEAR"; if(activeFile==file) restartProcessedPlayback(file,play,seek,position,rate,playbackMode) else startPlayback(file,play,seek,position,rate,playbackMode) }
        clear.setOnClickListener { playbackMode=PlaybackMode.CLEAR; clear.text="CLEAR ✓"; boost.text="BOOST"; if(activeFile==file) restartProcessedPlayback(file,play,seek,position,rate,playbackMode) else startPlayback(file,play,seek,position,rate,playbackMode) }
        export.setOnClickListener {
            exportFile = file
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, file.name)
            }
            startActivityForResult(i, 400)
        }
        del.setOnClickListener {
            if (activeFile == file) releasePlayer()
            if (file.delete()) refreshLibrary()
            else Toast.makeText(this, "Could not delete recording", Toast.LENGTH_SHORT).show()
        }

        libraryContainer.addView(card, lp(-1, -2))
    }

    private fun startPlayback(
        file: File,
        playButton: TextView,
        seek: SeekBar,
        position: TextView,
        rate: Float,
        mode: PlaybackMode
    ) {
        releasePlayer()
        try {
            repairWavIfNeeded(file)
            val source=if(mode==PlaybackMode.NORMAL) file else createProcessedFile(file,mode)
            val exo=ExoPlayer.Builder(this).build()
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val total = exo.duration.coerceAtLeast(0L)
                        seek.max = total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        position.text = formatMillis(exo.currentPosition) + " / " + formatMillis(total)
                    } else if (state == Player.STATE_ENDED) {
                        seek.progress = seek.max
                        position.text = formatMillis(exo.duration.coerceAtLeast(0L)) + " / " + formatMillis(exo.duration.coerceAtLeast(0L))
                        releasePlayer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    releasePlayer()
                    Toast.makeText(this@MainActivity, "Unable to play this recording.", Toast.LENGTH_SHORT).show()
                }
            })

            player = exo
            activeFile = file
            activePlayButton = playButton
            activeSeek = seek
            activePosition = position

            exo.setPlaybackSpeed(rate)
            exo.volume=1f
            val mediaItem = MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(source))
                .setMimeType(MimeTypes.AUDIO_WAV)
                .build()
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true

            playButton.text = "STOP"
            handler.removeCallbacks(playbackTick)
            handler.post(playbackTick)
        } catch (_: Exception) {
            releasePlayer()
            Toast.makeText(this, "Unable to play this recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private enum class PlaybackMode { NORMAL, BOOST, CLEAR }
    private fun restartProcessedPlayback(file:File,playButton:TextView,seek:SeekBar,position:TextView,rate:Float,mode:PlaybackMode){
        releasePlayer(); startPlayback(file,playButton,seek,position,rate,mode)
    }
    private fun createProcessedFile(source:File,mode:PlaybackMode):File{
        val dir=File(cacheDir,"processed").apply{mkdirs()}
        val target=File(dir,source.nameWithoutExtension+(if(mode==PlaybackMode.BOOST)"_boost" else "_clear")+".wav")
        if(target.exists()&&target.lastModified()>=source.lastModified())return target
        RandomAccessFile(source,"r").use{input->val size=(input.length()-44L).coerceAtLeast(0L);RandomAccessFile(target,"rw").use{out->
            out.setLength(0); writeWavHeader(out,size); input.seek(44); val b=ByteArray(8192); var prev=0f; var rem=size
            while(rem>0){val n=input.read(b,0,minOf(b.size.toLong(),rem).toInt());if(n<=0)break;var i=0
                while(i+1<n){val raw=(b[i].toInt() and 255) or (b[i+1].toInt() shl 8);val sample=if(raw and 0x8000!=0)raw-65536 else raw;var x=sample/32768f
                    if(mode==PlaybackMode.CLEAR){val hp=x-prev*0.995f;prev=x;x=hp}else{x=kotlin.math.tanh(x*1.8f)/kotlin.math.tanh(1.8f)}
                    val v=(x*32767f).toInt().coerceIn(-32768,32767);b[i]=(v and 255).toByte();b[i+1]=(v shr 8).toByte();i+=2}
                out.write(b,0,n);rem-=n}
        }};return target
    }
    private fun writeWavHeader(r:RandomAccessFile,size:Long){
        r.writeBytes("RIFF");r.writeInt(Integer.reverseBytes((36L+size).coerceAtMost(0x7FFFFFFFL).toInt()))
        r.writeBytes("WAVEfmt ");r.writeInt(Integer.reverseBytes(16));r.writeShort(Integer.reverseBytes(1));r.writeShort(Integer.reverseBytes(1))
        r.writeInt(Integer.reverseBytes(16000));r.writeInt(Integer.reverseBytes(32000));r.writeShort(Integer.reverseBytes(2));r.writeShort(Integer.reverseBytes(16))
        r.writeBytes("data");r.writeInt(Integer.reverseBytes(size.coerceAtMost(0x7FFFFFFFL).toInt()))
    }

    private fun releasePlayer() {
        handler.removeCallbacks(playbackTick)
        try { player?.release() } catch (_: Exception) {}
        player = null
        activePlayButton?.text = "PLAY"
        activeFile = null
        activePlayButton = null
        activeSeek = null
        activePosition = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 400 && resultCode == RESULT_OK && data?.data != null && exportFile != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { out ->
                    exportFile!!.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(this, "Recording exported", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            } finally {
                exportFile = null
            }
        }
    }

    private fun findBluetoothInput(): AudioDeviceInfo? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
    }

    private fun friendlyDeviceName(device: AudioDeviceInfo): String {
        val raw = device.productName?.toString()?.trim().orEmpty()
        return if (raw.isNotEmpty() && !raw.startsWith("android.media.AudioDeviceInfo")) raw
        else if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) "Bluetooth LE headset mic"
        else "Bluetooth headset mic"
    }

    private fun technicalInputInfo(device: AudioDeviceInfo): String {
        val channels = device.channelCounts.maxOrNull()?.let { if (it >= 2) "Stereo capable" else "Mono" } ?: "Mono"
        val rates = device.sampleRates
        val rate = if (rates.isNotEmpty()) rates.maxOrNull() ?: 16000 else 16000
        return String.format(Locale.UK, "%s • up to %d Hz", channels, rate)
    }

    private fun requestPermissionsIfNeeded() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    private fun repairWavIfNeeded(file: File) {
        if (!file.exists() || file.length() <= 44L) return
        try {
            RandomAccessFile(file, "rw").use { r ->
                val riff = ByteArray(4)
                val wave = ByteArray(4)
                r.seek(0)
                r.readFully(riff)
                r.seek(8)
                r.readFully(wave)
                if (String(riff) != "RIFF" || String(wave) != "WAVE") return
                r.seek(40)
                val current = Integer.reverseBytes(r.readInt())
                val actual = (file.length() - 44L).coerceAtLeast(0L)
                if (current <= 0 && actual > 0) {
                    r.seek(4)
                    r.writeInt(Integer.reverseBytes((36L + actual).coerceAtMost(0x7FFFFFFFL).toInt()))
                    r.seek(40)
                    r.writeInt(Integer.reverseBytes(actual.coerceAtMost(0x7FFFFFFFL).toInt()))
                }
            }
        } catch (_: Exception) {}
    }

    private fun durationMillis(file: File): Long {
        try {
            RandomAccessFile(file, "r").use { r ->
                if (r.length() < 44L) return 0L
                r.seek(24)
                val rate = Integer.reverseBytes(r.readInt())
                r.seek(22)
                val channels = java.lang.Short.reverseBytes(r.readShort()).toInt().coerceAtLeast(1)
                r.seek(34)
                val bits = java.lang.Short.reverseBytes(r.readShort()).toInt().coerceAtLeast(8)
                r.seek(40)
                val declared = Integer.reverseBytes(r.readInt()).toLong().coerceAtLeast(0L)
                val bytes = if (declared > 0L && declared <= file.length() - 44L) declared else file.length() - 44L
                val frameBytes = (channels * bits / 8).coerceAtLeast(1)
                return if (rate > 0) bytes * 1000L / (rate.toLong() * frameBytes) else 0L
            }
        } catch (_: Exception) {
            return 0L
        }
    }

    private fun duration(file: File): String = formatMillis(durationMillis(file))

    private fun formatMillis(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.UK, "%02d:%02d", total / 60L, total % 60L)
    }

    private fun humanFileTitle(file: File): String {
        val base = file.name.removePrefix("EchoLink_").removeSuffix(".wav")
        return if ((base.length == 15 || base.length == 19) && base.contains("_")) {
            try {
                val date = base.substring(0, 8)
                val time = base.substring(9, 15)
                String.format(Locale.UK, "%s/%s/%s  •  %s:%s:%s",
                    date.substring(6, 8), date.substring(4, 6), date.substring(0, 4),
                    time.substring(0, 2), time.substring(2, 4), time.substring(4, 6))
            } catch (_: Exception) { base }
        } else base
    }

    private fun recordingsDir() =
        File(getExternalFilesDir("recordings") ?: filesDir, "recordings").apply { mkdirs() }

    private fun formatFreeSpace(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.UK, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> String.format(Locale.UK, "%.0f MB", bytes / 1024.0 / 1024.0)
            else -> String.format(Locale.UK, "%.0f KB", bytes / 1024.0)
        }
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes >= 1024L * 1024L)
            String.format(Locale.UK, "%.1f MB", bytes / 1024.0 / 1024.0)
        else
            String.format(Locale.UK, "%.0f KB", bytes / 1024.0)
    }

    private fun sensitivityName(p: Int) = when {
        p < 20 -> "NORMAL"
        p < 40 -> "HIGH"
        p < 60 -> "VERY HIGH"
        p < 80 -> "EXTREME"
        else -> "EXTREME+"
    }

    private fun label(t: String, s: Float, c: Int) = TextView(this).apply {
        text = t
        textSize = s
        setTextColor(c)
        includeFontPadding = true
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xFF101827.toInt())
            setStroke(dp(1), 0xFF203247.toInt())
        }
    }

    private fun recordButtonBackground(active:Boolean)=GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if(active)intArrayOf(0xFFE14D70.toInt(),0xFF76182F.toInt()) else intArrayOf(0xFF29C3F4.toInt(),0xFF1056A4.toInt())
    ).apply{shape=GradientDrawable.OVAL;setStroke(dp(2),0xFF7AE9FF.toInt())}
    private fun smallButtonBackground()=StateListDrawable().apply{
        addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{cornerRadius=dp(12).toFloat();setColor(0xFF2B6E91.toInt());setStroke(dp(1),0xFF64D8FF.toInt())})
        addState(intArrayOf(),GradientDrawable().apply{cornerRadius=dp(12).toFloat();setColor(0xFF16263A.toInt());setStroke(dp(1),0xFF223A54.toInt())})
    }

    private fun smallButton(t: String) = TextView(this).apply {
        text = t
        minHeight = dp(44)
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = smallButtonBackground()
        isClickable = true
        isFocusable = true
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply {
        setMargins(0, dp(4), 0, dp(4))
    }

    private fun compactLp(w: Int, h: Int) = LinearLayout.LayoutParams(w, dp(h)).apply {
        setMargins(0, dp(2), 0, dp(2))
    }

    private fun controlRowLp() = LinearLayout.LayoutParams(-1, dp(50)).apply {
        setMargins(0, dp(2), 0, dp(2))
    }

    private fun rowButtonLp() = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    class LiveWaveformView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64D8FF.toInt()
            strokeWidth = 4f
        }
        private val levels = FloatArray(72)

        fun setLevel(v: Float) {
            System.arraycopy(levels, 1, levels, 0, levels.size - 1)
            levels[levels.lastIndex] = v.coerceIn(0f, 1f)
            invalidate()
        }

        fun clear() {
            java.util.Arrays.fill(levels, 0f)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val mid = height / 2f
            val step = width / levels.size.toFloat()
            levels.forEachIndexed { i, v ->
                val h = (v * height * .9f).coerceAtLeast(2f)
                c.drawLine(i * step, mid - h / 2f, i * step, mid + h / 2f, paint)
            }
        }
    }

    class WaveformView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF36CFFF.toInt()
            strokeWidth = 3f
        }
        private var levels = FloatArray(0)

        fun load(f: File) {
            try {
                RandomAccessFile(f, "r").use { r ->
                    if (r.length() <= 44L) return
                    val samples = (r.length() - 44L) / 2L
                    val count = 110
                    levels = FloatArray(count)
                    for (i in 0 until count) {
                        val start = i * samples / count
                        val end = max(start + 1L, (i + 1L) * samples / count)
                        r.seek(44L + start * 2L)
                        var peak = 0
                        var n = start
                        while (n < end && r.filePointer + 1L < r.length()) {
                            val s = java.lang.Short.reverseBytes(r.readShort()).toInt()
                            peak = max(peak, abs(s))
                            n++
                        }
                        levels[i] = peak / 32768f
                    }
                }
                invalidate()
            } catch (_: Exception) {}
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val mid = height / 2f
            if (levels.isEmpty()) {
                c.drawLine(0f, mid, width.toFloat(), mid, paint)
                return
            }

            val maxLevel = levels.maxOrNull()?.coerceAtLeast(0.0001f) ?: 0.0001f
            val step = width / levels.size.toFloat()

            levels.forEachIndexed { i, raw ->
                val normalized = (raw / maxLevel).coerceIn(0f, 1f)
                val h = (normalized * height * .78f).coerceAtLeast(3f)
                val x = i * step + step / 2f
                c.drawLine(x, mid - h / 2f, x, mid + h / 2f, paint)
            }
        }
    }
}
