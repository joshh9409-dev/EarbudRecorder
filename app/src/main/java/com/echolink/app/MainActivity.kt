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
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var statusContainer: LinearLayout
    private lateinit var recordButton: TextView
    private lateinit var timerText: TextView
    private lateinit var timerCaption: TextView
    private lateinit var meter: ProgressBar
    private lateinit var liveWave: LiveWaveformView
    private lateinit var libraryContainer: LinearLayout
    private lateinit var sensitivityLabel: TextView

    private var recording = false
    private var pulse: ObjectAnimator? = null
    private var player: MediaPlayer? = null
    private var activeFile: File? = null
    private var activePlayButton: TextView? = null
    private var activeSeek: SeekBar? = null
    private var activePosition: TextView? = null
    private var exportFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            if (!recording) refreshLibrary()
            handler.postDelayed(this, 1800)
        }
    }
    private val playbackTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                activeSeek?.max = p.duration.coerceAtLeast(1)
                activeSeek?.progress = p.currentPosition.coerceIn(0, p.duration.coerceAtLeast(1))
                activePosition?.text = formatMillis(p.currentPosition) + " / " + formatMillis(p.duration)
                handler.postDelayed(this, 250)
            } else if (p != null) {
                activeSeek?.max = p.duration.coerceAtLeast(1)
                activeSeek?.progress = p.currentPosition.coerceIn(0, p.duration.coerceAtLeast(1))
                activePosition?.text = formatMillis(p.currentPosition) + " / " + formatMillis(p.duration)
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = 0
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
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 8, 16))
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
            setBackgroundColor(Color.rgb(5, 8, 16))
        }
        scroll.addView(root)

        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(dp(18) + bars.left, dp(20) + bars.top, dp(18) + bars.right, dp(30) + bars.bottom)
            insets
        }

        root.addView(label("ECHOLINK", 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }, lp(-1, 48))

        root.addView(label("BLUETOOTH EAR BUD RECORDER", 12f, 0xFF82A1B8.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }, lp(-1, 28))

        val statusPanel = panel()
        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusPanel.addView(statusContainer, lp(-1, -2))
        root.addView(statusPanel, lp(-1, -2))

        timerCaption = label("READY", 11f, 0xFF7890A5.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(timerCaption, lp(-1, 30))

        timerText = label("00:00", 34f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        root.addView(timerText, lp(-1, 48))

        recordButton = TextView(this).apply {
            text = "●  START RECORDING"
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = buttonBackground(false)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleRecording() }
        }
        root.addView(recordButton, lp(-1, 66))

        root.addView(label("INPUT MONITOR", 11f, 0xFF7890A5.toInt()).apply {
            setPadding(dp(4), dp(16), dp(4), dp(5))
            letterSpacing = 0.12f
        })
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(meter, lp(-1, 16))

        liveWave = LiveWaveformView(this)
        root.addView(liveWave, lp(-1, 54))

        val monitorNote = label(
            "Signal visualization is active only while EchoLink is recording. No captured audio is played live.",
            11.5f, 0xFF71849A.toInt()
        )
        monitorNote.setPadding(dp(4), 0, dp(4), dp(6))
        root.addView(monitorNote, lp(-1, -2))

        root.addView(label("RECORDING SENSITIVITY", 11f, 0xFF7890A5.toInt()).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
            letterSpacing = 0.12f
        })
        sensitivityLabel = label("NORMAL", 14f, 0xFFBDEBFF.toInt())
        sensitivityLabel.setPadding(dp(4), 0, dp(4), 0)
        root.addView(sensitivityLabel, lp(-1, 30))

        val sensitivity = SeekBar(this).apply {
            max = 100
            progress = getPreferences(MODE_PRIVATE).getInt("sensitivity", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sensitivityLabel.text = sensitivityName(p)
                    if (fromUser) getPreferences(MODE_PRIVATE).edit().putInt("sensitivity", p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sensitivityLabel.text = sensitivityName(sensitivity.progress)
        root.addView(sensitivity, lp(-1, 42))

        val behaviour = panel()
        behaviour.addView(label(
            "RECORDING BEHAVIOUR",
            11f, 0xFF78A8C4.toInt()
        ).apply { letterSpacing = 0.12f }, lp(-1, 24))
        behaviour.addView(label(
            "Bluetooth microphone only • no phone-mic fallback • automatic reconnect attempts • Android recording indicator stays visible • recordings stay inside EchoLink until exported or deleted.",
            11.5f, 0xFF8799AB.toInt()
        ), lp(-1, -2))
        root.addView(behaviour, lp(-1, -2))

        root.addView(label("RECORDING LIBRARY", 20f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, dp(14), 2, dp(5))
        }, lp(-1, 48))

        libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(libraryContainer, lp(-1, -2))

        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun refreshStatus() {
        if (!::statusContainer.isInitialized) return

        val enabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        val input = findBluetoothInput()
        val micPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val free = recordingsDir().usableSpace
        val deviceName = input?.let { friendlyDeviceName(it) } ?: "Not detected"
        val route = if (input != null) "Bluetooth input" else "Waiting for earbud"
        val inputInfo = input?.let { technicalInputInfo(it) } ?: "No Bluetooth input exposed"
        val ready = enabled && micPermission && input != null

        statusContainer.removeAllViews()
        statusContainer.addView(statusRow("BLUETOOTH", if (enabled) "CONNECTED" else "OFFLINE", enabled))
        statusContainer.addView(statusRow("EARBUD MIC", deviceName, input != null))
        statusContainer.addView(statusRow("AUDIO ROUTE", route, input != null))
        statusContainer.addView(statusRow("INPUT FORMAT", inputInfo, input != null))
        statusContainer.addView(statusRow("RECORD AUDIO", if (micPermission) "GRANTED" else "PERMISSION NEEDED", micPermission))
        statusContainer.addView(statusRow("STORAGE", formatFreeSpace(free) + " free", free > 5L * 1024 * 1024))
        statusContainer.addView(statusRow("EARBUD BATTERY", "Not exposed by Android", null))
        statusContainer.addView(statusRow("BT SIGNAL", "Not exposed by Android", null))
        statusContainer.addView(statusRow("STATUS", if (ready) "EARBUD MICROPHONE READY" else "CONNECT EARBUD WITH MIC", ready))

        recordButton.isEnabled = recording || ready
        recordButton.alpha = if (recordButton.isEnabled) 1f else 0.42f
    }

    private fun statusRow(title: String, value: String, good: Boolean?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val left = label(title, 10.5f, 0xFF7E94A9.toInt()).apply {
            letterSpacing = 0.06f
        }
        val right = label(value, 12.5f, when (good) {
            true -> 0xFFBDEBFF.toInt()
            false -> 0xFFFF8F9F.toInt()
            null -> 0xFF94A4B3.toInt()
        }.toInt()).apply {
            gravity = Gravity.END
        }
        row.addView(left, LinearLayout.LayoutParams(0, dp(29), 0.36f))
        row.addView(right, LinearLayout.LayoutParams(0, dp(29), 0.64f))
        return row
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
                .putExtra(RecordingService.EXTRA_SENSITIVITY, getPreferences(MODE_PRIVATE).getInt("sensitivity", 0))
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
            recordButton.background = buttonBackground(true)
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
            recordButton.background = buttonBackground(false)
            pulse?.cancel()
            pulse = null
            recordButton.alpha = if (recordButton.isEnabled) 1f else .42f
            meter.progress = 0
            liveWave.clear()
        }
    }

    private fun refreshLibrary() {
        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()
        val files = recordingsDir().listFiles { f -> f.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isEmpty()) {
            libraryContainer.addView(label("No recordings yet. Start a Bluetooth recording and it will appear here.", 13f, 0xFF6E8295.toInt()))
            return
        }

        files.forEach {
            repairWavIfNeeded(it)
            addRecordingCard(it)
        }
    }

    private fun addRecordingCard(file: File) {
        val card = panel()
        val title = humanFileTitle(file)
        card.addView(label(title, 15f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, lp(-1, 34))
        card.addView(label(
            formatSize(file.length()) + "  •  " + duration(file) + "  •  WAV PCM",
            11.5f, 0xFF8EA4B8.toInt()
        ), lp(-1, 25))

        val wave = WaveformView(this)
        wave.load(file)
        card.addView(wave, lp(-1, 60))

        val position = label("00:00 / " + duration(file), 10.5f, 0xFF8195A8.toInt())
        card.addView(position, lp(-1, 24))

        val seek = SeekBar(this).apply {
            max = durationMillis(file).coerceAtLeast(1).toInt()
            progress = 0
        }
        card.addView(seek, lp(-1, 30))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val play = smallButton("PLAY")
        val back = smallButton("−10s")
        val fwd = smallButton("+10s")
        val speed = smallButton("1×")
        listOf(play, back, fwd, speed).forEach { row1.addView(it, weightLp()) }
        card.addView(row1, lp(-1, 44))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val boost = smallButton("BOOST")
        val export = smallButton("EXPORT")
        val del = smallButton("DELETE")
        listOf(boost, export, del).forEach { row2.addView(it, weightLp()) }
        card.addView(row2, lp(-1, 44))

        var rate = 1f
        var boosted = false

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && activeFile == file && player != null) {
                    try { player?.seekTo(progress) } catch (_: Exception) {}
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
            startPlayback(file, play, seek, position, rate, boosted)
        }

        back.setOnClickListener {
            if (activeFile == file) player?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
        }
        fwd.setOnClickListener {
            if (activeFile == file) player?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) }
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
                try { player?.playbackParams = player!!.playbackParams.setSpeed(rate) } catch (_: Exception) {}
            }
        }
        boost.setOnClickListener {
            boosted = !boosted
            boost.text = if (boosted) "BOOST ON" else "BOOST"
            if (activeFile == file) setPlayerVolume(boosted)
        }
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
        boosted: Boolean
    ) {
        releasePlayer()
        try {
            repairWavIfNeeded(file)
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setVolume(if (boosted) 1.45f else 1f, if (boosted) 1.45f else 1f)
                if (Build.VERSION.SDK_INT >= 23) playbackParams = playbackParams.setSpeed(rate)
                setOnCompletionListener {
                    playButton.text = "PLAY"
                    seek.progress = seek.max
                    position.text = formatMillis(durationMillis(file)) + " / " + duration(file)
                    releasePlayer()
                }
                start()
            }
            activeFile = file
            activePlayButton = playButton
            activeSeek = seek
            activePosition = position
            playButton.text = "STOP"
            handler.removeCallbacks(playbackTick)
            handler.post(playbackTick)
        } catch (_: Exception) {
            releasePlayer()
            Toast.makeText(this, "Unable to play this recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setPlayerVolume(boosted: Boolean) {
        try {
            val v = if (boosted) 1.45f else 1f
            player?.setVolume(v, v)
        } catch (_: Exception) {}
    }

    private fun releasePlayer() {
        handler.removeCallbacks(playbackTick)
        try { player?.stop() } catch (_: Exception) {}
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
        return if (base.length == 15 && base.contains("_")) {
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

    private fun buttonBackground(active: Boolean) =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (active) intArrayOf(0xFFE14D70.toInt(), 0xFF76182F.toInt())
            else intArrayOf(0xFF29C3F4.toInt(), 0xFF1056A4.toInt())
        ).apply {
            cornerRadius = dp(34).toFloat()
            setStroke(dp(1), 0xFF7AE9FF.toInt())
        }

    private fun smallButton(t: String) = TextView(this).apply {
        text = t
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFF16263A.toInt())
            setStroke(dp(1), 0xFF223A54.toInt())
        }
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply {
        setMargins(0, dp(4), 0, dp(4))
    }

    private fun weightLp() = LinearLayout.LayoutParams(0, -1, 1f).apply {
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
            val step = width / levels.size.toFloat()
            levels.forEachIndexed { i, v ->
                val h = (v * height * .85f).coerceAtLeast(2f)
                c.drawLine(i * step, mid - h / 2f, i * step, mid + h / 2f, paint)
            }
        }
    }
}
