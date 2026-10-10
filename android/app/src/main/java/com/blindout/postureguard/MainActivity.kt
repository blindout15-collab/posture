package com.blindout.postureguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startMonitoring()
        } else {
            Toast.makeText(this, "카메라 권한이 있어야 자세를 볼 수 있어요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Alerter.createChannels(this)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (PostureState.ui.value.running) {
                startService(PostureService.intent(this, PostureService.ACTION_STOP))
            } else {
                requestPermissionsAndStart()
            }
        }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener {
            startService(PostureService.intent(this, PostureService.ACTION_CALIBRATE))
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            try {
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        bindSettings()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PostureState.ui.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.overlayButton).text =
            if (overlayOk) "✓ 다른 앱 위에 표시 허용됨" else "다른 앱 위에 알림 띄우기 허용"
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<Button>(R.id.batteryButton).text =
            if (batteryOk) "✓ 배터리 최적화 제외됨" else "배터리 최적화에서 제외 (꺼지지 않게)"
    }

    private fun requestPermissionsAndStart() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startMonitoring() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, PostureService.intent(this, PostureService.ACTION_START))
    }

    private fun render(s: UiState) {
        findViewById<TextView>(R.id.statusLabel).text = s.label
        findViewById<TextView>(R.id.statusDetail).text = s.detail
        val (bg, fg) = when (s.level) {
            Level.GOOD -> R.color.good_bg to R.color.good
            Level.WARN -> R.color.warn_bg to R.color.warn
            Level.BAD -> R.color.bad_bg to R.color.bad
            Level.IDLE -> R.color.idle_bg to R.color.text
        }
        findViewById<android.view.View>(R.id.statusCard).setBackgroundColor(ContextCompat.getColor(this, bg))
        findViewById<TextView>(R.id.statusLabel).setTextColor(ContextCompat.getColor(this, fg))

        val ev = s.evaluation
        findViewById<TextView>(R.id.metricDistance).text = ev?.let { "${it.distanceCm.roundToInt()}cm" } ?: "–"
        findViewById<TextView>(R.id.metricPitch).text = ev?.let { "${it.pitchDown.roundToInt()}°" } ?: "–"
        findViewById<TextView>(R.id.metricRoll).text = ev?.let { "${abs(it.roll).roundToInt()}°" } ?: "–"

        findViewById<Button>(R.id.startButton).text = if (s.running) "중지" else "시작"
        findViewById<Button>(R.id.calibrateButton).isEnabled = s.running
    }

    private fun bindSettings() {
        fun number(id: Int, get: () -> Int, set: (Int) -> Unit) {
            val field = findViewById<EditText>(id)
            field.setText(get().toString())
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = field.text.toString().toIntOrNull()
                    if (v != null && v > 0) set(v) else field.setText(get().toString())
                }
            }
        }
        fun toggle(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
            val box = findViewById<CheckBox>(id)
            box.isChecked = get()
            box.setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        number(R.id.setBaseDist, { prefs.baseDistCm }, { prefs.baseDistCm = it })
        number(R.id.setMinDist, { prefs.minDistCm }, { prefs.minDistCm = it })
        number(R.id.setPitch, { prefs.pitchTolDeg }, { prefs.pitchTolDeg = it })
        number(R.id.setRoll, { prefs.rollTolDeg }, { prefs.rollTolDeg = it })
        number(R.id.setHold, { prefs.holdSec }, { prefs.holdSec = it })
        number(R.id.setCooldown, { prefs.cooldownSec }, { prefs.cooldownSec = it })
        toggle(R.id.setOverlay, { prefs.overlay }, { prefs.overlay = it })
        toggle(R.id.setVibrate, { prefs.vibrate }, { prefs.vibrate = it })
        toggle(R.id.setBreak, { prefs.breakReminder }, { prefs.breakReminder = it })
    }

    override fun onPause() {
        // 입력하던 숫자 칸의 값도 저장되도록 포커스를 뺀다.
        currentFocus?.clearFocus()
        super.onPause()
    }
}
