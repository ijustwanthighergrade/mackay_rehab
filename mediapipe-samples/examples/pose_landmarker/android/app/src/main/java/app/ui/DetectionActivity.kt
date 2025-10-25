package app.ui

import rehabcore.domain.SquatParams
import CalfParams
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import rehabcore.domain.model.RepLog


class DetectionActivity : ComponentActivity() {

    private lateinit var cameraController: CameraController
    private val viewModel: DetectionViewModel by lazy { androidx.lifecycle.ViewModelProvider(this)[DetectionViewModel::class.java] }

    companion object {
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_DIAG = "extra_diag"
    }

    private var isShuttingDown = false
    private var imageAnalysis: ImageAnalysis? = null
    private var poseReady = false   // 可選：等第一次 callback 才開始送幀

    // --- UI ---
    private lateinit var previewView: PreviewView
    private lateinit var skeleton: SkeletonOverlay
    private lateinit var overlay: View
    private lateinit var backBtn: Button
    private lateinit var switchCamBtn: Button   // ← 新增

    // --- HUD renderers ---
    private val hud = HudRenderer()
    private val calfOverlay = CalfOverlay()

    // --- detection ---
    private lateinit var pose: PoseLandmarkerClient

    // --- session state / HUD data ---
    private var lastHud: FrameHud = FrameHud(null, "INIT", 0f, 0, 0)
    private var fps: Float = 0f
    private var lastTsMs: Long? = null

    // --- camera ---
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var isFront = true
    private var isSaving = false
    private lateinit var savingOverlay: View   // 轉圈圈遮罩
    // diagnostics（如果你要額外 CSV，可自行接）
    private var diagnostics: Diagnostics? = null


    private val reqPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) cameraController.bindCamera() else finish()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isDestroyed) stopCameraAndMl()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra(EXTRA_ACTION) ?: ActionType.CALF.name
        viewModel.initDetector(action)

        // 1) 先把畫面元素建好（至少 previewView 要先有）
        val root = FrameLayout(this)
        previewView = PreviewView(this)
        skeleton = SkeletonOverlay(this)

        overlay = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                hud.draw(canvas, viewModel.hud.value)
                calfOverlay.draw(canvas, viewModel.hud.value)
            }
        }

        backBtn = Button(this).apply {
            text = "返回"
            alpha = 0.9f
            setOnClickListener { showSessionConfirm(this) }
        }

        savingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            addView(android.widget.ProgressBar(this@DetectionActivity),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
        }

        val backParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 48
            rightMargin = 48
        }

        val switchParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = 48
            bottomMargin = 48
        }

        // 2) 再建 cameraController（此時 previewView 已經就緒）
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        cameraController = CameraController(this, previewView, executor) { image ->
            try {
                pose.detectLive(image, cameraController.isFrontCamera)
            } finally {
                image.close()
            }
        }

        // 3) 需要讀取 cameraController 狀態的按鈕，現在才安全設定文字/點擊事件
        switchCamBtn = Button(this).apply {
            text = if (cameraController.isFrontCamera) "前鏡頭（點我切換）" else "後鏡頭（點我切換）"
            alpha = 0.9f
            setOnClickListener {
                if (isSaving) return@setOnClickListener
                cameraController.switchCamera()
                text = if (cameraController.isFrontCamera) "前鏡頭（點我切換）" else "後鏡頭（點我切換）"
            }
        }

        // 4) 把各層加到畫面上（savingOverlay 放最後，確保在最上層）
        root.addView(previewView)
        root.addView(skeleton)
        root.addView(overlay as View)
        root.addView(switchCamBtn, switchParams)
        root.addView(backBtn, backParams)
        root.addView(savingOverlay)
        setContentView(root)

        // 5) 再建立 PoseLandmarker（bindCamera 會在權限核可後才呼叫，不會搶跑）
        pose = PoseLandmarkerClient(
            context = this,
            runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM
        ) { lm, w, h, tsMs ->
            if (lm != null) {
                // 1) 先讓偵測邏輯跑
                viewModel.processFrame(lm, w, h, fps, tsMs)

                // 2) 設定骨架的轉換（旋轉角度要用 0/90/180/270 度，而不是 Surface 的 0..3 常數）
                val rotConst = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val rotDeg = when (rotConst) {
                    android.view.Surface.ROTATION_0   -> 0
                    android.view.Surface.ROTATION_90  -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }

                // FILL_CENTER：算出 scale 與置中位移
                val (scale, offX, offY) = computeFillCenter(
                    previewView.width, previewView.height,
                    w, h, rotDeg
                )

                // 3) 套用轉換與 landmarks（前鏡頭要鏡像）
                skeleton.setTransform(
                    imgWidth = if (rotDeg % 180 == 0) w else h,
                    imgHeight = if (rotDeg % 180 == 0) h else w,
                    rotationDeg = rotDeg,
                    mirror = cameraController.isFrontCamera,
                    viewWidth = previewView.width,
                    viewHeight = previewView.height,
                    scale = scale,
                    offsetX = offX,
                    offsetY = offY
                )
                skeleton.updateLandmarks(lm, w, h, mirror = cameraController.isFrontCamera)
            }

            // 4) 讓兩層 overlay 重畫
            overlay.invalidate()
            // skeleton.invalidate() 不一定需要，updateLandmarks() 內部已經會 invalidate()
        }


        // 6) 權限 → 綁相機（此時 controller 與 previewView 都已就緒）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            cameraController.bindCamera()
        } else {
            reqPerm.launch(Manifest.permission.CAMERA)
        }

        // 讓返回彈窗能讀到最新 HUD
        lastHud = viewModel.hud.value
    }

    /** 根據 PreviewView 尺寸、來源尺寸與旋轉，算出 FILL_CENTER 的縮放與置中位移變換參數 */
    private fun computeFillCenter(
        viewW: Int, viewH: Int,
        srcW: Int, srcH: Int,
        rotationDeg: Int
    ): Triple<Float, Float, Float> {
        val (rw, rh) = if (rotationDeg % 180 == 0) srcW to srcH else srcH to srcW
        val scale = maxOf(viewW / rw.toFloat(), viewH / rh.toFloat())   // FILL_CENTER 用 max
        val outW = rw * scale
        val outH = rh * scale
        val offsetX = (viewW - outW) * 0.5f
        val offsetY = (viewH - outH) * 0.5f
        return Triple(scale, offsetX, offsetY)
    }

