package app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import app.data.SessionResultWriter
import rehabcore.overlay.CalfOverlay
import rehabcore.overlay.HudRenderer
import rehabcore.domain.*
import rehabcore.domain.model.FrameHud
import rehabcore.domain.model.ParamValue
import rehabcore.domain.model.SessionResult
import rehabcore.mediapipe.PoseLandmarkerClient
import rehabcore.overlay.SkeletonOverlay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class DetectionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_DIAG = "extra_diag"
    }

    private lateinit var skeleton: SkeletonOverlay

    private lateinit var previewView: PreviewView
    private lateinit var overlay: View
    private val hud = HudRenderer()
    private val calfOverlay = CalfOverlay()

    private var detector: Detector? = null
    private var lastHud: FrameHud = FrameHud(null, "INIT", 0f, 0, 0)
    private var fps = 30f

    private lateinit var pose: PoseLandmarkerClient
    private var cameraProvider: ProcessCameraProvider? = null
    private var diagnostics: Diagnostics? = null


    private val reqPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) bindCamera() else finish()
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var isFront = false  // 用來告訴骨架是否要鏡像（前鏡頭）


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
// 避免黑畫面/裁切問題：
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlay = object : View(this) {
            init {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                hud.draw(canvas, lastHud)
                val act = intent.getStringExtra(EXTRA_ACTION)
                if (act == ActionType.CALF.name) calfOverlay.draw(canvas, lastHud)
            }
        }
        val btn = Button(this).apply { text = "結算" }
        btn.setOnClickListener { finishSession(root) }
        skeleton = SkeletonOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(previewView)
        root.addView(skeleton)     // ← 新增：骨架層
        root.addView(overlay)      // ← 你的 HUD 層（照舊）
        overlay.bringToFront()
        root.addView(btn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 40
            topMargin = 40 + 280
        })
        setContentView(root)


        pose = PoseLandmarkerClient(this)


        val action = intent.getStringExtra(EXTRA_ACTION) ?: ActionType.CALF.name
        val diag = intent.getBooleanExtra(EXTRA_DIAG, false)
        diagnostics = if (diag) Diagnostics(this) else null
        detector = when (ActionType.valueOf(action)) {
            ActionType.SQUAT -> SquatDetector(SquatParams(), diagnostics)
            ActionType.CALF -> CalfDetector(CalfParams(), diagnostics)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            reqPerm.launch(Manifest.permission.CAMERA)
        }
    }


    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            val displayRotation = previewView.display.rotation

            val preview = Preview.Builder()
                .setTargetRotation(displayRotation)
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))   // ★ 大幅加速
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(displayRotation)                 // ★ 跟 Preview 一致
                .build().also { a -> a.setAnalyzer(analysisExecutor) { img -> onImage(img) } }

