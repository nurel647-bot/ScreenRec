package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_CAPTURE = 2

        val RES_LABELS = arrayOf("Родное разрешение экрана", "До 2160p (4K)", "До 1440p", "До 1080p")
        val RES_VALUES = intArrayOf(Int.MAX_VALUE, 2160, 1440, 1080)
        val FPS_LABELS = arrayOf("60 кадров/с", "30 кадров/с")
        val FPS_VALUES = intArrayOf(60, 30)
        val CODEC_LABELS = arrayOf("H.265 / HEVC (лучшее сжатие)", "H.264 (максимальная совместимость)")
        val QUALITY_LABELS = arrayOf("Стандартное", "Высокое", "Максимальное")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spCodec: Spinner
    private lateinit var spQuality: Spinner
    private lateinit var swAudio: Switch
    private lateinit var btn: Button
    private lateinit var status: TextView

    private var audioGranted = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Запись экрана"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Аппаратное кодирование: качество высокое, нагрузка на процессор минимальна."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(16))
        })

        spRes = addSpinner(root, "Разрешение", "res", RES_LABELS, 0)
        spFps = addSpinner(root, "Частота кадров", "fps", FPS_LABELS, 0)
        spCodec = addSpinner(root, "Видеокодек", "codec", CODEC_LABELS, 0)
        spQuality = addSpinner(root, "Качество (битрейт)", "quality", QUALITY_LABELS, 1)

        swAudio = Switch(this).apply {
            text = "Записывать системный звук"
            textSize = 16f
            isChecked = prefs.getBoolean("audio", true)
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("audio", checked).apply() }
        }
        root.addView(swAudio)
        root.addView(TextView(this).apply {
            text = "Только звук, который играет на устройстве. Микрофон не записывается."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        btn = Button(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setOnClickListener { onButtonClicked() }
        }
        root.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(28) })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(status)
    }

    private fun addSpinner(
        root: LinearLayout, title: String, key: String, labels: Array<String>, default: Int
    ): Spinner {
        root.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, 0)
        })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(prefs.getInt(key, default))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(key, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(sp)
        return sp
    }

    override fun onResume() {
        super.onResume()
        RecorderService.onStateChanged = { runOnUiThread { refreshUi() } }
        refreshUi()
    }

    override fun onPause() {
        RecorderService.onStateChanged = null
        super.onPause()
    }

    private fun refreshUi() {
        val rec = RecorderService.isRecording
        btn.text = if (rec) "■  Остановить запись" else "●  Начать запись"
        btn.setBackgroundColor(Color.parseColor(if (rec) "#546E7A" else "#E53935"))
        spRes.isEnabled = !rec
        spFps.isEnabled = !rec
        spCodec.isEnabled = !rec
        spQuality.isEnabled = !rec
        swAudio.isEnabled = !rec
        status.text = if (rec) {
            "Идёт запись. Остановить можно здесь или из шторки уведомлений."
        } else {
            "Готово к записи. Видео сохраняются в папку Movies/ScreenRec."
        }
    }

    private fun onButtonClicked() {
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            return
        }
        val need = ArrayList<String>()
        if (swAudio.isChecked &&
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

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (swAudio.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Нет доступа к звуку — запись будет без звука", Toast.LENGTH_LONG).show()
        }
        requestCapture()
    }

    private fun requestCapture() {
        audioGranted = swAudio.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Запись отменена", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RecorderService.EXTRA_DATA, data)
            .putExtra(RecorderService.EXTRA_MAX_SHORT, RES_VALUES[spRes.selectedItemPosition])
            .putExtra(RecorderService.EXTRA_FPS, FPS_VALUES[spFps.selectedItemPosition])
            .putExtra(RecorderService.EXTRA_HEVC, spCodec.selectedItemPosition == 0)
            .putExtra(RecorderService.EXTRA_QUALITY, spQuality.selectedItemPosition)
            .putExtra(RecorderService.EXTRA_AUDIO, audioGranted)
        startForegroundService(intent)
        // Сворачиваемся, чтобы пользователь сразу видел свой экран.
        moveTaskToBack(true)
    }
}
