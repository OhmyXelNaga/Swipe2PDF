package com.example.swipe2pdf

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.*

/** 수백 장 PDF를 만드는 동안 앱이 백그라운드로 가도 시스템이 종료하지 않도록 붙잡아 두는 서비스. */
class PdfService : Service() {
    companion object {
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL = "pdf"
        private const val NOTIFICATION_ID = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        getSystemService(NotificationManager::class.java)!!
            .createNotificationChannel(NotificationChannel(CHANNEL, "PDF 만들기", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(AppState.pdfProgress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        val config = ConfigCodec.decode(intent?.getStringExtra(EXTRA_CONFIG))
        job = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)!!
            val updates = launch {
                AppState.pdfProgress.collect {
                    manager.notify(NOTIFICATION_ID, notification(it))
                    delay(500)
                }
            }
            try {
                AppState.runPdf(applicationContext, config.crop)
            } finally {
                updates.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(progress: Pair<Int, Int>?): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val (done, total) = progress ?: (0 to 0)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PDF 만드는 중")
            .setContentText(if (total > 0) "$done/${total}장" else "준비 중")
            .setProgress(total, done, total == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
