package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast

/** Foreground-сервис: держит запись живой, пока приложение свёрнуто. */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenrec.START"
        const val ACTION_STOP = "com.example.screenrec.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MAX_SHORT = "maxShort"
        const val EXTRA_FPS = "fps"
        const val EXTRA_HEVC = "hevc"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_AUDIO = "audio"

        private const val CHANNEL_ID = "screenrec"
        private const val NOTIF_ID = 1
        private const val TAG = "ScreenRec"

        @Volatile
        var isRecording = false

        /** Вызывается в главном потоке при смене состояния записи. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: ScreenRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> recorder?.stop() ?: stopSelf()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return

        createChannel()
        // На Android 14+ startForeground нужно вызвать до getMediaProjection().
        startForeground(
            NOTIF_ID,
            buildNotification("Подготовка записи…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) error("Нет разрешения на запись экрана")

            val cfg = RecConfig(
                maxShortSide = intent.getIntExtra(EXTRA_MAX_SHORT, Int.MAX_VALUE),
                fps = intent.getIntExtra(EXTRA_FPS, 60),
                preferHevc = intent.getBooleanExtra(EXTRA_HEVC, true),
                quality = intent.getIntExtra(EXTRA_QUALITY, 1),
                audio = intent.getBooleanExtra(EXTRA_AUDIO, true)
            )

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(resultCode, data)

            val rec = ScreenRecorder(applicationContext, projection, cfg)
            rec.onFinished = { uri -> onRecordingFinished(uri) }
            recorder = rec

            val description = rec.start()
            isRecording = true
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(description))
            onStateChanged?.invoke()
        } catch (e: Throwable) {
            Log.e(TAG, "cannot start recording", e)
            Toast.makeText(this, "Не удалось начать запись: ${e.message}", Toast.LENGTH_LONG).show()
            recorder = null
            isRecording = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            onStateChanged?.invoke()
        }
    }

    private fun onRecordingFinished(uri: android.net.Uri?) {
        recorder = null
        isRecording = false
        val msg = if (uri != null) "Запись сохранена: Movies/ScreenRec" else "Запись не сохранена"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStateChanged?.invoke()
    }

    override fun onDestroy() {
        // Если система убила сервис во время записи — постараемся закрыть файл.
        recorder?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Запись экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Остановить",
            stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Идёт запись экрана")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .addAction(stopAction)
            .build()
    }
}
