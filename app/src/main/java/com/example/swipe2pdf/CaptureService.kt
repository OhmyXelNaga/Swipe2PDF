package com.example.swipe2pdf

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7
        private const val OVERLAY_HIDE_MS = 180L
        private const val FRAME_TIMEOUT_MS = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)
    private val loopStarted = AtomicBoolean(false)
    @Volatile private var paused = false
    private var finishing = false
    private var config = JobConfig()
    private var jobStartMs = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlay: View? = null
    private var overlayText: TextView? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var loopJob: Job? = null
    private var pendingSave: Deferred<Unit>? = null
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val display = getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = windowContext.getSystemService(WindowManager::class.java)!!
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prepare(intent)
        return START_NOT_STICKY
    }

    private fun prepare(intent: Intent?) {
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        // startForegroundService() 호출마다 제한 시간 안에 포그라운드 전환이 필요하다.
        val foreground = data != null && runCatching {
            startForeground(NOTIFICATION_ID, notification("준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }.isSuccess
        if (!running.compareAndSet(false, true)) return
        config = ConfigCodec.decode(intent?.getStringExtra(EXTRA_CONFIG))
        if (!foreground || !Settings.canDrawOverlays(this) || SwipeAccessibilityService.instance == null || !AppState.moveTo(JobState.Preparing)) {
            running.set(false)
            finishing = true
            AppState.message.value = "필수 권한 또는 접근성 서비스가 준비되지 않았습니다."
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            val mpm = getSystemService(MediaProjectionManager::class.java)!!
            val p = mpm.getMediaProjection(resultCode, data!!) ?: error("화면 캡처 세션을 시작하지 못했습니다.")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (running.get()) finish(true, "화면 캡처 세션이 종료되었습니다. 다시 동의해 주세요.")
                }
            }, Handler(Looper.getMainLooper()))
            val r = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader = r
            virtualDisplay = p.createVirtualDisplay(
                "Swipe2PDF", screenWidth, screenHeight, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            AppState.progress.value = 0 to config.pages
            showOverlay()
            AppState.message.value = "대상 앱으로 이동한 뒤 플로팅 컨트롤러의 시작을 누르세요."
        } catch (t: Throwable) {
            finish(true, t.message ?: "화면 캡처를 준비하지 못했습니다.")
        }
    }

    private fun startLoop() {
        if (!running.get() || finishing || !loopStarted.compareAndSet(false, true)) return
        if (!AppState.moveTo(JobState.Capturing)) {
            loopStarted.set(false)
            return
        }
        jobStartMs = System.currentTimeMillis()
        startButton?.isEnabled = false
        pauseButton?.isEnabled = true
        loopJob = scope.launch { runLoop() }
    }

    private fun setPaused(value: Boolean) {
        if (!loopStarted.get() || finishing) return
        if (!AppState.moveTo(if (value) JobState.Paused else JobState.Capturing)) return
        paused = value
        updateOverlay()
    }

    private suspend fun runLoop() {
        try {
            for (index in 0 until config.pages) {
                while (paused) delay(100)
                checkScreenUnchanged()
                val bitmap = grabFrame()
                AppState.progress.value = (index + 1) to config.pages
                updateOverlay()
                saveInBackground(bitmap, index)
                if (index < config.pages - 1) {
                    val ok = SwipeAccessibilityService.instance?.swipe(screenWidth, screenHeight, config) == true
                    if (!ok) throw IllegalStateException("스와이프 동작을 수행하지 못했습니다. 접근성 서비스를 확인해 주세요.")
                    delay(config.delayMs)
                }
            }
            pendingSave?.await()
            finish(false, "촬영이 끝났습니다. 앱에서 결과를 검토해 주세요.")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            finish(true, t.message ?: "촬영 중 오류가 발생했습니다.")
        }
    }

    private fun checkScreenUnchanged() {
        val bounds = wm.maximumWindowMetrics.bounds
        if (bounds.width() != screenWidth || bounds.height() != screenHeight) {
            throw IllegalStateException("기기 회전·접기 상태 또는 화면 크기가 바뀌었습니다. 촬영본을 보존했으니 작업을 다시 시작해 주세요.")
        }
    }

    /** 컨트롤러를 숨긴 뒤 새로 그려진 프레임만 사용한다. 이전 프레임은 먼저 버린다. */
    private suspend fun grabFrame(): Bitmap {
        val r = reader ?: error("화면 캡처 세션이 없습니다.")
        r.acquireLatestImage()?.close()
        overlay?.visibility = View.GONE
        try {
            delay(OVERLAY_HIDE_MS)
            return withContext(Dispatchers.IO) {
                val image = awaitImage(r) ?: error("화면 이미지를 가져오지 못했습니다. 보호된 화면은 캡처할 수 없습니다.")
                image.use(::toBitmap)
            }
        } finally {
            overlay?.visibility = View.VISIBLE
        }
    }

    private suspend fun awaitImage(r: ImageReader): Image? {
        val deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT_MS
        while (true) {
            r.acquireLatestImage()?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) return null
            delay(30)
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowWidth = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowWidth, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        if (rowWidth == image.width) return full
        return Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
    }

    /** PNG 인코딩을 다음 스와이프와 겹쳐 실행한다. 메모리에 남는 비트맵은 최대 2장이다. */
    private suspend fun saveInBackground(bitmap: Bitmap, index: Int) {
        pendingSave?.await()
        val dir = AppState.captureDir(filesDir)
        val file = File(dir, CaptureNaming.name(jobStartMs, index))
        pendingSave = scope.async(Dispatchers.IO) {
            val tmp = File(dir, file.name + ".tmp")
            try {
                FileOutputStream(tmp).use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("이미지를 저장하지 못했습니다.") }
                if (!tmp.renameTo(file)) error("이미지를 저장하지 못했습니다.")
                AppState.addCapture(file)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun showOverlay() {
        val ctx = windowContext
        fun controllerButton(label: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            setOnClickListener { onClick() }
        }
        val text = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 0)
        }
        val start = controllerButton("시작") { startLoop() }
        val pause = controllerButton("일시정지") { setPaused(!paused) }.apply { isEnabled = false }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(12, 8, 12, 8)
            addView(text)
            addView(start)
            addView(pause)
            addView(controllerButton("중지") { showStopChoice() })
        }
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
        overlayText = text
        startButton = start
        pauseButton = pause
        updateOverlay()
    }

    private fun updateOverlay() {
        overlayText?.text = "${AppState.progress.value.first}/${config.pages}${if (paused) " · 일시정지" else ""}"
        pauseButton?.text = if (paused) "재개" else "일시정지"
    }

    /** 결정하는 동안 촬영이 진행돼 대화상자가 찍히지 않도록 잠시 멈춘다. */
    private fun showStopChoice() {
        if (finishing) return
        val wasPaused = paused
        setPaused(true)
        val resume = { if (!wasPaused) setPaused(false) }
        val dialog = AlertDialog.Builder(windowContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("촬영을 중지할까요?")
            .setMessage("지금까지 촬영한 이미지를 검토하거나 모두 폐기할 수 있습니다.")
            .setPositiveButton("촬영본 검토") { _, _ -> finish(false, "작업을 중지했습니다. 촬영된 이미지는 검토할 수 있습니다.") }
            .setNegativeButton("전체 폐기") { _, _ -> finish(false, "작업과 촬영본을 모두 폐기했습니다.", discard = true) }
            .setNeutralButton("계속 촬영") { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        overlayText = null
        startButton = null
        pauseButton = null
    }

    /** 촬영 루프를 멈추고 진행 중인 저장을 마친 뒤 자원을 정리한다. */
    private fun finish(error: Boolean, msg: String, discard: Boolean = false) {
        if (finishing) return
        finishing = true
        running.set(false)
        scope.launch {
            loopJob?.cancelAndJoin()
            runCatching { pendingSave?.await() }
            if (discard) AppState.discardCaptures()
            cleanupProjection()
            hideOverlay()
            AppState.moveTo(
                when {
                    error -> JobState.Error
                    AppState.captures.value.isEmpty() -> JobState.Idle
                    else -> JobState.Reviewing
                }
            )
            AppState.message.value = msg
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        cleanupProjection()
        hideOverlay()
        // 시스템이 서비스를 먼저 종료한 경우 화면이 촬영 중 상태에 머물지 않게 한다.
        if (!finishing) AppState.moveTo(if (AppState.captures.value.isEmpty()) JobState.Idle else JobState.Reviewing)
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)!!
            .createNotificationChannel(NotificationChannel(CHANNEL, "화면 캡처", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Swipe2PDF 화면 캡처")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

class DragTouch(private val wm: WindowManager, private val view: View, private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
    private var sx = 0f
    private var sy = 0f
    private var ox = 0
    private var oy = 0

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
            MotionEvent.ACTION_MOVE -> {
                lp.x = ox - (e.rawX - sx).toInt()
                lp.y = oy + (e.rawY - sy).toInt()
                wm.updateViewLayout(view, lp)
            }
        }
        return e.action != MotionEvent.ACTION_UP
    }
}
