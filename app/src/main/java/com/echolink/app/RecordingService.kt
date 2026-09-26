package com.echolink.app

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RecordingService : Service() {
    companion object {
        const val START="com.echolink.START"; const val STOP="com.echolink.STOP"
        const val ACTION_STATE="com.echolink.STATE"
        const val EXTRA_RECORDING="recording"; const val EXTRA_TIMER="timer"; const val EXTRA_METER="meter"
        const val EXTRA_SENSITIVITY="sensitivity"
        private const val CHANNEL="echolink_recording"; private const val NOTIFICATION_ID=7
        private const val RATE=16000; private const val MIN_FREE=5L*1024*1024
    }
    private var recorder:AudioRecord?=null
    private var worker:Thread?=null
    private var output:File?=null
    private var out:DataOutputStream?=null
    @Volatile private var running=false
    private var bluetoothScoStarted=false
    private var communicationDevice:AudioDeviceInfo?=null
    private var headset:BluetoothHeadset?=null
    private var headsetDevice:BluetoothDevice?=null
    private var sensitivity=0
    private var dataBytes=0L
    private var recorderBufferSize=4096
    private var startedAt=0L
    private val handler=Handler(Looper.getMainLooper())
    private val retry=object:Runnable{override fun run(){if(running && recorder==null)tryResumeBluetooth();if(running)handler.postDelayed(this,1500)}}

    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"EchoLink recording",NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
        when(i?.action){START->startCapture(i.getIntExtra(EXTRA_SENSITIVITY,0));STOP->stopCapture()}
        return START_NOT_STICKY
    }

    private fun notification():Notification=Notification.Builder(this,CHANNEL)
        .setContentTitle("EchoLink is recording").setContentText("Bluetooth microphone capture is active")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()

    private fun startCapture(level:Int){
        if(running)return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return}
        startForeground(NOTIFICATION_ID,notification())
        sensitivity=level.coerceIn(0,100)
        output=File(getExternalFilesDir("recordings") ?: filesDir,"recordings").apply{mkdirs()}
            .let{File(it,"EchoLink_"+SimpleDateFormat("yyyyMMdd_HHmmss",Locale.UK).format(Date())+".wav")}
        try{
            routeToBluetooth()
            out=DataOutputStream(BufferedOutputStream(FileOutputStream(output!!)))
            writeWavHeader(out!!,0)
            running=true;startedAt=System.currentTimeMillis()
            broadcast(0)
            worker=Thread{captureLoop()}.also{it.start()}
            handler.post(retry)
        }catch(_:Exception){finishFile();stopSelf()}
    }

    private fun captureLoop(){
        var lastMeter=0
        var storageFull=false
        while(running){
            if(recorder==null){
                if(!tryCreateRecorder()){Thread.sleep(700);continue}
            }
            val r=recorder ?: continue
            val buffer=ByteArray(max(2048,recorderBufferSize))
            try{
                if(r.recordingState!=AudioRecord.RECORDSTATE_RECORDING)r.startRecording()
                val n=r.read(buffer,0,buffer.size)
                if(n>0){
                    val gain=1f+(sensitivity/100f)*1.5f
                    if(sensitivity>0)applyGain(buffer,n,gain)
                    out?.write(buffer,0,n);dataBytes+=n
                    if(dataBytes%32768L < n) {
                        val peak=peak(buffer,n)
                        lastMeter=(peak/327.68f).toInt().coerceIn(0,100)
                        broadcast(lastMeter)
                    }
                    if(output?.parentFile?.usableSpace ?: Long.MAX_VALUE < MIN_FREE){storageFull=true;running=false;break}
                }else if(n<0){releaseRecorder()}
            }catch(_:Exception){releaseRecorder()}
        }
        finishFile()
        if(storageFull){ cleanupRouting(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        broadcast(0)
    }

    private fun tryCreateRecorder():Boolean{
        if(!running && startedAt==0L)return false
        if(findBluetoothInput()==null)return false
        try{
            val minBuf=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if(minBuf<=0)return false
            val r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,minBuf*2)
            recorderBufferSize=minBuf*2
            if(r.state!=AudioRecord.STATE_INITIALIZED){r.release();return false}
            val d=findBluetoothInput() ?: run{r.release();return false}
            if(Build.VERSION.SDK_INT>=23 && !r.setPreferredDevice(d)){r.release();return false}
            recorder=r
            broadcast(0)
            return true
        }catch(_:Exception){releaseRecorder();return false}
    }

    private fun tryResumeBluetooth(){
        try{routeToBluetooth();tryCreateRecorder()}catch(_:Exception){}
    }

    private fun routeToBluetooth(){
        val audio=getSystemService(AudioManager::class.java)
        if(Build.VERSION.SDK_INT>=31){
            val d=audio.availableCommunicationDevices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BLE_HEADSET||it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO}
            if(d!=null){communicationDevice=d;audio.setCommunicationDevice(d)}
        }else if(Build.VERSION.SDK_INT>=26){
            audio.mode=AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") audio.startBluetoothSco()
            @Suppress("DEPRECATION") audio.isBluetoothScoOn=true
            bluetoothScoStarted=true
        }
        if(Build.VERSION.SDK_INT>=31)connectHfpProfile()
    }

    private fun connectHfpProfile(){
        val a=BluetoothAdapter.getDefaultAdapter() ?: return
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return
        a.getProfileProxy(this,object:BluetoothProfile.ServiceListener{
            override fun onServiceConnected(profile:Int,proxy:BluetoothProfile){
                if(profile!=BluetoothProfile.HEADSET)return
                headset=proxy as BluetoothHeadset
                headsetDevice=headset?.connectedDevices?.firstOrNull()
                headsetDevice?.let{d->try{if(headset?.isAudioConnected(d)!=true)headset?.startVoiceRecognition(d)}catch(_:Exception){}}
            }
            override fun onServiceDisconnected(profile:Int){if(profile==BluetoothProfile.HEADSET){headset=null;headsetDevice=null}}
        },BluetoothProfile.HEADSET)
    }

    private fun findBluetoothInput():AudioDeviceInfo?{
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return null
        return getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull{
            it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT>=31 && it.type==AudioDeviceInfo.TYPE_BLE_HEADSET)}
    }

    private fun releaseRecorder(){try{recorder?.stop()}catch(_:Exception){};try{recorder?.release()}catch(_:Exception){};recorder=null}

    private fun stopCapture(){
        if(!running){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return}
        running=false
        handler.removeCallbacks(retry)
        releaseRecorder()
        finishFile()
        cleanupRouting()
        broadcast(0)
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    
    private fun cleanupRouting(){
        val audio=getSystemService(AudioManager::class.java)
        if(Build.VERSION.SDK_INT>=31)try{headsetDevice?.let{d->headset?.stopVoiceRecognition(d)};audio.clearCommunicationDevice()}catch(_:Exception){}
        if(bluetoothScoStarted)try{@Suppress("DEPRECATION") audio.isBluetoothScoOn=false;@Suppress("DEPRECATION") audio.stopBluetoothSco()}catch(_:Exception){}
        bluetoothScoStarted=false
        if(Build.VERSION.SDK_INT>=31)try{headset?.let{BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HEADSET,it)}}catch(_:Exception){}
        headset=null;headsetDevice=null;communicationDevice=null
        try{audio.mode=AudioManager.MODE_NORMAL}catch(_:Exception){}
    }

    private fun finishFile(){
        try{out?.flush();out?.close()}catch(_:Exception){}
        out=null
        output?.let{f->try{RandomAccessFile(f,"rw").use{r->r.seek(4);r.writeInt(Integer.reverseBytes((36+dataBytes).toInt()));r.seek(40);r.writeInt(Integer.reverseBytes(dataBytes.toInt()))}}catch(_:Exception){}}
        dataBytes=0;startedAt=0L
    }

    private fun broadcast(meter:Int){
        val elapsed=if(dataBytes>0)dataBytes/(RATE*2L) else 0
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_RECORDING,running)
            .putExtra(EXTRA_TIMER,"%02d:%02d".format(Locale.UK,elapsed/60,elapsed%60)).putExtra(EXTRA_METER,meter))
    }

    private fun applyGain(b:ByteArray,n:Int,gain:Float){
        var i=0
        while(i+1<n){
            val s=(b[i].toInt() and 255) or (b[i+1].toInt() shl 8)
            val signed=if(s and 0x8000!=0)s-65536 else s
            val v=(signed*gain).toInt().coerceIn(-32768,32767)
            b[i]=(v and 255).toByte();b[i+1]=(v shr 8).toByte();i+=2
        }
    }
    private fun peak(b:ByteArray,n:Int):Int{var p=0;var i=0;while(i+1<n){val s=(b[i].toInt() and 255) or (b[i+1].toInt() shl 8);val v=if(s and 0x8000!=0)s-65536 else s;p=max(p,abs(v));i+=2};return p}

    private fun writeWavHeader(o:DataOutputStream,size:Int){
        o.writeBytes("RIFF");o.writeInt(Integer.reverseBytes(36+size));o.writeBytes("WAVEfmt ")
        o.writeInt(Integer.reverseBytes(16));o.writeShort(java.lang.Short.reverseBytes(1).toInt())
        o.writeShort(java.lang.Short.reverseBytes(1).toInt());o.writeInt(Integer.reverseBytes(RATE))
        o.writeInt(Integer.reverseBytes(RATE*2));o.writeShort(java.lang.Short.reverseBytes(2).toInt())
        o.writeShort(java.lang.Short.reverseBytes(16).toInt());o.writeBytes("data");o.writeInt(Integer.reverseBytes(size))
    }
    override fun onDestroy(){running=false;handler.removeCallbacks(retry);releaseRecorder();try{out?.close()}catch(_:Exception){};super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null
}