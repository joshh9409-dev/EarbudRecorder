package com.echolink.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
            setBackgroundColor(0xFF080A12.toInt())
        }

        val title = TextView(this).apply {
            text = "ECHOLINK"
            textSize = 30f
            setTextColor(0xFFEAF7FF.toInt())
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Bluetooth microphone recorder"
            textSize = 14f
            setTextColor(0xFF8EA4B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 24)
        }

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFBDEBFF.toInt())
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xFF111827.toInt())
        }

        recordButton = Button(this).apply {
            text = "●  START RECORDING"
            textSize = 18f
            setOnClickListener { toggleRecording() }
        }

        val info = TextView(this).apply {
            text = "No live playback. Recordings stay on the phone until deleted. Android recording indicators remain active while recording."
            textSize = 13f
            setTextColor(0xFF8EA4B8.toInt())
            setPadding(12, 24, 12, 0)
        }

        root.addView(title, LinearLayout.LayoutParams(-1, 70))
        root.addView(subtitle)
        root.addView(status)
        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(recordButton, LinearLayout.LayoutParams(-1, 80))
        root.addView(info)
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun refreshStatus() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        val enabled = bt?.isEnabled == true
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val input = findBluetoothInput()
        status.text = "BLUETOOTH: " + if (enabled) "ON" else "OFF"
        status.append("\nMIC PERMISSION: " + if (micGranted) "READY" else "NEEDED")
        status.append("\nEARBUD MICROPHONE: " + (input ?: "NOT DETECTED"))
        status.append("\nSTATUS: " + if (enabled && micGranted && input != null)
            "EARBUD MICROPHONE READY" else "CONNECT AN EARBUD WITH A MIC")
    }

    private fun findBluetoothInput(): String? {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val audio = getSystemService(AudioManager::class.java)
        val devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val device = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        return device?.productName?.toString() ?: if (device != null) "Bluetooth headset" else null
    }

    private fun toggleRecording() {
        if (!recording) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionsIfNeeded()
                return
            }
            val input = findBluetoothInput()
            if (input == null) {
                refreshStatus()
                return
            }
            val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            recording = true
            recordButton.text = "■  STOP & SAVE"
        } else {
            val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.STOP)
            startService(intent)
            recording = false
            recordButton.text = "●  START RECORDING"
        }
    }
}
