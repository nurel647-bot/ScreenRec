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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

/** Foreground-сервис: держит запись живой, пока приложение свёрнуто. */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenrec.START"
        const val ACTION_STOP = "com.example.screenrec.STOP"
        const val ACTION_PAUSE = "com.example.screenrec.PAUSE"
        const val ACTION_RESUME = "com.example.screenrec.RESUME"
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

        @Volatile
        var isPaused = false

        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        fun addListener(l: () -> Unit) {
            listeners.addIfAbsent(l)
        }

        fun removeListener(l: () -> Unit) {
            listeners.remove(l)
        }

        /** Сообщает всем слушателям (в главном потоке) об изменении состояния записи. */
        fun dispatchState() {
            Handler(Looper.getMainLooper()).post {
                for (l in listeners) l()
            }
        }
    }

    private var recorder: ScreenRecorder? = null
    private var description = ""
    private var elapsedBeforeMs = 0L
    private var segmentStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> {
                val r = recorder
                if (r != null) r.stop() else stopSelf()
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return

        createChannel()
        description = "Подготовка записи…"
        // На Android 14+ startForeground нужно вызвать до getMediaProjection().
        startForeground(
            NOTIF_ID,
            buildNotification(),
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

            description = rec.start()
            elapsedBeforeMs = 0L
            segmentStartMs = System.currentTimeMillis()
            isPaused = false
            isRecording = true
            updateNotification()
            dispatchState()
        } catch (e: Throwable) {
            Log.e(TAG, "cannot start recording", e)
            Toast.makeText(this, "Не удалось начать запись: ${e.message}", Toast.LENGTH_LONG).show()
            recorder = null
            isRecording = false
            isPaused = false
            OverlayService.awaitingStart = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            dispatchState()
        }
    }

    private fun setPaused(pause: Boolean) {
        val r = recorder
        if (r == null || !isRecording) {
            if (r == null) stopSelf()
            return
        }
        if (pause == isPaused) return

        val now = System.currentTimeMillis()
        if (pause) {
            r.pause()
            elapsedBeforeMs += now - segmentStartMs
        } else {
            r.resume()
            segmentStartMs = now
        }
        isPaused = pause
        updateNotification()
        dispatchState()
    }

    private fun onRecordingFinished(uri: Uri?) {
        recorder = null
        isRecording = false
        isPaused = false
        OverlayService.awaitingStart = false
        val msg = if (uri != null) "Запись сохранена: Movies/ScreenRec" else "Запись не сохранена"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        dispatchState()
    }

    override fun onDestroy() {
        // Если система убила сервис во время записи — постараемся закрыть файл.
        recorder?.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------- уведомление

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Запись экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun action(title: String, iconRes: Int, act: String, requestCode: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, requestCode,
            Intent(this, RecorderService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, pi).build()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setContentTitle(if (isPaused) "Запись на паузе" else "Идёт запись экрана")
            .setContentText(description)
            .setContentIntent(openIntent)
            .setOngoing(true)

        if (isRecording) {
            if (isPaused) {
                b.addAction(action("Продолжить", android.R.drawable.ic_media_play, ACTION_RESUME, 10))
            } else {
                b.addAction(action("Пауза", android.R.drawable.ic_media_pause, ACTION_PAUSE, 11))
            }
        }
        b.addAction(action("Остановить", android.R.drawable.ic_menu_close_clear_cancel, ACTION_STOP, 12))

        if (isRecording && !isPaused) {
            val now = System.currentTimeMillis()
            val elapsed = elapsedBeforeMs + (now - segmentStartMs)
            b.setUsesChronometer(true).setWhen(now - elapsed)
        }
        return b.build()
    }
}
