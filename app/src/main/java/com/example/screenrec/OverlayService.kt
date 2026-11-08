package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Плавающие элементы поверх других приложений.
 *
 *  - Пока записи нет: круглая кнопка «●» (её можно перетаскивать). Нажатие запускает запись.
 *  - Во время записи кнопка исчезает, а в правом верхнем углу появляется НЕВИДИМАЯ зона:
 *    короткое касание = пауза / продолжить, долгое удержание = остановить запись.
 *  - На паузе экран не записывается, поэтому в углу показывается значок паузы (в видео он не попадает).
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP_OVERLAY = "com.example.screenrec.STOP_OVERLAY"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 2

        @Volatile
        var isRunning = false

        /** Кнопка нажата и мы ждём системное разрешение — кнопку не показываем. */
        @Volatile
        var awaitingStart = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var startButton: View? = null
    private var corner: FrameLayout? = null
    private var badge: TextView? = null

    private val stateListener: () -> Unit = { updateWindows() }
    private val showBadge = Runnable { badge?.visibility = View.VISIBLE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            RecorderService.addListener(stateListener)
        }
        updateWindows()
        RecorderService.dispatchState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RecorderService.removeListener(stateListener)
        main.removeCallbacksAndMessages(null)
        removeStartButton()
        removeCorner()
        isRunning = false
        awaitingStart = false
        RecorderService.dispatchState()
        super.onDestroy()
    }

    // ------------------------------------------------------------ окна

    private fun updateWindows() {
        if (RecorderService.isRecording) {
            awaitingStart = false
            removeStartButton()
            showCorner()
            updateBadge()
        } else {
            removeCorner()
            if (awaitingStart) removeStartButton() else showStartButton()
        }
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    )

    private fun showStartButton() {
        if (startButton != null) return
        val prefs = AppSettings.prefs(this)
        val lp = overlayParams(dp(52), dp(52), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ov_x", dp(4))
            y = prefs.getInt("ov_y", dp(220))
        }
        val v = TextView(this).apply {
            text = "●"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(215, 229, 57, 53))
            }
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        try { wm.updateViewLayout(view, lp) } catch (e: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("ov_x", lp.x).putInt("ov_y", lp.y).apply()
                    } else {
                        onStartTapped()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        startButton = v
    }

    private fun onStartTapped() {
        if (RecorderService.isRecording) return
        awaitingStart = true
        try {
            // Активность запускаем, пока кнопка ещё на экране (так Android разрешает запуск из фона).
            startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            awaitingStart = false
            Toast.makeText(this, "Не удалось открыть запрос на запись", Toast.LENGTH_LONG).show()
            return
        }
        main.post { removeStartButton() }
    }

    private fun showCorner() {
        if (corner != null) return
        val root = FrameLayout(this)
        root.isClickable = true
        root.isLongClickable = true
        root.setOnClickListener { onCornerTap() }
        root.setOnLongClickListener {
            onCornerLongPress()
            true
        }

        val b = TextView(this).apply {
            text = "❚❚"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 0, 0, 0))
            }
            visibility = View.GONE
        }
        root.addView(b, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

        val lp = overlayParams(
            dp(72), dp(72),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(root, lp)
        corner = root
        badge = b
    }

    private fun onCornerTap() {
        haptic(25)
        if (RecorderService.isPaused) {
            // Сначала прячем значок, потом возобновляем — чтобы он не попал в видео.
            main.removeCallbacks(showBadge)
            badge?.visibility = View.GONE
            main.postDelayed({ sendToRecorder(RecorderService.ACTION_RESUME) }, 200)
        } else {
            sendToRecorder(RecorderService.ACTION_PAUSE)
        }
    }

    private fun onCornerLongPress() {
        haptic(70)
        main.removeCallbacks(showBadge)
        badge?.visibility = View.GONE
        sendToRecorder(RecorderService.ACTION_STOP)
    }

    /** Значок паузы показываем с небольшой задержкой: к этому моменту запись уже остановлена. */
    private fun updateBadge() {
        main.removeCallbacks(showBadge)
        if (RecorderService.isPaused) {
            main.postDelayed(showBadge, 300)
        } else {
            badge?.visibility = View.GONE
        }
    }

    private fun sendToRecorder(action: String) {
        try {
            startService(Intent(this, RecorderService::class.java).setAction(action))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось отправить команду записи", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeStartButton() {
        startButton?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        startButton = null
    }

    private fun removeCorner() {
        main.removeCallbacks(showBadge)
        corner?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        corner = null
        badge = null
    }

    @Suppress("DEPRECATION")
    private fun haptic(ms: Long) {
        try {
            corner?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (e: Throwable) { }
        try {
            val vib = getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) { }
    }

    // ---------------------------------------------------------- уведомление

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Плавающая кнопка", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val offIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Плавающая кнопка записи включена")
            .setContentText("Нажми на кнопку поверх любого приложения, чтобы начать запись")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Выключить",
                    offIntent
                ).build()
            )
            .build()
    }
}
