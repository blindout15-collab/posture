package com.blindout.postureguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 알림 채널, 헤드업 알림, 다른 앱 위에 뜨는 배너, 진동을 맡는다. */
class Alerter(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        const val CHANNEL_RUNNING = "running"
        const val CHANNEL_ALERT = "alerts"
        private const val NOTIF_ALERT = 2
        private const val OVERLAY_MS = 5000L

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "측정 중 표시", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "자세를 지켜보는 동안 떠 있는 알림"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "자세 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "자세가 나쁘거나 화면과 너무 가까울 때 보내는 알림"
                    enableVibration(false) // 진동은 설정에 따라 직접 처리한다.
                },
            )
        }

        fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var overlayView: TextView? = null
    private val hideOverlay = Runnable { removeOverlay() }

    fun alert(message: String, info: Boolean = false) {
        notify(message)
        if (prefs.vibrate) vibrate(info)
        if (prefs.overlay && Settings.canDrawOverlays(ctx)) main.post { showOverlay(message, info) }
    }

    fun release() {
        main.post {
            main.removeCallbacks(hideOverlay)
            removeOverlay()
        }
    }

    private fun notify(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_posture)
            .setContentTitle("자세 지킴이")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(openAppIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ALERT, n)
    }

    private fun vibrate(info: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = if (info) longArrayOf(0, 120) else longArrayOf(0, 200, 120, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun showOverlay(message: String, info: Boolean) {
        val color = ContextCompat.getColor(ctx, if (info) R.color.accent else R.color.alert)
        val view = overlayView ?: TextView(ctx).also { tv ->
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setPadding(dp(20), dp(14), dp(20), dp(14))
            tv.elevation = dp(6).toFloat()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(56)
            }
            try {
                wm.addView(tv, params)
            } catch (e: RuntimeException) {
                return
            }
            overlayView = tv
        }
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
        view.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
        view.text = message
        view.visibility = View.VISIBLE
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (_: RuntimeException) {
            }
        }
        overlayView = null
    }
}
