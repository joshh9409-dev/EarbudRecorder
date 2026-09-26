package com.echolink.app

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class RecordingService : Service() {
    companion object {
        const val START = "com.echolink.START"
        const val STOP = "com.echolink.STOP"
        const val ACTION_STATE = "com.echolink.STATE"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_TIMER = "timer"
        const val EXTRA_METER = "meter"
        const val EXTRA_SENSITIVITY = "sensitivity"
        private const val CHANNEL = "echolink_recording"
        private const val NOTIFICATION_ID = 7
        private const val RATE = 16000
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val MIN_FREE = 5L * 1024L * 1024L
    }

    private val fileLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null
    private var out: DataOutputStream? = null
    private var dataBytes = 0L
    private var recorderBufferSize = 4096
    private var sensitivity = 0
    private var bluetoothScoStarted = false
    private var communicationDevice: AudioDeviceInfo? = null
    private var headset: BluetoothHeadset? = null
    private var headsetDevice: BluetoothDevice? = null

    @Volatile private var running = false
    @Volatile private var finalized = false

    private val retry = object : Runnable {
        override fun run() {
            if (running && recorder == null) {
                tryResumeBluetooth()
            }
            if (running) handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "EchoLink recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible notification while EchoLink records audio."
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> startCapture(intent.getIntExtra(EXTRA_SENSITIVITY, 0))
            STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("EchoLink is recording")
            .setContentText("Bluetooth microphone capture is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun startCapture(level: Int) {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        sensitivity = level.coerceIn(0, 100)
        finalized = false
        dataBytes = 0L

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }

            val directory = File(getExternalFilesDir("recordings") ?: filesDir, "recordings").apply { mkdirs() }
            output = File(
                directory,
                "EchoLink_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.UK).format(Date()) + ".wav"
            )
            out = DataOutputStream(BufferedOutputStream(FileOutputStream(output!!)))
            writeWavHeader(out!!, 0)

            routeToBluetooth()
            running = true
            broadcast(0)

            worker = Thread({
                captureLoop()
            }, "EchoLinkAudioCapture").also { it.start() }

            handler.removeCallbacks(retry)
            handler.post(retry)
        } catch (_: Exception) {
            running = false
            finalizeFile()
            cleanupRouting()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun captureLoop() {
        var storageFull = false
        var lastMeterTime = 0L

        try {
            while (running) {
                if (recorder == null) {
                    if (!tryCreateRecorder()) {
                        Thread.sleep(500)
                        continue
                    }
                }

                val r = recorder ?: continue
                try {
                    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        r.startRecording()
                    }

                    val routed = try { r.routedDevice } catch (_: Exception) { null }
                    if (routed != null && !isBluetoothInput(routed)) {
                        releaseRecorder()
                        Thread.sleep(300)
                        continue
                    }

                    val buffer = ByteArray(max(2048, recorderBufferSize))
                    val n = r.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

                    if (n > 0) {
                        val gain = 1f + (sensitivity / 100f) * 1.5f
                        if (sensitivity > 0) applyGain(buffer, n, gain)

                        synchronized(fileLock) {
                            if (out != null) {
                                out!!.write(buffer, 0, n)
                                dataBytes += n.toLong()
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastMeterTime >= 90L) {
                            lastMeterTime = now
                            broadcast(peak(buffer, n))
                        }

                        val free = output?.parentFile?.usableSpace ?: Long.MAX_VALUE
                        if (free < MIN_FREE) {
                            storageFull = true
                            running = false
                            break
                        }
                    } else if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR) {
                        releaseRecorder()
                    }
                } catch (_: Exception) {
                    releaseRecorder()
                }
            }
        } catch (_:InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            releaseRecorder()
            handler.removeCallbacks(retry)
            finalizeFile()
            cleanupRouting()
            broadcast(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        if (storageFull) {
            ToastMessage.notifyStorage(this)
        }
    }

    private fun tryCreateRecorder(): Boolean {
        if (!running) return false
        val input = findBluetoothInput() ?: return false

        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false

            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            recorderBufferSize = minBuf * 2

            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }

            if (!r.setPreferredDevice(input)) {
                r.release()
                return false
            }

            recorder = r
            return true
        } catch (_: Exception) {
            releaseRecorder()
            false
        }
    }

    private fun tryResumeBluetooth() {
        if (!running) return
        try {
            routeToBluetooth()
            if (recorder == null) tryCreateRecorder()
        } catch (_: Exception) {}
    }

    private fun routeToBluetooth() {
        val audio = getSystemService(AudioManager::class.java)

        if (Build.VERSION.SDK_INT >= 31) {
            val device = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (device != null) {
                communicationDevice = device
                audio.setCommunicationDevice(device)
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            bluetoothScoStarted = true
        }

        if (Build.VERSION.SDK_INT >= 31) connectHfpProfile()
    }

    private fun connectHfpProfile() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        try {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HEADSET) return
                    headset = proxy as BluetoothHeadset
                    headsetDevice = headset?.connectedDevices?.firstOrNull()
                    headsetDevice?.let { device ->
                        try {
                            if (headset?.isAudioConnected(device) != true &&
                                headset?.isVoiceRecognitionSupported(device) == true) {
                                headset?.startVoiceRecognition(device)
                            }
                        } catch (_: Exception) {}
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headset = null
                        headsetDevice = null
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (_: Exception) {}
    }

    private fun findBluetoothInput(): AudioDeviceInfo? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { isBluetoothInput(it) }
    }

    private fun isBluetoothInput(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun releaseRecorder() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun stopCapture() {
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = false
        handler.removeCallbacks(retry)
        releaseRecorder()
        // The capture thread owns finalization. This prevents the WAV header from being
        // overwritten by a second concurrent finalization pass.
    }

    private fun cleanupRouting() {
        val audio = getSystemService(AudioManager::class.java)

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                headsetDevice?.let { device ->
                    if (headset?.isAudioConnected(device) == true) {
                        headset?.stopVoiceRecognition(device)
                    }
                }
            } catch (_: Exception) {}
            try { audio.clearCommunicationDevice() } catch (_: Exception) {}
        }

        if (bluetoothScoStarted) {
            try {
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            } catch (_: Exception) {}
        }

        bluetoothScoStarted = false

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                headset?.let { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            } catch (_: Exception) {}
        }

        headset = null
        headsetDevice = null
        communicationDevice = null

        try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
    }

    private fun finalizeFile() {
        synchronized(fileLock) {
            if (finalized) return
            finalized = true

            val file = output
            val bytes = dataBytes

            try { out?.flush() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            out = null

            if (file != null && file.exists() && file.length() >= 44L) {
                if (bytes > 0L) {
                    try {
                        RandomAccessFile(file, "rw").use { r ->
                            r.seek(4)
                            r.writeInt(Integer.reverseBytes((36L + bytes).coerceAtMost(0x7FFFFFFFL).toInt()))
                            r.seek(40)
                            r.writeInt(Integer.reverseBytes(bytes.coerceAtMost(0x7FFFFFFFL).toInt()))
                        }
                    } catch (_: Exception) {}
                } else {
                    try { file.delete() } catch (_: Exception) {}
                }
            }

            dataBytes = 0L
        }
    }

    private fun broadcast(meter: Int) {
        val bytes: Long
        synchronized(fileLock) { bytes = dataBytes }
        val elapsed = bytes / (RATE.toLong() * CHANNELS * BYTES_PER_SAMPLE)
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RECORDING, running)
                .putExtra(EXTRA_TIMER, String.format(Locale.UK, "%02d:%02d", elapsed / 60L, elapsed % 60L))
                .putExtra(EXTRA_METER, meter.coerceIn(0, 100))
        )
    }

    private fun applyGain(buffer: ByteArray, n: Int, gain: Float) {
        var i = 0
        while (i + 1 < n) {
            val raw = (buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)
            val signed = if (raw and 0x8000 != 0) raw - 65536 else raw
            val value = (signed * gain).toInt().coerceIn(-32768, 32767)
            buffer[i] = (value and 255).toByte()
            buffer[i + 1] = (value shr 8).toByte()
            i += 2
        }
    }

    private fun peak(buffer: ByteArray, n: Int): Int {
        var p = 0
        var i = 0
        while (i + 1 < n) {
            val raw = (buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)
            val signed = if (raw and 0x8000 != 0) raw - 65536 else raw
            p = max(p, abs(signed))
            i += 2
        }
        return (p / 327.68f).toInt().coerceIn(0, 100)
    }

    private fun writeWavHeader(o: DataOutputStream, size: Long) {
        o.writeBytes("RIFF")
        o.writeInt(Integer.reverseBytes((36L + size).coerceAtMost(0x7FFFFFFFL).toInt()))
        o.writeBytes("WAVE")
        o.writeBytes("fmt ")
        o.writeInt(Integer.reverseBytes(16))
        o.writeShort(java.lang.Short.reverseBytes(1).toInt())
        o.writeShort(java.lang.Short.reverseBytes(CHANNELS.toShort()).toInt())
        o.writeInt(Integer.reverseBytes(RATE))
        o.writeInt(Integer.reverseBytes(RATE * CHANNELS * BYTES_PER_SAMPLE))
        o.writeShort(java.lang.Short.reverseBytes((CHANNELS * BYTES_PER_SAMPLE).toShort()).toInt())
        o.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        o.writeBytes("data")
        o.writeInt(Integer.reverseBytes(size.coerceAtMost(0x7FFFFFFFL).toInt()))
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(retry)
        releaseRecorder()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private object ToastMessage {
        fun notifyStorage(context: Context) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Storage is nearly full. Recording was saved and stopped.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
