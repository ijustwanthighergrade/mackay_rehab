package rehabcore.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import rehabcore.domain.model.PoseLandmarks
import rehabcore.domain.model.PosePoint
import kotlin.collections.iterator
import kotlin.math.max

class SkeletonOverlay(context: Context) : View(context) {

    private var imgW = 0; private var imgH = 0
    private var rot = 0;  private var mirror = false
    private var viewW = 0; private var viewH = 0
    private var scale = 1f; private var offX = 0f; private var offY = 0f

    private var lm: PoseLandmarks? = null
    private var frameW = 1
    private var frameH = 1
    private var mirrorX = false // 前鏡頭/需要鏡像時可開

    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; strokeWidth = 6f }
    private val bone  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }

    // 常用骨架邊
    private val edges = arrayOf(
        "left_shoulder" to "right_shoulder", "left_hip" to "right_hip",
        "left_shoulder" to "left_elbow", "left_elbow" to "left_wrist",
        "right_shoulder" to "right_elbow", "right_elbow" to "right_wrist",
        "left_hip" to "left_knee", "left_knee" to "left_ankle",
        "right_hip" to "right_knee", "right_knee" to "right_ankle",
        "left_shoulder" to "left_hip", "right_shoulder" to "right_hip"
    )

    fun updateLandmarks(pose: PoseLandmarks?, srcW: Int, srcH: Int, mirror: Boolean = false) {
        lm = pose
        frameW = if (srcW <= 0) 1 else srcW
        frameH = if (srcH <= 0) 1 else srcH
        mirrorX = mirror
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = lm?.points ?: return
        if (pts.isEmpty()) return

        // 偵測是否已是 0..1 normalized
        val maxX = pts.values.maxOf { it.x }
        val maxY = pts.values.maxOf { it.y }
        val isNorm = (maxX <= 2f && maxY <= 2f)

        fun nx(x: Float) = if (isNorm) x else x / frameW.coerceAtLeast(1).toFloat()
        fun ny(y: Float) = if (isNorm) y else y / frameH.coerceAtLeast(1).toFloat()

        // 畫骨架線
        for ((a, b) in edges) {
            val pa = pts[a]; val pb = pts[b]
            if (visible(pa) && visible(pb)) {
                val (x1, y1) = normToView(nx(pa!!.x), ny(pa.y))
                val (x2, y2) = normToView(nx(pb!!.x), ny(pb.y))
                canvas.drawLine(x1, y1, x2, y2, bone)
            }
        }
        // 畫關節點
        for ((_, p) in pts) if (visible(p)) {
            val (cx, cy) = normToView(nx(p.x), ny(p.y))
            canvas.drawCircle(cx, cy, 6f, joint)
        }
    }

    private fun visible(p: PosePoint?): Boolean {
        if (p == null) return false
        val v = p.visibility ?: 0f
        return v >= 0.2f && p.x.isFinite() && p.y.isFinite()
    }

    fun setTransform(
        imgWidth: Int, imgHeight: Int,
        rotationDeg: Int, mirror: Boolean,
        viewWidth: Int, viewHeight: Int,
        scale: Float, offsetX: Float, offsetY: Float
    ) {
        this.imgW = imgWidth; this.imgH = imgHeight
        this.rot = rotationDeg; this.mirror = mirror
        this.viewW = viewWidth; this.viewH = viewHeight
        this.scale = scale; this.offX = offsetX; this.offY = offsetY
    }

    // 把 normalized landmark 轉成螢幕座標（FILL_CENTER + 旋轉 + 鏡像）
    private fun normToView(nx: Float, ny: Float): Pair<Float, Float> {
        var x = nx; var y = ny
        when (rot) {          // 先旋轉到直立座標
            90  -> { val ox = x; x = 1f - y; y = ox }
            180 -> { x = 1f - x; y = 1f - y }
            270 -> { val ox = x; x = y; y = 1f - ox }
        }
        if (mirror) x = 1f - x   // 旋轉後再鏡像（順序重要）

        val px = x * imgW
        val py = y * imgH
        val vx = offX + px * scale
        val vy = offY + py * scale
        return vx to vy
    }
}