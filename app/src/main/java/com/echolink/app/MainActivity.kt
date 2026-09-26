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
import android.view.animation.LinearInterpolator
import android.widget.*
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var recordButton: TextView
    private lateinit var timerText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var liveWave: LiveWaveformView
    private lateinit var libraryContainer: LinearLayout
    private lateinit var sensitivityLabel: TextView
    private var recording = false
    private var pulse: ObjectAnimator? = null
    private var player: MediaPlayer? = null
    private var exportFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); if (!recording) refreshLibrary(); handler.postDelayed(this, 2500) }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RecordingService.ACTION_STATE) {
                recording = intent.getBooleanExtra(RecordingService.EXTRA_RECORDING, false)
                timerText.text = intent.getStringExtra(RecordingService.EXTRA_TIMER) ?: "00:00"
                meter.progress = intent.getIntExtra(RecordingService.EXTRA_METER, 0)
                    liveWave.setLevel(meter.progress / 100f)
                updateRecordButton()
                if (!recording) refreshLibrary()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
        refreshStatus()
        refreshLibrary()
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        val f = IntentFilter(RecordingService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, f)
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        player?.release()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 28)
            setBackgroundColor(Color.rgb(6, 9, 18))
        }
        scroll.addView(root)
        root.addView(label("ECHOLINK", 31f, Color.WHITE).apply {
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, lp(-1, 52))
        root.addView(label("Bluetooth earbud recorder", 14f, 0xFF8EA4B8).apply { gravity = Gravity.CENTER }, lp(-1, 30))

        val dashboard = panel()
        statusText = label("Checking Bluetooth…", 14f, 0xFFBDEBFF)
        dashboard.addView(statusText, lp(-1, -2))
        root.addView(dashboard, lp(-1, -2))

        timerText = label("00:00", 38f, Color.WHITE).apply {
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(timerText, lp(-1, 60))

        recordButton = TextView(this).apply {
            text = "●  START RECORDING"; textSize = 18f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = buttonBackground(false); setOnClickListener { toggleRecording() }
        }
        root.addView(recordButton, lp(-1, 82))

        root.addView(label("LIVE INPUT LEVEL", 11f, 0xFF7890A5).apply { setPadding(4,16,4,5) })
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(meter, lp(-1, 18))
        liveWave = LiveWaveformView(this)
        root.addView(liveWave, lp(-1, 58))

        root.addView(label("SENSITIVITY", 11f, 0xFF7890A5).apply { setPadding(4,16,4,0) })
        sensitivityLabel = label("Normal", 13f, 0xFFBDEBFF)
        root.addView(sensitivityLabel)
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

        root.addView(label("No live playback. Recordings remain inside EchoLink until deleted or exported. Android's recording indicator and foreground notification remain visible while recording.", 12f, 0xFF71849A).apply { setPadding(4,8,4,18) })
        root.addView(label("RECORDING LIBRARY", 20f, Color.WHITE).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }, lp(-1,44))
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(libraryContainer, lp(-1,-2))
        setContentView(scroll)
    }

    private fun toggleRecording() {
        if (!recording) {
            if (!readyToRecord()) { refreshStatus(); return }
            val i = Intent(this, RecordingService::class.java).setAction(RecordingService.START)
                .putExtra(RecordingService.EXTRA_SENSITIVITY, getPreferences(MODE_PRIVATE).getInt("sensitivity", 0))
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else startService(Intent(this, RecordingService::class.java).setAction(RecordingService.STOP))
    }

    private fun readyToRecord() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        findBluetoothInput() != null

    private fun updateRecordButton() {
        if (recording) {
            recordButton.text = "■  STOP & SAVE"; recordButton.background = buttonBackground(true)
            if (pulse == null) pulse = ObjectAnimator.ofFloat(recordButton, View.ALPHA,1f,.62f,1f).apply {
                duration=1200; repeatCount=ObjectAnimator.INFINITE; interpolator=LinearInterpolator(); start()
            }
        } else {
            recordButton.text = "●  START RECORDING"; recordButton.background = buttonBackground(false)
            pulse?.cancel(); pulse=null; recordButton.alpha=1f; meter.progress=0
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val enabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        val input = findBluetoothInput()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val free = recordingsDir().usableSpace
        val freeText = if (free < 1024L*1024L) "${free/1024} KB" else "${free/1024/1024} MB"
        statusText.text = "BLUETOOTH       ${if(enabled) "ONLINE" else "OFF"}
" +
            "EARBUD MIC      ${input ?: "NOT DETECTED"}
" +
            "RECORD AUDIO    ${if(mic) "GRANTED" else "NEEDED"}
" +
            "STORAGE         $freeText FREE
" +
            "ROUTING         ${if(input!=null) "BLUETOOTH INPUT" else "WAITING FOR EARBUD"}
" +
            "READY           ${if(enabled && mic && input!=null) "EARBUD MICROPHONE READY" else "CONNECT EARBUD WITH MIC"}"
        recordButton.isEnabled = recording || (enabled && mic && input != null)
        recordButton.alpha = if(recordButton.isEnabled) 1f else .45f
    }

    private fun refreshLibrary() {
        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()
        val files = recordingsDir().listFiles { f -> f.extension.equals("wav",true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) { libraryContainer.addView(label("No recordings yet.",13f,0xFF65788D)); return }
        files.forEach { addRecordingCard(it) }
    }

    private fun addRecordingCard(file: File) {
        val card=panel()
        card.addView(label(file.name.removePrefix("EchoLink_").removeSuffix(".wav"),16f,Color.WHITE).apply { typeface=android.graphics.Typeface.DEFAULT_BOLD },lp(-1,34))
        card.addView(label("${formatSize(file.length())}  •  ${duration(file)}",12f,0xFF8EA4B8),lp(-1,26))
        val wave=WaveformView(this); wave.load(file); card.addView(wave,lp(-1,62))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val play=smallButton("PLAY"); val back=smallButton("−10s"); val fwd=smallButton("+10s")
        val speed=smallButton("1×"); val boost=smallButton("BOOST"); val export=smallButton("EXPORT"); val del=smallButton("DELETE")
        listOf(play,back,fwd,speed,boost,export,del).forEach{controls.addView(it,weightLp())}; card.addView(controls,lp(-1,48))
        var rate=1f; var boosted=false
        play.setOnClickListener {
            if(player!=null){player?.release();player=null;play.text="PLAY"} else try {
                player=MediaPlayer().apply{
                    setDataSource(file.absolutePath);prepare()
                    setOnCompletionListener{release();player=null;play.text="PLAY"}
                    setVolume(if(boosted)1.5f else 1f,if(boosted)1.5f else 1f)
                    if(Build.VERSION.SDK_INT>=23) playbackParams=playbackParams.setSpeed(rate)
                    start()
                }; play.text="STOP"
            } catch(_:Exception){player=null;Toast.makeText(this,"Unable to play this recording",Toast.LENGTH_SHORT).show()}
        }
        back.setOnClickListener{player?.let{it.seekTo((it.currentPosition-10000).coerceAtLeast(0))}}
        fwd.setOnClickListener{player?.let{it.seekTo((it.currentPosition+10000).coerceAtMost(it.duration))}}
        speed.setOnClickListener{
            rate=when(rate){1f->1.25f;1.25f->1.5f;1.5f->.75f;else->1f};speed.text="${rate}×"
            if(Build.VERSION.SDK_INT>=23) player?.let { it.playbackParams = it.playbackParams.setSpeed(rate) }
        }
        boost.setOnClickListener{boosted=!boosted;boost.text=if(boosted)"BOOST ON" else "BOOST";player?.setVolume(if(boosted)1.5f else 1f,if(boosted)1.5f else 1f)}
        export.setOnClickListener { exportFile=file; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="audio/wav"; putExtra(Intent.EXTRA_TITLE,file.name) }, 400) }
        del.setOnClickListener{player?.release();player=null;if(file.delete())refreshLibrary()}
        libraryContainer.addView(card,lp(-1,-2))
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==400 && resultCode==RESULT_OK && data?.data!=null && exportFile!=null){
            try{ contentResolver.openOutputStream(data.data!!)?.use{out->exportFile!!.inputStream().use{input->input.copyTo(out)}}; Toast.makeText(this,"Recording exported",Toast.LENGTH_SHORT).show() }
            catch(_:Exception){ Toast.makeText(this,"Export failed",Toast.LENGTH_SHORT).show() }
            finally{exportFile=null}
        }
    }

    private fun duration(file:File):String=try{RandomAccessFile(file,"r").use{r->r.seek(24);val rate=Integer.reverseBytes(r.readInt());r.seek(40);val bytes=Integer.reverseBytes(r.readInt()).toLong();val s=if(rate>0)bytes/(rate*2L) else 0;"%02d:%02d".format(Locale.UK,s/60,s%60)}}catch(_:Exception){"00:00"}
    private fun recordingsDir()=File(getExternalFilesDir("recordings") ?: filesDir,"recordings").apply{mkdirs()}

    private fun findBluetoothInput():AudioDeviceInfo?{
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return null
        return getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull{
            it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT>=31 && it.type==AudioDeviceInfo.TYPE_BLE_HEADSET)}
    }
    private fun requestPermissionsIfNeeded(){
        val p=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=31){p+=Manifest.permission.BLUETOOTH_CONNECT;p+=Manifest.permission.BLUETOOTH_SCAN}
        if(Build.VERSION.SDK_INT>=33)p+=Manifest.permission.POST_NOTIFICATIONS
        val m=p.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(m.isNotEmpty())requestPermissions(m.toTypedArray(),100)
    }
    private fun label(t:String,s:Float,c:Number)=TextView(this).apply{text=t;textSize=s;setTextColor(c.toInt())}
    private fun panel()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,14,16,14);background=GradientDrawable().apply{cornerRadius=22f;setColor(0xFF101827.toInt());setStroke(1,0xFF203247.toInt())}}
    private fun buttonBackground(active:Boolean)=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,if(active)intArrayOf(0xFF7D1837.toInt(),0xFF3B1024.toInt())else intArrayOf(0xFF20AEEA.toInt(),0xFF1152A0.toInt())).apply{cornerRadius=42f;setStroke(2,0xFF76E4FF.toInt())}
    private fun smallButton(t:String)=TextView(this).apply{text=t;textSize=10f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=GradientDrawable().apply{cornerRadius=14f;setColor(0xFF16263A.toInt())}}
    private fun lp(w:Int,h:Int)=LinearLayout.LayoutParams(w,h).apply{setMargins(0,5,0,5)}
    private fun weightLp()=LinearLayout.LayoutParams(0,-1,1f).apply{setMargins(2,0,2,0)}
    private fun sensitivityName(p:Int)=when{p<20->"Normal";p<40->"High";p<60->"Very High";p<80->"Extreme";else->"Extreme+"}
    private fun formatSize(b:Long)=if(b>=1024*1024)"%.1f MB".format(Locale.UK,b/1024f/1024f) else "${b/1024} KB"

    class LiveWaveformView(c:Context):View(c){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xFF64D8FF.toInt();strokeWidth=4f}
        private val levels=FloatArray(72)
        fun setLevel(v:Float){System.arraycopy(levels,1,levels,0,levels.size-1);levels[levels.lastIndex]=v.coerceIn(0f,1f);invalidate()}
        override fun onDraw(c:Canvas){super.onDraw(c);val mid=height/2f;val step=width/levels.size.toFloat();levels.forEachIndexed{i,v->val h=(v*height*.9f).coerceAtLeast(2f);c.drawLine(i*step,mid-h/2,i*step,mid+h/2,paint)}}
    }

    class WaveformView(c:Context):View(c){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xFF36CFFF.toInt();strokeWidth=3f}
        private var levels=FloatArray(0)
        fun load(f:File){try{RandomAccessFile(f,"r").use{r->if(r.length()<44)return;r.seek(44);val samples=(r.length()-44)/2;val count=90;levels=FloatArray(count)
            for(i in 0 until count){val start=i*samples/count;val end=((i+1)*samples/count).coerceAtLeast(start+1);r.seek(44+start*2);var max=0;var n=start
                while(n<end){max=maxOf(max,kotlin.math.abs(java.lang.Short.reverseBytes(r.readShort()).toInt()));n++};levels[i]=max/32768f}};invalidate()}catch(_:Exception){}}
        override fun onDraw(c:Canvas){super.onDraw(c);val mid=height/2f;val step=if(levels.isNotEmpty())width/levels.size.toFloat()else width.toFloat();levels.forEachIndexed{i,v->val h=(v*height*.85f).coerceAtLeast(3f);c.drawLine(i*step,mid-h/2,i*step,mid+h/2,paint)}}
    }
}