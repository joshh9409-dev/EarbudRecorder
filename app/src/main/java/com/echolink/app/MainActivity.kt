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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var statusContainer: LinearLayout
    private lateinit var timerText: TextView
    private lateinit var timerCaption: TextView
    private lateinit var liveWave: LiveWaveformView
    private lateinit var recordOrb: RecordOrbView
    private lateinit var savedRecordingsButton: TextView
    private lateinit var libraryContainer: LinearLayout

    private var recording = false
    private var pulse: ObjectAnimator? = null
    private var player: MediaPlayer? = null
    private var activeFile: File? = null
    private var activePlayButton: TextView? = null
    private var activeSeek: SeekBar? = null
    private var activePosition: TextView? = null
    private var exportFile: File? = null
    private var currentScreen = "home"
    private var playbackBoostPercent = 100
    private var backCallback: OnBackInvokedCallback? = null
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
                val total = p.duration.toLong().coerceAtLeast(0L)
                val position = p.currentPosition.toLong().coerceIn(0L, total)
                activeSeek?.max = total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                activeSeek?.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
            val inputLevel = intent.getIntExtra(RecordingService.EXTRA_METER, 0)
            liveWave.setLevel(inputLevel / 100f)
            if (::recordOrb.isInitialized) recordOrb.level = inputLevel / 100f
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
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback {
                when (currentScreen) {
                    "home" -> moveTaskToBack(true)
                    "library", "volume" -> buildUi()
                    else -> buildUi()
                }
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!
            )
        }
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
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(it) }
        }
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        releasePlayer()
        processorExecutor.shutdownNow()
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (currentScreen) {
            "home" -> super.onBackPressed()
            "library", "volume" -> buildUi()
            else -> buildUi()
        }
    }

    private fun buildUi() {
        currentScreen = "home"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), 0)
            setBackgroundColor(0xFF05060C.toInt())
            applySystemInsets(this)
        }

        root.addView(label("ECHOLINK", 29f, 0xFFE7E5EE.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
        }, lp(-1, 52))

        root.addView(label("BLUETOOTH EAR BUD RECORDER", 11f, 0xFFA985C8.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, lp(-1, 32))

        val status = TextView(this).apply {
            tag = "main_status"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFBCA6D2.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF20182F.toInt())
                setStroke(dp(1), 0xFF3A2851.toInt())
            }
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status, lp(-1, 42))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        liveWave = LiveWaveformView(this)
        content.addView(liveWave, lp(-1, 104))

        timerText = label("00:00", 48f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        content.addView(timerText, lp(-1, 58))

        recordOrb = RecordOrbView(this).apply {
            isClickable = true
            setOnClickListener { toggleRecording() }
        }
        content.addView(recordOrb, LinearLayout.LayoutParams(-1, dp(190)))

        timerCaption = label("3D RECORD ORB • READY", 15f, 0xFFA985C8.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        content.addView(timerCaption, lp(-1, 34))

        content.addView(label(
            "No captured audio is played live.",
            10.5f, 0xFF687080.toInt()
        ).apply { gravity = Gravity.CENTER }, lp(-1, 24))

        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF171321.toInt())
                setStroke(dp(1), 0xFF2C253A.toInt())
            }
        }
        nav.addView(navItem("▰", "Library") { showLibraryScreen() }, LinearLayout.LayoutParams(0, dp(76), 1f))
        nav.addView(navItem("◖))", "Volume") { showVolumeScreen() }, LinearLayout.LayoutParams(0, dp(76), 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(86)))

        savedRecordingsButton = TextView(this).apply { visibility = View.GONE }
        setContentView(root)
        refreshStatus()
        refreshLibrary()
    }

    private fun navItem(icon: String, title: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(label(icon, 25f, 0xFFA87BE0.toInt()).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, dp(38)))
        addView(label(title, 12.5f, 0xFFB99ACD.toInt()).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(28)))
    }

    private fun refreshStatus() {
        val enabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        val input = findBluetoothInput()
        val permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val connected = enabled && permission && input != null

        if (::recordOrb.isInitialized) {
            recordOrb.isEnabled = recording || connected
            recordOrb.isRecording = recording
            recordOrb.connectionReady = connected
            recordOrb.alpha = if (recordOrb.isEnabled) 1f else .42f
        }

        val status = findViewById<View>(android.R.id.content).findViewWithTag<TextView>("main_status")
        status?.text = if (connected) "CONNECTED" else "NOT CONNECTED"
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
            timerCaption.text = "3D RECORD ORB • ACTIVE"
            if (::recordOrb.isInitialized) {
                recordOrb.isRecording = true
                recordOrb.connectionReady = true
            }
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(recordOrb, View.ALPHA, 1f, .78f, 1f).apply {
                    duration = 1100
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            timerCaption.text = "3D RECORD ORB • READY"
            if (::recordOrb.isInitialized) recordOrb.isRecording = false
            pulse?.cancel()
            pulse = null
            liveWave.clear()
        }
    }

    private fun showLibraryScreen() {
        currentScreen = "library"
        val root = pageRoot()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(smallButton("‹").apply {
            textSize = 22f
            setOnClickListener { buildUi() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("LIBRARY", 22f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        root.addView(label("Saved recordings • newest first", 11.5f, 0xFF8D7CA0.toInt()), lp(-1, 34))

        val search = EditText(this).apply {
            hint = "Search recordings"
            setHintTextColor(0xFF756A82.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF15131C.toInt())
                setStroke(dp(1), 0xFF30283C.toInt())
            }
        }
        root.addView(search, lp(-1, 52))

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(6))
        }
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(libraryContainer, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav(root, 0)
        setContentView(root)
        refreshLibrary()

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshLibrary(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun showVolumeScreen() {
        currentScreen = "volume"
        val root = pageRoot()
        root.addView(pageTitle("VOLUME BOOST", "Make saved recordings louder"))

        val card = panel().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val value = label(playbackBoostPercent.toString() + "%", 40f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

        card.addView(label("VOLUME BOOST", 12f, 0xFFA985C8.toInt()).apply {
            gravity = Gravity.CENTER
        }, lp(-1, -2))
        card.addView(value, lp(-1, 68))

        val seek = SeekBar(this).apply {
            min = 100
            max = 400
            progress = playbackBoostPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        playbackBoostPercent = progress.coerceIn(100, 400)
                        value.text = playbackBoostPercent.toString() + "%"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(seek, lp(-1, 54))
        card.addView(label(
            "100% = original volume\nUp to 400% boost for quiet recordings.\nBoost is applied to playback only and does not change the original recording.",
            11f, 0xFF81768E.toInt()
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, lp(-1, -2))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(card, lp(-1, -2))
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav(root, 1)
        setContentView(root)
    }

    private fun pageRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), 0)
        setBackgroundColor(0xFF05060C.toInt())
        applySystemInsets(this)
    }

    private fun pageTitle(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("ECHOLINK", 27f, 0xFFE7E5EE.toInt()).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.03f
            }, lp(-1, 52))
            addView(label(title, 12f, 0xFFA985C8.toInt()).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
            }, lp(-1, 34))
            addView(label(subtitle, 10.5f, 0xFF756B82.toInt()).apply {
                gravity = Gravity.CENTER
            }, lp(-1, 34))
        }
    }

    private fun addInfoRow(card: LinearLayout, title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        row.addView(label(title, 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(label(value, 11f, 0xFFA98DC0.toInt()).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        card.addView(row, lp(-1, 52))
    }

    private fun addBottomNav(root: LinearLayout, selected: Int) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF171321.toInt())
                setStroke(dp(1), 0xFF2C253A.toInt())
            }
        }
        val items = listOf(
            Triple("▰", "Library") { showLibraryScreen() },
            Triple("◖))", "Volume") { showVolumeScreen() }
        )
        items.forEachIndexed { index, item ->
            val b = navItem(item.first, item.second, item.third)
            b.alpha = if (index == selected) 1f else .62f
            nav.addView(b, LinearLayout.LayoutParams(0, dp(76), 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(86)))
    }

    private fun refreshLibrary(query: String = "") {
        val files = recordingsDir().listFiles { f -> f.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
            .filter { query.isBlank() || humanFileTitle(it).contains(query, true) || it.name.contains(query, true) }

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
        val card = panel()

        card.addView(label(humanFileTitle(file), 15f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(-1, -2))

        card.addView(label(
            formatSize(file.length()) + "  •  " + duration(file) + "  •  WAV PCM",
            11.5f, 0xFF8EA4B8.toInt()
        ), lp(-1, -2))

        val wave = WaveformView(this)
        card.addView(wave, compactLp(-1, 58))
        processorExecutor.execute {
            wave.load(file)
            runOnUiThread { if (wave.isAttachedToWindow) wave.invalidate() }
        }

        val position = label("00:00 / " + duration(file), 10.5f, 0xFF8195A8.toInt())
        card.addView(position, compactLp(-1, 28))

        val seek = SeekBar(this).apply {
            max = durationMillis(file).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = 0
        }
        card.addView(seek, compactLp(-1, 40))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = smallButton("PLAY")
        val back = smallButton("−10s")
        val fwd = smallButton("+10s")
        val speed = smallButton("1×")
        listOf(play, back, fwd, speed).forEach { transport.addView(it, rowButtonLp()) }
        card.addView(transport, controlRowLp())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val boost = smallButton("VOLUME BOOST")
        val export = smallButton("EXPORT WAV")
        val deleteButton = smallButton("DELETE")
        listOf(boost, export, deleteButton).forEach {
            actions.addView(it, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        card.addView(actions, controlRowLp())

        var rate = 1f

        fun playWithCurrentSettings() {
            if (activeFile == file) releasePlayer()
            startPlayback(file, play, seek, position, rate, playbackBoostPercent / 100f)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && activeFile == file) player?.seekTo(progress)
                if (fromUser && activeFile != file) {
                    position.text = formatMillis(progress.toLong()) + " / " + duration(file)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        play.setOnClickListener {
            if (activeFile == file && player != null) releasePlayer()
            else playWithCurrentSettings()
        }

        back.setOnClickListener {
            if (activeFile == file) {
                player?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
            }
        }

        fwd.setOnClickListener {
            if (activeFile == file) {
                player?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration.coerceAtLeast(0))) }
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
            if (activeFile == file) {
                try { player?.setPlaybackParams(PlaybackParams().setSpeed(rate).setPitch(1f)) } catch (_: Exception) {}
            }
        }

        boost.setOnClickListener { showVolumeScreen() }

        export.setOnClickListener {
            exportFile = file
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension + ".wav")
            }
            startActivityForResult(intent, 400)
        }

        deleteButton.setOnClickListener {
            if (activeFile == file) releasePlayer()
            if (file.delete()) {
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                refreshLibrary()
            } else {
                Toast.makeText(this, "Could not delete recording", Toast.LENGTH_SHORT).show()
            }
        }

        libraryContainer.addView(card, lp(-1, -2))
    }

    private fun startPlayback(
        file: File,
        playButton: TextView,
        seek: SeekBar,
        position: TextView,
        rate: Float,
        boost: Float = 1f
    ) {
        releasePlayer()
        try {
            repairWavIfNeeded(file)
            val source = if (boost > 1.001f) createBoostedFile(file, boost) else file
            val mp = MediaPlayer()

            player = mp
            activeFile = file
            activePlayButton = playButton
            activeSeek = seek
            activePosition = position

            val knownDuration = durationMillis(source).coerceAtLeast(0L)
            seek.max = knownDuration.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seek.progress = 0
            position.text = "00:00 / " + formatMillis(knownDuration)

            mp.setVolume(1.0f, 1.0f)

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared || activeFile != file) {
                    try { prepared.release() } catch (_: Exception) {}
                    return@setOnPreparedListener
                }

                val total = prepared.duration.toLong().coerceAtLeast(knownDuration)
                seek.max = total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                position.text = formatMillis(prepared.currentPosition.toLong()) + " / " + formatMillis(total)

                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        prepared.setPlaybackParams(
                            PlaybackParams()
                                .setSpeed(rate.coerceIn(0.5f, 2f))
                                .setPitch(1f)
                        )
                    }
                } catch (_: Exception) {}

                prepared.start()
                playButton.text = "STOP"
                handler.removeCallbacks(playbackTick)
                handler.post(playbackTick)
            }
            mp.setOnCompletionListener {
                seek.progress = seek.max
                val total = mp.duration.toLong().coerceAtLeast(knownDuration)
                position.text = formatMillis(total) + " / " + formatMillis(total)
                releasePlayer()
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                Toast.makeText(this@MainActivity, "Unable to play this recording.", Toast.LENGTH_SHORT).show()
                true
            }

            mp.setDataSource(source.absolutePath)
            mp.prepareAsync()
            playButton.text = "LOADING"
        } catch (_: Exception) {
            releasePlayer()
            Toast.makeText(this, "Unable to play this recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createBoostedFile(source: File, boost: Float): File {
        val safeBoost = boost.coerceIn(1f, 4f)
        val dir = File(cacheDir, "boosted").apply { mkdirs() }
        val key = (safeBoost * 100).toInt()
        val target = File(dir, source.nameWithoutExtension + "_boost_v2_" + key + ".wav")

        /*
         * Playback-only loudness processing.
         *
         * First normalize the recording to a safe peak, then apply the user's
         * requested boost. This makes the slider useful even when the source
         * recording has a lot of headroom. A final peak ceiling prevents PCM
         * clipping. The original recording is never changed.
         */
        RandomAccessFile(source, "r").use { input ->
            val payload = (input.length() - 44L).coerceAtLeast(0L)
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0)

                input.seek(44L)
                val sourceData = ByteArray(
                    payload.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
                var offset = 0
                while (offset < sourceData.size) {
                    val n = input.read(sourceData, offset, sourceData.size - offset)
                    if (n <= 0) break
                    offset += n
                }

                var peak = 1
                var i = 0
                while (i + 1 < offset) {
                    val raw = (sourceData[i].toInt() and 0xFF) or
                        ((sourceData[i + 1].toInt() and 0xFF) shl 8)
                    val sample = if ((raw and 0x8000) != 0) raw - 65536 else raw
                    peak = max(peak, abs(sample))
                    i += 2
                }

                /*
                 * Normalize a quiet recording up to a safe reference peak.
                 * Then apply the selected boost. The final ceiling is kept
                 * slightly below full-scale to leave a little safety margin.
                 */
                // Use a 25% full-scale reference at 100%, then let the
                // slider raise that reference progressively. This gives the
                // user real audible differences between 100% and 400%.
                val referencePeak = 7500f
                val requested = (referencePeak / peak.toFloat()) * safeBoost
                val effectiveScale = minOf(requested, 30000f / peak.toFloat())

                writeWavHeader(out, offset.toLong())

                i = 0
                while (i + 1 < offset) {
                    val raw = (sourceData[i].toInt() and 0xFF) or
                        ((sourceData[i + 1].toInt() and 0xFF) shl 8)
                    val sample = if ((raw and 0x8000) != 0) raw - 65536 else raw
                    val boosted = (sample * effectiveScale)
                        .coerceIn(-30000f, 30000f)
                        .toInt()

                    sourceData[i] = (boosted and 0xFF).toByte()
                    sourceData[i + 1] = (boosted shr 8).toByte()
                    i += 2
                }

                out.write(sourceData, 0, offset)
            }
        }
        return target
    }

    private fun writeWavHeader(r:RandomAccessFile,size:Long){
        val safeSize = size.coerceAtLeast(0L).coerceAtMost(0x7FFFFFFFL)
        r.writeBytes("RIFF")
        r.writeInt(Integer.reverseBytes((36L + safeSize).toInt()))
        r.writeBytes("WAVE")
        r.writeBytes("fmt ")
        r.writeInt(Integer.reverseBytes(16))
        r.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        r.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        r.writeInt(Integer.reverseBytes(16000))
        r.writeInt(Integer.reverseBytes(32000))
        r.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
        r.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        r.writeBytes("data")
        r.writeInt(Integer.reverseBytes(safeSize.toInt()))
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

                // EchoLink recordings use the standard 44-byte PCM WAV layout.
                // Always normalize the RIFF/data sizes to the actual file payload.
                val actual = (file.length() - 44L).coerceAtLeast(0L).coerceAtMost(0x7FFFFFFFL)
                r.seek(4)
                r.writeInt(Integer.reverseBytes((36L + actual).toInt()))
                r.seek(40)
                r.writeInt(Integer.reverseBytes(actual.toInt()))
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

                // The data chunk is 44 bytes into EchoLink's PCM WAV files.
                // Prefer the actual payload size so a stale/broken data-size field
                // cannot make the UI report 00:00.
                val bytes = (r.length() - 44L).coerceAtLeast(0L)
                val frameBytes = (channels * bits / 8).coerceAtLeast(1)

                return if (rate > 0 && bytes > 0L) {
                    bytes * 1000L / (rate.toLong() * frameBytes)
                } else {
                    0L
                }
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

        private fun label(t: String, s: Float, c: Int) = TextView(this).apply {
        text = t
        textSize = s
        setTextColor(c)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
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

    private fun applySystemInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { v, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }

            v.setPadding(
                baseLeft,
                baseTop + topInset,
                baseRight,
                max(baseBottom, bottomInset + dp(4))
            )
            insets
        }
        root.post { root.requestApplyInsets() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    class RecordOrbView(c: Context) : View(c) {
        var isRecording = false
            set(value) { field = value; invalidate() }
        var connectionReady = false
            set(value) { field = value; invalidate() }
        var level = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        private var phase = 0f
        private val tick = object : Runnable {
            override fun run() {
                phase += if (isRecording) 0.075f else 0.025f
                invalidate()
                postDelayed(this, 32L)
            }
        }

        init { post(tick) }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(tick)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height * .52f
            val base = minOf(width, height) * .30f
            val wave = (kotlin.math.sin(phase.toDouble()).toFloat() + 1f) * .5f
            val accent = if (isRecording) 0xFFFF63C7.toInt() else 0xFFA96BFF.toInt()
            val cyan = 0xFF48E5FF.toInt()

            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            for (i in 0..7) {
                ring.strokeWidth = if (i == 0) 5f else 2f
                ring.color = if (i % 2 == 0) accent else cyan
                ring.alpha = (190 - i * 20).coerceAtLeast(35)
                val r = base * (1.05f + i * .055f) + wave * 3f
                canvas.drawCircle(cx, cy + 5f, r, ring)
            }

            val glow = Paint(Paint.ANTI_ALIAS_FLAG)
            glow.shader = RadialGradient(cx, cy, base * 1.45f,
                intArrayOf(0xFF6B3A74.toInt(), accent, 0xFF17101F.toInt(), 0x00000000),
                floatArrayOf(0f, .35f, .72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, base * 1.45f, glow)

            val body = Paint(Paint.ANTI_ALIAS_FLAG)
            body.shader = RadialGradient(cx - base * .28f, cy - base * .4f, base * 1.3f,
                intArrayOf(0xFF9B99A5.toInt(), 0xFF302D3A.toInt(), 0xFF07070B.toInt()),
                floatArrayOf(0f, .36f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, base, body)

            val floor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = base * .16f
                color = accent
                alpha = 225
            }
            canvas.drawArc(RectF(cx - base * 1.25f, cy + base * .15f,
                cx + base * 1.25f, cy + base * .78f), 195f, 150f, false, floor)

            val mic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (connectionReady) 0xFFD7B8FF.toInt() else 0xFF756A80.toInt()
                style = Paint.Style.STROKE
                strokeWidth = base * .105f
                strokeCap = Paint.Cap.ROUND
            }
            val mr = base * .27f
            canvas.drawRoundRect(RectF(cx - mr, cy - mr * 1.35f, cx + mr, cy + mr * .55f), mr, mr, mic)
            canvas.drawArc(RectF(cx - mr * 1.45f, cy - mr * .2f, cx + mr * 1.45f, cy + mr * 1.65f), 0f, 180f, false, mic)
            canvas.drawLine(cx, cy + mr * 1.65f, cx, cy + mr * 2.15f, mic)
            canvas.drawLine(cx - mr * .75f, cy + mr * 2.15f, cx + mr * .75f, cy + mr * 2.15f, mic)

            if (isRecording) {
                val bars = 17
                val barsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = cyan
                    strokeWidth = 4f
                    strokeCap = Paint.Cap.ROUND
                }
                for (i in 0 until bars) {
                    val a = (i - bars / 2f) / (bars / 2f)
                    val h = base * .28f * (1f - kotlin.math.abs(a)) * (0.35f + level * 1.4f)
                    val x = cx + a * base * 1.05f
                    canvas.drawLine(x, cy + base * .95f, x, cy + base * .95f - h, barsPaint)
                }
            }
        }
    }

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
            if (width <= 0) return
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(0xFFB65CFF.toInt(), 0xFF7C87D9.toInt(), 0xFF4BE4F0.toInt()),
                null, Shader.TileMode.CLAMP
            )
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