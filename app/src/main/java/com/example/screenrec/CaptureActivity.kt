package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимая активность: её запускает плавающая кнопка, чтобы показать системный запрос
 * на запись экрана (из сервиса его показать нельзя). После ответа сразу закрывается,
 * и пользователь остаётся в своём приложении или игре.
 */
class CaptureActivity : Activity() {

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_CAPTURE = 2
    }

    private var audioGranted = false
    private var handedOver = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RecorderService.isRecording) {
            finish()
            return
        }

        val need = ArrayList<String>()
        if (wantsAudio() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
        } else {
            requestCapture()
        }
    }

    private fun wantsAudio() = AppSettings.prefs(this).getBoolean("audio", true)

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) requestCapture()
    }

    private fun requestCapture() {
        audioGranted = wantsAudio() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                startForegroundService(AppSettings.startIntent(this, resultCode, data, audioGranted))
                // Дальше флаг «ждём запуск» сбросит сам RecorderService (успех или ошибка).
                handedOver = true
            } catch (e: Throwable) {
                Toast.makeText(this, "Не удалось начать запись: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!handedOver) {
            // Отмена или отказ — возвращаем плавающую кнопку на экран.
            OverlayService.awaitingStart = false
            RecorderService.dispatchState()
        }
        super.onDestroy()
    }
}