//
//    // ② Camera 綁定與 Analyzer（把幀丟給 LIVE_STREAM）
//    @OptIn(ExperimentalGetImage::class)
//    private fun bindCamera() {
//        poseReady = false
//        val providerFuture = ProcessCameraProvider.getInstance(this)
//        providerFuture.addListener({
//            cameraProvider = providerFuture.get()
//
//            val displayRotation = previewView.display.rotation
//
//            val preview = Preview.Builder()
//                .setTargetRotation(displayRotation)
//                .build().also {
//                    it.setSurfaceProvider(previewView.surfaceProvider)
//                }
//
//            val analysis = ImageAnalysis.Builder()
//                .setTargetResolution(android.util.Size(640, 480))
//                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//                .setImageQueueDepth(1)   // ← 加這行
//                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
//                .setTargetRotation(displayRotation)
//                .build().also { ia ->
//                    imageAnalysis = ia   // ← **存起來**
//                    ia.setAnalyzer(analysisExecutor) { image ->
//                        if (isShuttingDown || isFinishing || isDestroyed) { image.close(); return@setAnalyzer }
//                        try {
//                            pose.detectLive(image, isFront)
//                        } catch (t: Throwable) {
//                            Log.w("DetectionActivity", "detectLive ignored: ${t.message}")
//                        } finally {
//                            image.close()
//                        }
//                    }
//
//                }
//
//
//
//            // 依 isFront 選擇鏡頭
//            val selector = if (isFront)
//                CameraSelector.DEFAULT_FRONT_CAMERA
//            else
//                CameraSelector.DEFAULT_BACK_CAMERA
//
//            // 重新綁定
//            cameraProvider?.unbindAll()
//            cameraProvider?.bindToLifecycle(this, selector, preview, analysis)
//        }, ContextCompat.getMainExecutor(this))
//    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        try { pose.close() } catch (_: Exception) {}
    }

    // === 確認視窗：先預覽統計與最近狀態，再決定是否存檔 ===
    private fun showSessionConfirm(anchor: View) {
        val (succ, fail, total) = viewModel.getCounts()
        val successRate = if (total > 0) (succ * 100f / total) else 0f

        // 讀最近 50 筆事件（可依需要調）
        val recentAll: List<RepLog> = viewModel.peekRecentRepLogs(50)

        // A) 狀態變換紀錄（只挑 outcome 以 "STATE_" 開頭）
        val stateLines = recentAll
            .filter { it.outcome.startsWith("STATE_") }
            .takeLast(20) // 只列最後 20 筆，避免太長
            .joinToString("\n") { r ->
                val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date(r.epochMs))
                val degTxt = r.peakDeg?.let { String.format("%.1f°", it) } ?: "--"
                "• [$t] ${r.outcome} @Δ=${degTxt}"
            }

        // B) 回合結果彙整（成功 / 失敗 事件）
        val repLines = recentAll
            .filter { !it.outcome.startsWith("STATE_") }   // 只留 SUCCESS/FAIL_*
            .takeLast(10)                                  // 最後 10 回合
            .joinToString("\n") { r ->
                val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date(r.epochMs))
                val baseTxt = r.baseDeg?.let { String.format("%.1f°", it) } ?: "--"
                val peakTxt = r.peakDeg?.let { String.format("%.1f°", it) } ?: "--"
                val holdTxt = String.format("%.2fs", r.holdSec)
                "• #${r.id} [$t] ${r.outcome}  base=$baseTxt  peakΔ=$peakTxt  hold=$holdTxt"
            }

        val state = lastHud.state
        val angle = lastHud.angleDeg ?: 0f

        val msg = buildString {
            appendLine("即將儲存此次運動結果：")
            appendLine("狀態：$state")
            appendLine("當前角度(Δ)：${"%.1f".format(angle)}°")
            appendLine("成功：$succ  失敗：$fail  總數：$total")
            appendLine("成功率：${"%.1f".format(successRate)}%")
            appendLine()
            appendLine("— 狀態變換紀錄（近 20 筆）—")
            appendLine(if (stateLines.isBlank()) "（無）" else stateLines)
            appendLine()
            appendLine("— 回合結果彙整（近 10 筆）—")
            appendLine(if (repLines.isBlank()) "（無）" else repLines)
            appendLine()
            append("確認要儲存並離開嗎？")
        }

        AlertDialog.Builder(this)
            .setTitle("儲存並離開？")
            .setMessage(msg)
            .setPositiveButton("儲存並離開") { _, _ ->
                if (isSaving) return@setPositiveButton
                isSaving = true
                backBtn.isEnabled = false
                switchCamBtn.isEnabled = false
                savingOverlay.visibility = View.VISIBLE
                stopCameraOnly()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        writeSessionFiles()
                        withContext(Dispatchers.Main) {
                            stopCameraAndMl()
                            setResult(RESULT_OK, Intent().putExtra("saved", true))
                            finish()
                        }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            isSaving = false
                            savingOverlay.visibility = View.GONE
                            backBtn.isEnabled = true
                            switchCamBtn.isEnabled = true
                            Toast.makeText(this@DetectionActivity, "儲存失敗：${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNeutralButton("不儲存離開") { _, _ ->
                if (isSaving) return@setNeutralButton
                backBtn.isEnabled = false
                switchCamBtn.isEnabled = false
                stopCameraAndMl()
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    private fun stopCameraOnly() {
        cameraController.unbind()
//        poseReady = false
//        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
//        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
    }

    private fun stopCameraAndMl() {
//        if (isShuttingDown) return
//        isShuttingDown = true
//        poseReady = false
//        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
//        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
//        try { pose.close() } catch (_: Exception) {}
//        try { analysisExecutor.shutdownNow() } catch (_: Exception) {}
//        detector = null

        cameraController.shutdown()
        try { pose.close() } catch (_: Exception) {}
    }

    // 只負責寫檔（原 finishSession 內的收集 counts、產 JSON、SessionResultWriter 寫檔等）
    private fun writeSessionFiles() {
        val d = viewModel ?: return
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
                "emaAlpha"  to SquatParams().emaAlpha,
                "smoothN"   to SquatParams().smoothN
            )
            else -> mapOf(
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

        val paramsEncoded: Map<String, rehabcore.domain.model.ParamValue> =
            paramsAny.mapValues { (_, v) -> v.toParamValue() }

        SessionResultWriter.save(
            context = this,
            action = action,
            fps = fps,
            params = paramsEncoded,
            success = succ,
            fail = fail,
            total = total,
            successRate = successRate,
            reps = reps,
            framesSampled = 0
            // filename 可省略，預設用 action+timestamp 命名
        )
    }


    private fun finishSession(root: View) {
        stopCameraAndMl()
        val d = viewModel ?: return
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

            ActionType.REHAB_CALF -> TODO()
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
// 1) 產生檔名時間戳
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

// 2) 寫入 JSON：writeSafe 回傳「字串路徑」
        val jsonPath: String = SessionResultWriter.writeSafe(
            context = this,
            result = result,
            filename = "${stamp}_${action}.json"
        ).toString()

// 3) 提示 JSON 寫檔結果（直接顯示字串路徑，不要 .absolutePath）
        Snackbar.make(root, "已儲存: $jsonPath", Snackbar.LENGTH_LONG).show()

// 4) 若有逐幀診斷資訊：dumpCsvTo 通常回傳 File，這時候用 .absolutePath 才正確
        diagnostics?.let { diag ->
            val csvFile: File = diag.dumpCsvTo(filesDir)
            Snackbar.make(root, "Frames CSV: ${csvFile.absolutePath}", Snackbar.LENGTH_LONG).show()
        }

// 5) 將路徑回傳給上一個 Activity（仍然用字串）
        setResult(
            RESULT_OK,
            Intent().putExtra("json_path", jsonPath)
        )
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