// 若之後要加前鏡頭切換，請設定 selector 並同步 isFront
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            isFront = (selector == CameraSelector.DEFAULT_FRONT_CAMERA)

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                selector,              // ← 用上面的 selector
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }


    private fun onImage(image: ImageProxy) {
        // 千萬不要在這裡 return；一定走到 finally 關閉 image
        val tsNs = SystemClock.elapsedRealtimeNanos()
        val tsMs = tsNs / 1_000_000

        try {
            val lm = try {
                pose.detect(image, tsMs)
            } catch (t: Throwable) {
                Log.e("REHAB", "pose.detect crash", t)
                null
            }

            if (lm == null) {
                // 偵測不到也要正常關閉 image
                return
            }

            val rot = image.imageInfo.rotationDegrees    // 0/90/180/270
            val srcW = if (rot % 180 == 0) image.width else image.height
            val srcH = if (rot % 180 == 0) image.height else image.width
            val imgW = image.width
            val imgH = image.height

            val viewW = previewView.width
            val viewH = previewView.height

// FILL_CENTER 幾何：取較大的縮放，可能被裁切
            val scale   = maxOf(viewW / srcW.toFloat(), viewH / srcH.toFloat())
            val offsetX = (viewW - srcW * scale) * 0.5f
            val offsetY = (viewH - srcH * scale) * 0.5f

            try {
                // 前鏡頭要鏡像（你已有 isFront）
                skeleton.setTransform(
                    imgWidth = srcW, imgHeight = srcH,
                    rotationDeg = rot, mirror = isFront,
                    viewWidth = viewW, viewHeight = viewH,
                    scale = scale, offsetX = offsetX, offsetY = offsetY
                )
                skeleton.updateLandmarks(lm, srcW, srcH, mirror = isFront)
            } catch (t: Throwable) {
                Log.e("REHAB", "skeleton.updateLandmarks crash", t)
            }

            try {
                detector?.let { d ->
                    lastHud = d.onFrame(lm, srcW, srcH, fps, tsNs)
                }
            } catch (t: Throwable) {
                Log.e("REHAB", "detector.onFrame crash", t)
            }

            overlay.postInvalidate()
            skeleton.postInvalidate()
        } catch (t: Throwable) {
            // 任何未預期例外都記一筆，避免靜默退出
            Log.e("REHAB", "Analyzer fatal error", t)
        } finally {
            try {
                image.close()
            } catch (t: Throwable) {
                Log.w("REHAB", "Image close failed", t)
            }
        }
    }


    private fun finishSession(root: View) {
        val d = detector ?: return
        val (succ, fail, total) = d.getCounts()
        val reps = d.drainRepLogs()
        val successRate = if (total > 0) (succ * 100f / total) else 0f
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ActionType.CALF.name

        val paramsAny: Map<String, Any> = when (ActionType.valueOf(action)) {
            ActionType.SQUAT -> mapOf(
                "standUpDeg" to SquatParams().standUpDeg,
                "succMinDeg" to SquatParams().succMinDeg,
                "succMaxDeg" to SquatParams().succMaxDeg,
                "failMinDeg" to SquatParams().failMinDeg,
                "failMaxDeg" to SquatParams().failMaxDeg,
                "emaAlpha" to SquatParams().emaAlpha,
                "smoothN" to SquatParams().smoothN
            )
            ActionType.CALF -> mapOf(
                "A_min" to CalfParams().aMin,
                "A_max" to CalfParams().aMax,
                "holdSeconds" to CalfParams().holdSeconds,
                "emaAlpha" to CalfParams().emaAlpha,
                "idleThreshold" to CalfParams().idleThreshold,
                "calibFrames" to CalfParams().calibFrames,
                "calibJitterPx" to CalfParams().calibJitterPx,
                "enforceToeGround" to CalfParams().enforceToeGround
            )
        }

        // (2) 映射成 ParamValue
        val paramsEncoded: Map<String, rehabcore.domain.model.ParamValue> =
            paramsAny.mapValues { (_, v) -> v.toParamValue() }

        val result = SessionResult(
            action = action,
            fps = fps,
            params = paramsEncoded,
            success = succ,
            fail = fail,
            total = total,
            successRate = successRate,
            reps = reps,
            framesSampled = 0
        )
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = SessionResultWriter.write(this, result, "${stamp}_${action}.json")
        Snackbar.make(root, "已儲存：${file.absolutePath}", Snackbar.LENGTH_LONG).show()
        diagnostics?.let {
            val csv = it.dumpCsvTo(filesDir)
            Snackbar.make(root, "Frames CSV：${csv.absolutePath}", Snackbar.LENGTH_LONG).show()
        }
        setResult(RESULT_OK, Intent().putExtra("json_path", file.absolutePath))
        finish()
    }
    private fun Any?.toParamValue(): ParamValue = when (this) {
        null       -> ParamValue.S("null")
        is String  -> ParamValue.S(this)
        is Boolean -> ParamValue.B(this)
        is Int     -> ParamValue.I(this)
        is Long    -> ParamValue.I(this.toInt())     // 視需求：可能會有精度/範圍
        is Float   -> ParamValue.F(this)
        is Double  -> ParamValue.F(this.toFloat())
        is Enum<*> -> ParamValue.S(this.name)        // enum 用名字表示
        else       -> ParamValue.S(toString())       // 其他型別一律 toString()
    }
}
