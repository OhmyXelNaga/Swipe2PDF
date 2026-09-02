package com.example.swipe2pdf

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService:LifecycleService(){
    companion object { const val EXTRA_RESULT="result";const val EXTRA_DATA="data";const val EXTRA_CONFIG="config";const val ACTION_START="start";const val ACTION_PAUSE="pause";const val ACTION_RESUME="resume";const val ACTION_STOP="stop";const val ACTION_DISCARD="discard" }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val running=AtomicBoolean(false);private val loopStarted=AtomicBoolean(false);private var paused=false;private var screenWidth=0;private var screenHeight=0;private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private var display:android.hardware.display.VirtualDisplay?=null;private var overlay:View?=null;private lateinit var wm:WindowManager;private var job:Job?=null;private var config=JobConfig()
    override fun onCreate(){super.onCreate();wm=getSystemService(WINDOW_SERVICE) as WindowManager;createChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){ACTION_START->{if(running.get()&&loopStarted.compareAndSet(false,true))job=scope.launch{loop(screenWidth,screenHeight)}};ACTION_PAUSE->{if(loopStarted.get()){paused=true;AppState.state.value=JobState.Paused;updateOverlay()}};ACTION_RESUME->{if(loopStarted.get()){paused=false;AppState.state.value=JobState.Capturing;updateOverlay()}};ACTION_STOP->showStopChoice();ACTION_DISCARD->{AppState.captures.value.forEach(File::delete);AppState.captures.value=emptyList();finish(false,"작업과 촬영본을 모두 폐기했습니다.")};else->prepare(intent)};return START_NOT_STICKY
    }
    private fun prepare(i:Intent?){if(!running.compareAndSet(false,true))return
        config=ConfigCodec.decode(i?.getStringExtra(EXTRA_CONFIG));val data=if(Build.VERSION.SDK_INT>=33)i?.getParcelableExtra(EXTRA_DATA,Intent::class.java) else @Suppress("DEPRECATION") i?.getParcelableExtra(EXTRA_DATA)
        if(data==null||!Settings.canDrawOverlays(this)||SwipeAccessibilityService.instance==null){finish(true,"필수 권한 또는 접근성 서비스가 준비되지 않았습니다.");return}
        startForeground(7,notification("준비 중"));AppState.state.value=JobState.Preparing
        val metrics=resources.displayMetrics;val w=metrics.widthPixels;val h=metrics.heightPixels;screenWidth=w;screenHeight=h
        projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(i?.getIntExtra(EXTRA_RESULT,Activity.RESULT_CANCELED)?:0,data)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){if(running.get())finish(true,"화면 캡처 세션이 종료되었습니다. 다시 동의해 주세요.")}},Handler(Looper.getMainLooper()))
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);display=projection?.createVirtualDisplay("Swipe2PDF",w,h,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null);showOverlay();AppState.message.value="대상 앱으로 이동한 뒤 플로팅 컨트롤러의 시작을 누르세요."
    }
    private suspend fun loop(w:Int,h:Int){AppState.state.value=JobState.Capturing;AppState.progress.value=0 to config.pages
        try{for(index in 0 until config.pages){while(paused&&running.get())delay(100);if(!running.get())return
            val m=resources.displayMetrics;if(m.widthPixels!=w||m.heightPixels!=h)throw IllegalStateException("기기 회전·접기 상태 또는 화면 크기가 바뀌었습니다. 촬영본을 보존했으니 작업을 다시 시작해 주세요.")
            overlay?.visibility=View.GONE;delay(180);val file=capture(index);overlay?.visibility=View.VISIBLE;AppState.captures.value=AppState.captures.value+file;AppState.progress.value=(index+1) to config.pages;updateOverlay()
            if(index<config.pages-1){val ok=SwipeAccessibilityService.instance?.swipe(w,h,config)==true;if(!ok)throw IllegalStateException("스와이프 동작을 수행하지 못했습니다. 접근성 서비스를 확인해 주세요.");delay(config.delayMs)}
        };running.set(false);AppState.state.value=JobState.Processing;cleanupProjection();hideOverlay();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();AppState.message.value="촬영이 끝났습니다. 앱에서 결과를 검토해 주세요."}
        catch(t:Throwable){finish(true,t.message?:"촬영 중 오류가 발생했습니다.")}}
    private suspend fun capture(index:Int):File=withContext(Dispatchers.IO){var image:android.media.Image?=null;var tries=0;while(image==null&&tries++<30){image=reader?.acquireLatestImage();if(image==null)delay(50)};val img=image?:error("화면 이미지를 가져오지 못했습니다. 보호된 화면은 캡처할 수 없습니다.");try{val plane=img.planes[0];val row=plane.rowStride;val pixel=plane.pixelStride;val width=img.width;val bitmap=android.graphics.Bitmap.createBitmap(row/pixel,img.height,android.graphics.Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(plane.buffer);val cropped=android.graphics.Bitmap.createBitmap(bitmap,0,0,width,img.height);bitmap.recycle();val dir=File(filesDir,"captures").apply{mkdirs()};File(dir,"${System.currentTimeMillis()}_$index.png").also{f->FileOutputStream(f).use{cropped.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};cropped.recycle()}}finally{img.close()}}
    private fun showOverlay() {
        fun controllerButton(label: String, action: String) = Button(this).apply {
            text = label
            setOnClickListener {
                startService(Intent(this@CaptureService, CaptureService::class.java).setAction(action))
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(12, 8, 12, 8)
        }
        box.addView(TextView(this).apply {
            id = 42
            setTextColor(android.graphics.Color.WHITE)
            text = "0/${config.pages}"
        })
        box.addView(controllerButton("시작", ACTION_START))
        box.addView(controllerButton("일시정지", ACTION_PAUSE))
        box.addView(controllerButton("재개", ACTION_RESUME))
        box.addView(controllerButton("중지", ACTION_STOP))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 200 }
        box.setOnTouchListener(DragTouch(wm, box, lp))
        wm.addView(box, lp)
        overlay = box
    }
    private fun showStopChoice(){val dialog=AlertDialog.Builder(this).setTitle("촬영을 중지할까요?").setMessage("지금까지 촬영한 이미지를 검토하거나 모두 폐기할 수 있습니다.").setPositiveButton("촬영본 검토"){_,_->finish(false,"작업을 중지했습니다. 촬영된 이미지는 검토할 수 있습니다.")}.setNegativeButton("전체 폐기"){_,_->startService(Intent(this,CaptureService::class.java).setAction(ACTION_DISCARD))}.setNeutralButton("계속 촬영",null).create();dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show()}
    private fun updateOverlay(){overlay?.findViewById<TextView>(42)?.text="${AppState.progress.value.first}/${config.pages}"}
    private fun hideOverlay(){overlay?.let{runCatching{wm.removeView(it)}};overlay=null}
    private fun finish(error:Boolean,msg:String){running.set(false);job?.cancel();cleanupProjection();hideOverlay();AppState.state.value=if(error)JobState.Error else if(AppState.captures.value.isEmpty())JobState.Idle else JobState.Processing;AppState.message.value=msg;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun cleanupProjection(){display?.release();display=null;reader?.close();reader=null;projection?.stop();projection=null}
    override fun onDestroy(){running.set(false);job?.cancel();cleanupProjection();hideOverlay();scope.cancel();super.onDestroy()}
    private fun createChannel(){(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("capture","화면 캡처",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(s:String)=NotificationCompat.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Swipe2PDF 화면 캡처").setContentText(s).setOngoing(true).build()
}
class DragTouch(private val wm:WindowManager,private val view:View,private val lp:WindowManager.LayoutParams):View.OnTouchListener{private var sx=0f;private var sy=0f;private var ox=0;private var oy=0;override fun onTouch(v:View,e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;ox=lp.x;oy=lp.y};MotionEvent.ACTION_MOVE->{lp.x=ox-(e.rawX-sx).toInt();lp.y=oy+(e.rawY-sy).toInt();wm.updateViewLayout(view,lp)}};return e.action!=MotionEvent.ACTION_UP}}
object ConfigCodec{fun encode(c:JobConfig)="${c.pages},${c.startX},${c.endX},${c.y},${c.gestureMs},${c.delayMs},${c.crop.left},${c.crop.top},${c.crop.right},${c.crop.bottom},${c.filename.replace(",","_")}";fun decode(s:String?):JobConfig=runCatching{val p=s!!.split(",");JobConfig(p[0].toInt(),p[1].toFloat(),p[2].toFloat(),p[3].toFloat(),p[4].toLong(),p[5].toLong(),NormalizedRect(p[6].toFloat(),p[7].toFloat(),p[8].toFloat(),p[9].toFloat()),p.drop(10).joinToString(","))}.getOrDefault(JobConfig())}
