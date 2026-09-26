package com.echolink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Build
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    companion object {
        const val START = "com.echolink.START"
        const val STOP = "com.echolink.STOP"
        private const val CHANNEL = "echolink_recording"
        private const val NOTIFICATION_ID = 7
    }

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null
    @Volatile private var running = false
    private var bluetoothScoStarted = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "EchoLink recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> startCapture()
            STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("EchoLink is recording")
            .setContentText("Bluetooth microphone capture is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun startCapture() {
        if (running) return
        startForeground(NOTIFICATION_ID, notification())

        try {
            val audioManager = getSystemService(AudioManager::class.java)
            routeToBluetooth(audioManager)

            val rate = 16000
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) throw IllegalStateException("Invalid audio buffer")

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Audio input unavailable")
            }

            val preferred = findBluetoothInput(audioManager)
            if (preferred != null && Build.VERSION.SDK_INT >= 23) {
                recorder?.setPreferredDevice(preferred)
            }

            output = File(
                getExternalFilesDir("recordings"),
                "EchoLink_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date()) + ".wav"
            )
            output!!.parentFile?.mkdirs()

            val out = DataOutputStream(
                BufferedOutputStream(FileOutputStream(output!!))
            )
            writeWavHeader(out, 0)

            running = true
            recorder!!.startRecording()

            worker = Thread {
                val buffer = ByteArray(min * 2)
                var bytes = 0L
                try {
                    while (running) {
                        val n = recorder?.read(buffer, 0, buffer.size) ?: 0
                        if (n > 0) {
                            out.write(buffer, 0, n)
                            bytes += n
                        }
                    }
                } finally {
                    try { out.flush(); out.close() } catch (_: Exception) {}
                    try {
                        output?.let { file ->
                            RandomAccessFile(file, "rw").use { f -> writeWavSizes(f, bytes) }
                        }
                    } catch (_: Exception) {}
                }
            }.also { it.start() }
        } catch (_: Exception) {
            stopCapture()
        }
    }

    private fun routeToBluetooth(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= 31) {
            val device = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (device != null) {
                audio.setCommunicationDevice(device)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
                bluetoothScoStarted = true
            } catch (_: Exception) {}
        }
    }

    private fun findBluetoothInput(audio: AudioManager): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    private fun stopCapture() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null

        val audio = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            try { audio.clearCommunicationDevice() } catch (_: Exception) {}
        }
        if (bluetoothScoStarted) {
            try { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() } catch (_: Exception) {}
            bluetoothScoStarted = false
        }
        try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun writeWavHeader(out: DataOutputStream, size: Int) {
        out.writeBytes("RIFF")
        out.writeInt(Integer.reverseBytes(36 + size))
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeInt(Integer.reverseBytes(16))
        out.writeShort(java.lang.Short.reverseBytes(1).toInt())
        out.writeShort(java.lang.Short.reverseBytes(1).toInt())
        out.writeInt(Integer.reverseBytes(16000))
        out.writeInt(Integer.reverseBytes(32000))
        out.writeShort(java.lang.Short.reverseBytes(2).toInt())
        out.writeShort(java.lang.Short.reverseBytes(16).toInt())
        out.writeBytes("data")
        out.writeInt(Integer.reverseBytes(size))
    }

    private fun writeWavSizes(f: RandomAccessFile, size: Long) {
        f.seek(4)
        f.writeInt(Integer.reverseBytes((36 + size).toInt()))
        f.seek(40)
        f.writeInt(Integer.reverseBytes(size.toInt()))
    }

    override fun onDestroy() {
        running = false
        try { recorder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
