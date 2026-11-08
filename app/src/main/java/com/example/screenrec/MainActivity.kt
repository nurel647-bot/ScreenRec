package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        const val REQ_NOTIFICATIONS = 3
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spCodec: Spinner
    private lateinit var spQuality: Spinner
    private lateinit var swAudio: Switch
    private lateinit var swOverlay: Switch
    private lateinit var btn: Button
    private lateinit var status: TextView

    private var audioGranted = false
    private var updatingSwitch = false
    private var pendingOverlay = false

    private val stateListener: () -> Unit = { runOnUiThread { refreshUi() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppSettings.prefs(this)

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

        spRes = addSpinner(root, "Разрешение", "res", AppSettings.RES_LABELS, 0)
        spFps = addSpinner(root, "Частота кадров", "fps", AppSettings.FPS_LABELS, 0)
        spCodec = addSpinner(root, "Видеокодек", "codec", AppSettings.CODEC_LABELS, 0)
        spQuality = addSpinner(root, "Качество (битрейт)", "quality", AppSettings.QUALITY_LABELS, 1)

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

        swOverlay = Switch(this).apply {
            text = "Плавающая кнопка поверх приложений"
            textSize = 16f
            setPadding(0, dp(20), 0, dp(4))
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSwitch) onOverlayToggled(checked)
            }
        }
        root.addView(swOverlay)
        root.addView(TextView(this).apply {
            text = "Кнопку можно перетаскивать. Нажми её в любом приложении или игре — запись начнётся, " +
                "а кнопка исчезнет. В правом верхнем углу появится невидимая зона: " +
                "касание — пауза / продолжить, долгое удержание — остановить запись."
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
        RecorderService.addListener(stateListener)
        if (pendingOverlay) {
            pendingOverlay = false
            if (Settings.canDrawOverlays(this)) {
                startOverlay()
            } else {
                Toast.makeText(this, "Разрешение не выдано — кнопка не включена", Toast.LENGTH_LONG).show()
            }
        }
        refreshUi()
    }

    override fun onPause() {
        RecorderService.removeListener(stateListener)
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
        status.text = when {
            rec && RecorderService.isPaused -> "Запись на паузе. Продолжить можно из шторки уведомлений."
            rec -> "Идёт запись. Остановить можно здесь или из шторки уведомлений."
            else -> "Готово к записи. Видео сохраняются в папку Movies/ScreenRec."
        }

        // Состояние переключателя берём из реально работающего сервиса.
        updatingSwitch = true
        swOverlay.isChecked = OverlayService.isRunning
        updatingSwitch = false
    }

    // ----------------------------------------------------- плавающая кнопка

    private fun onOverlayToggled(enable: Boolean) {
        if (enable) {
            if (Settings.canDrawOverlays(this)) {
                startOverlay()
            } else {
                pendingOverlay = true
                Toast.makeText(this, "Включи «Поверх других приложений» для ScreenRec", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        } else {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP_OVERLAY))
        }
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    // -------------------------------------------------------------- запись

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
        startForegroundService(AppSettings.startIntent(this, resultCode, data, audioGranted))
        // Сворачиваемся, чтобы пользователь сразу видел свой экран.
        moveTaskToBack(true)
    }
}
