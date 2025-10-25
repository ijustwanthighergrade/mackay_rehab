package rehabcore.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions
import rehabcore.domain.model.PoseLandmarks
import rehabcore.domain.model.PosePoint
import java.util.Optional

class PoseLandmarkerClient(
    context: Context,
    private val modelAsset: String = "pose_landmarker_full.task",
    private val runningMode: RunningMode = RunningMode.LIVE_STREAM,   // ← 跟你組員一樣
    private val listener: ((lm: PoseLandmarks?, w: Int, h: Int, tsMs: Long) -> Unit)? = null
) : AutoCloseable {

    private val landmarker: PoseLandmarker
    private var nv21Buf: ByteArray? = null
    private var rgbBitmap: Bitmap? = null
    private var argbBuf: IntArray? = null

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(modelAsset)
            .build()

        val optBuilder = PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(runningMode)
            .setNumPoses(1)

        if (runningMode == RunningMode.LIVE_STREAM) {
            optBuilder
                .setResultListener { res, input ->
                    // 統一把結果轉成我們的 PoseLandmarks 回傳
                    val ts = res.timestampMs()
                    val first = res.landmarks().firstOrNull()
                    val lm = if (first != null) toPose(first) else null
                    // input 為 MPImage；寬高用它
                    listener?.invoke(lm, input.width, input.height, ts)
                }
                .setErrorListener { e ->
                    Log.e("REHAB", "PoseLandmarker live error", e)
                }
        }

        val options = optBuilder.build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    // ===== 原先 VIDEO 同步路徑（保留）：如果你還要用 detectForVideo 可以繼續用 =====
    @ExperimentalGetImage
    fun detect(image: ImageProxy, timestampMs: Long): PoseLandmarks? {
        if (runningMode != RunningMode.VIDEO) {
            // 若目前是 LIVE_STREAM，這支同步方法就不做事（避免誤用）
            return null
        }
        val mp = buildMpImageARGB(image) ?: return null
        val imgOpts = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
            .setRotationDegrees(image.imageInfo.rotationDegrees)
            .build()
        val res = runCatching { landmarker.detectForVideo(mp, imgOpts, timestampMs) }.getOrNull()
            ?: return null
        val lm = res.landmarks().firstOrNull() ?: return null
        return toPose(lm)
    }

    // ===== LIVE_STREAM 非同步路徑：和你組員一樣 detectAsync =====
    @ExperimentalGetImage
    fun detectLive(image: ImageProxy, isFront: Boolean) {
        if (runningMode != RunningMode.LIVE_STREAM) return
        // LIVE_STREAM 的 detectAsync 沒有旋轉參數 → 先把位圖旋正／鏡像再送進去
        val tsMs = SystemClock.uptimeMillis()
        val mp = buildMpImageARGB(image, rotateIntoBitmap = true, isFront = isFront) ?: run {
            image.close(); return
        }
        image.close()
        landmarker.detectAsync(mp, tsMs)
    }

    // --- 共同的影像建構（沿用你原本高效路徑；可選擇是否旋到位圖內） ---
    @ExperimentalGetImage
    private fun buildMpImageARGB(
        image: ImageProxy,
        rotateIntoBitmap: Boolean = false,
        isFront: Boolean = false
    ): MPImage? {
        val mediaImage = image.image ?: return null
        val w = image.width
        val h = image.height

        if (argbBuf == null || argbBuf!!.size != w * h) argbBuf = IntArray(w * h)
        var out = rgbBitmap
        if (out == null || out.width != w || out.height != h) {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            rgbBitmap = out
        }

        val nv21 = ensureNv21Buffer(image)
        yuv420888_to_nv21(mediaImage, nv21)
        val rgbaBytes: ByteArray = Toolkit.yuvToRgb(nv21, w, h, YuvFormat.NV21)
        val argbInts = rgbaToArgbIntArray(rgbaBytes, argbBuf!!)
        out.setPixels(argbInts, 0, w, 0, 0, w, h)

        // LIVE_STREAM: 需要把旋轉/鏡像「寫進位圖」
        val bmp = if (rotateIntoBitmap) {
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
//                if (isFront) {
//                    postScale(-1f, 1f, w / 2f, h / 2f)
//                }
            }
            Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
        } else out

        return BitmapImageBuilder(bmp).build()
    }


    /** 轉換 RGBA8888 ByteArray -> ARGB8888 IntArray（Bitmap.setPixels 需要） */
    private fun rgbaToArgbIntArray(rgba: ByteArray, out: IntArray): IntArray {
        var i = 0; var p = 0
        while (i < rgba.size) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            out[p++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            i += 4
        }
        return out
    }

    // --- 緩衝區重用（回傳非 null ByteArray）
    private fun ensureNv21Buffer(image: ImageProxy): ByteArray {
        val need = image.width * image.height * 3 / 2
        val cur = nv21Buf
        return if (cur == null || cur.size != need) {
            ByteArray(need).also { nv21Buf = it }
        } else cur
    }


    /** 正確依 rowStride/pixelStride 打包成 NV21 (VU) */
    private fun yuv420888_to_nv21(img: android.media.Image, out: ByteArray) {
        val w = img.width
        val h = img.height

        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]

        // ---- Y
        var dst = 0
        val yRS = y.rowStride
        val yCap = y.buffer.capacity()
        val yRow = ByteArray(yRS)
        for (row in 0 until h) {
            val pos = row * yRS
            val take = minOf(yRS, yCap - pos)
            y.buffer.position(pos)
            y.buffer.get(yRow, 0, take)
            System.arraycopy(yRow, 0, out, dst, w)
            dst += w
        }

        // ---- UV -> NV21(VU)
        val uRS = u.rowStride; val uPS = u.pixelStride
        val vRS = v.rowStride; val vPS = v.pixelStride
        val uCap = u.buffer.capacity()
        val vCap = v.buffer.capacity()
        val uRow = ByteArray(uRS)
        val vRow = ByteArray(vRS)
        val chH = h / 2
        val chW = w / 2
        for (row in 0 until chH) {
            var uBase = row * uRS
            var vBase = row * vRS
            val uTake = minOf(uRS, uCap - uBase)
            val vTake = minOf(vRS, vCap - vBase)
            u.buffer.position(uBase); u.buffer.get(uRow, 0, uTake)
            v.buffer.position(vBase); v.buffer.get(vRow, 0, vTake)
            var uIdx = 0
            var vIdx = 0
            for (col in 0 until chW) {
                out[dst++] = vRow[vIdx] // V
                out[dst++] = uRow[uIdx] // U
                uIdx += uPS
                vIdx += vPS
            }
        }
    }

    private fun toPose(list: List<NormalizedLandmark>?): PoseLandmarks? {
        list ?: return null
        val names = listOf(
            "nose","left_eye_inner","left_eye","left_eye_outer","right_eye_inner","right_eye","right_eye_outer",
            "left_ear","right_ear","mouth_left","mouth_right",
            "left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist",
            "left_pinky","right_pinky","left_index","right_index","left_thumb","right_thumb",
            "left_hip","right_hip","left_knee","right_knee","left_ankle","right_ankle",
            "left_heel","right_heel","left_foot_index","right_foot_index"
        )
        val map = LinkedHashMap<String, PosePoint>(names.size)
        list.forEachIndexed { i, p ->
            if (i < names.size) {
                map[names[i]] = PosePoint(p.x(), p.y(), p.z(), safeVisibility(p))
            }
        }
        return PoseLandmarks(map)
    }

    /** 兼容 Optional<Float> 與 Float 兩種 visibility API。*/
    private fun safeVisibility(p: NormalizedLandmark): Float {
        return try {
            (p.visibility() as Optional<Float>).orElse(1f)
        } catch (_: Throwable) {
            try {
                p.visibility() as Float
            } catch (_: Throwable) {
                1f
            }
        }
    }

    override fun close() {
        landmarker.close()
    }
}
