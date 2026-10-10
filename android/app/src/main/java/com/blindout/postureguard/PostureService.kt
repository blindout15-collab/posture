package com.blindout.postureguard

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Size
import android.view.Display
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.min

/**
 * 전면 카메라로 얼굴을 보며 자세를 판단하는 포그라운드 서비스.
 * 앱이 화면에 없어도 계속 동작한다 (상단에 "측정 중" 알림이 떠 있는 동안).
 */
class PostureService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.blindout.postureguard.START"
        const val ACTION_STOP = "com.blindout.postureguard.STOP"
        const val ACTION_CALIBRATE = "com.blindout.postureguard.CALIBRATE"

        private const val NOTIF_RUNNING = 1
        private const val INTERVAL_MS = 500L
        private const val CALIBRATION_SAMPLES = 8
        private const val BREAK_INTERVAL_MS = 20 * 60 * 1000L

        fun intent(ctx: Context, action: String): Intent =
            Intent(ctx, PostureService::class.java).setAction(action)
    }

    private lateinit var prefs: Prefs
    private lateinit var alerter: Alerter
    private lateinit var executor: ExecutorService
    // 서비스가 끝난 뒤 늦게 도착한 얼굴 인식 결과는 버린다.
    private val resultExecutor = Executor { r ->
        try {
            executor.execute(r)
        } catch (_: RejectedExecutionException) {
        }
    }
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build(),
    )

    private var started = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // 아래 값들은 분석 스레드(executor)에서만 만진다.
    private var lastProcessedAt = 0L
    private var lastFrameAt = 0L
    private var faceSeenMs = 0L
    @Volatile private var calibrationSamples: MutableList<Sample>? = null
    private val badSince = HashMap<String, Long>()
    private val lastAlertAt = HashMap<String, Long>()
    private var lastNotifiedLabel: String? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // 태블릿을 돌리면 카메라 이미지 방향도 맞춰야 얼굴을 찾을 수 있다.
            imageAnalysis?.targetRotation = currentRotation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        alerter = Alerter(this, prefs)
        executor = Executors.newSingleThreadExecutor()
        Alerter.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopSelf()
            ACTION_CALIBRATE -> if (started) {
                calibrationSamples = mutableListOf()
                publish(Level.IDLE, "기준 자세 저장 중…", "그대로 바른 자세를 유지해 주세요.", null)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_RUNNING,
                runningNotification("측정을 시작했어요"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: RuntimeException) {
            publish(Level.BAD, "시작하지 못했어요", "앱을 연 상태에서 다시 ‘시작’을 눌러주세요.", null, running = false)
            stopSelf()
            return
        }
        started = true
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        publish(Level.IDLE, "카메라 켜는 중…", "", null)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(currentRotation())
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (state.type == CameraState.Type.PENDING_OPEN || state.error != null) {
                        publish(Level.WARN, "카메라를 쓸 수 없어요", "다른 앱이 카메라를 쓰고 있으면 끝난 뒤 다시 이어서 측정해요.", null)
                    }
                }
                cameraProvider = provider
                imageAnalysis = analysis
                val ready = prefs.baseline != null
                publish(
                    Level.IDLE,
                    if (ready) "측정 중" else "기준 자세가 필요해요",
                    if (ready) "" else "바른 자세로 앉아서 ‘기준 자세 저장’을 눌러주세요.",
                    null,
                )
            } catch (e: Exception) {
                publish(Level.BAD, "카메라를 켜지 못했어요", e.message ?: "", null, running = false)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun currentRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        if (now - lastProcessedAt < INTERVAL_MS || media == null) {
            image.close()
            return
        }
        lastProcessedAt = now
        val shortSide = min(image.width, image.height).toFloat()
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener(resultExecutor) { faces ->
                handle(faces.maxByOrNull { it.boundingBox.width() }, shortSide, now)
            }
            .addOnCompleteListener { image.close() }
    }

    private fun handle(face: Face?, shortSide: Float, now: Long) {
        val dt = if (lastFrameAt == 0L) 0L else min(now - lastFrameAt, 5000L)
        lastFrameAt = now

        if (face == null) {
            badSince.clear()
            publish(Level.IDLE, "얼굴이 안 보여요", "자리를 비웠거나 카메라 각도를 확인해 주세요.", null)
            return
        }
        val sample = Sample(face.boundingBox.width() / shortSide, face.headEulerAngleX, face.headEulerAngleZ)

        calibrationSamples?.let { samples ->
            samples += sample
            if (samples.size >= CALIBRATION_SAMPLES) {
                prefs.baseline = PostureEvaluator.average(samples)
                calibrationSamples = null
                badSince.clear()
                alerter.alert("기준 자세를 저장했어요. 이제부터 자세를 지켜볼게요.", info = true)
            }
            return
        }

        val baseline = prefs.baseline
        if (baseline == null) {
            publish(Level.IDLE, "기준 자세가 필요해요", "바른 자세로 앉아서 ‘기준 자세 저장’을 눌러주세요.", null)
            return
        }

        val ev = PostureEvaluator.evaluate(sample, baseline, prefs.thresholds)
        faceSeenMs += dt

        val active = ev.problems.map { it.key }.toSet()
        badSince.keys.retainAll(active)

        if (ev.problems.isEmpty()) {
            publish(Level.GOOD, "좋은 자세예요 👍", "지금처럼 유지해 주세요.", ev)
        } else {
            var level = Level.WARN
            for (p in ev.problems) {
                val since = badSince.getOrPut(p.key) { now }
                if (now - since >= prefs.holdSec * 1000L) {
                    level = Level.BAD
                    val last = lastAlertAt[p.key]
                    if (last == null || now - last >= prefs.cooldownSec * 1000L) {
                        lastAlertAt[p.key] = now
                        alerter.alert(p.message)
                    }
                }
            }
            publish(
                level,
                if (level == Level.BAD) "자세를 고쳐주세요" else "자세가 흐트러지고 있어요",
                ev.problems.first().message,
                ev,
            )
        }

        if (prefs.breakReminder && faceSeenMs >= BREAK_INTERVAL_MS) {
            faceSeenMs = 0
            alerter.alert("20분이 지났어요. 20초 동안 6m 이상 먼 곳을 바라보며 눈을 쉬어주세요.", info = true)
        }
    }

    private fun publish(level: Level, label: String, detail: String, ev: Evaluation?, running: Boolean = true) {
        PostureState.ui.update { UiState(running, level, label, detail, ev) }
        if (running && started && label != lastNotifiedLabel) {
            lastNotifiedLabel = label
            getSystemService(NotificationManager::class.java).notify(NOTIF_RUNNING, runningNotification(label))
        }
    }

    private fun runningNotification(text: String) =
        NotificationCompat.Builder(this, Alerter.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_posture)
            .setContentTitle("자세 지킴이 측정 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Alerter.openAppIntent(this))
            .addAction(
                0,
                "중지",
                PendingIntent.getService(
                    this,
                    1,
                    intent(this, ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    override fun onDestroy() {
        if (started) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        }
        cameraProvider?.unbindAll()
        detector.close()
        executor.shutdown()
        alerter.release()
        if (PostureState.ui.value.running) {
            PostureState.ui.value = UiState(
                running = false,
                label = "멈춤",
                detail = "다시 시작하려면 ‘시작’을 눌러주세요.",
            )
        }
        super.onDestroy()
    }
}
