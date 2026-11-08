package com.example.screenrec

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Настройки записи. Одни и те же значения использует и кнопка в приложении,
 * и плавающая кнопка (через CaptureActivity).
 */
object AppSettings {

    val RES_LABELS = arrayOf("Родное разрешение экрана", "До 2160p (4K)", "До 1440p", "До 1080p")
    val RES_VALUES = intArrayOf(Int.MAX_VALUE, 2160, 1440, 1080)

    val FPS_LABELS = arrayOf("60 кадров/с", "30 кадров/с")
    val FPS_VALUES = intArrayOf(60, 30)

    val CODEC_LABELS = arrayOf("H.265 / HEVC (лучшее сжатие)", "H.264 (максимальная совместимость)")

    val QUALITY_LABELS = arrayOf("Стандартное", "Высокое", "Максимальное")

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Intent для запуска RecorderService с текущими настройками. */
    fun startIntent(ctx: Context, resultCode: Int, data: Intent, audio: Boolean): Intent {
        val p = prefs(ctx)
        return Intent(ctx, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RecorderService.EXTRA_DATA, data)
            .putExtra(
                RecorderService.EXTRA_MAX_SHORT,
                RES_VALUES[p.getInt("res", 0).coerceIn(0, RES_VALUES.lastIndex)]
            )
            .putExtra(
                RecorderService.EXTRA_FPS,
                FPS_VALUES[p.getInt("fps", 0).coerceIn(0, FPS_VALUES.lastIndex)]
            )
            .putExtra(RecorderService.EXTRA_HEVC, p.getInt("codec", 0) == 0)
            .putExtra(RecorderService.EXTRA_QUALITY, p.getInt("quality", 1).coerceIn(0, 2))
            .putExtra(RecorderService.EXTRA_AUDIO, audio)
    }
}
