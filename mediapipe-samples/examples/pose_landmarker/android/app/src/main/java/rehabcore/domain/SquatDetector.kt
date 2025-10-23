package rehabcore.domain

import rehabcore.domain.model.*
import rehabcore.domain.Logger
import kotlin.math.*
enum class SideMode { LEFT, RIGHT, AUTO }

class SquatDetector(
    private val params: SquatParams = SquatParams(),
    private val diagnostics: Diagnostics? = null,
    private val sideMode: SideMode = SideMode.AUTO
) : Detector {
    private enum class S { STAND, DOWN }
    private var state = S.STAND
    private var emaAngle: Float? = null
    private var minAngleThisRep: Float = 999f
    private var success = 0
    private var fail = 0
    private var repId = 0
    private val repLogs = mutableListOf<RepLog>()
    private var lastTsNs: Long? = null
    private var avgFps: Float = 0f
    // 新增：本回合鎖定側（"L"/"R"/null）
    private var activeSide: String? = null

    override fun reset() {
        state = S.STAND
        emaAngle = null
        minAngleThisRep = 999f
        success = 0
        fail = 0
        repId = 0
        repLogs.clear()
    }

    override fun getCounts(): Triple<Int, Int, Int> = Triple(success, fail, success + fail)
    override fun drainRepLogs(): List<RepLog> = repLogs.toList()

    override fun onFrame(
        landmarks: PoseLandmarks,
        width: Int,
        height: Int,
        fps: Float,
        nowNanos: Long
    ): FrameHud {
        val (rawAngle, side) = kneeAngleWithSide(landmarks)
        rawAngle ?: return FrameHud(null, state.name, 0f, success, fail)
        emaAngle = if (emaAngle == null) rawAngle else (params.emaAlpha * rawAngle + (1 - params.emaAlpha) * emaAngle!!)
        val angle = emaAngle!!

        when (state) {
            S.STAND -> {
                if (angle <= params.standUpDeg - 5f) {
                    Logger.state("SQUAT", state.name, "DOWN", angle)
                    state = S.DOWN
                    minAngleThisRep = angle
                    // 進入回合時鎖側（AUTO 模式才需要）
                    if (sideMode == SideMode.AUTO) activeSide = side
                    if (activeSide == null) activeSide = side   // LEFT/RIGHT 也可顯示在 HUD
                }
            }
            S.DOWN -> {
                minAngleThisRep = kotlin.math.min(minAngleThisRep, angle)
                if (angle >= params.standUpDeg) {
                    finalize()
                    Logger.state("SQUAT", "DOWN", "STAND", angle)
                    state = S.STAND
                    activeSide = null // 回到待命，解除鎖側
                }
            }
        }
        val hud = FrameHud(angle, state.name, 0f, success, fail, mapOf(
            "minAngleThisRep" to minAngleThisRep,
            "fps" to smoothFps(nowNanos), "side" to (activeSide ?: side)
        ))
        diagnostics?.frame(angle, state.name, 0f, null, null)
        return hud
    }

    private fun finalize() {
        diagnostics?.event("SQUAT_FINALIZE", mapOf("minAngle" to minAngleThisRep))
        repId++
        val outcome = when {
            minAngleThisRep in params.succMinDeg..params.succMaxDeg -> { success++; "SUCCESS" }
            minAngleThisRep in params.failMinDeg..params.failMaxDeg -> { fail++; "FAIL_RANGE_${'$'}{params.failMinDeg.toInt()}_${'$'}{params.failMaxDeg.toInt()}" }
            else -> { fail++; "FAIL_INVALID_DEPTH" }
        }
        repLogs += RepLog(
            id = repId,
            baseDeg = null,
            peakDeg = null,
            holdSec = 0f,
            outcome = outcome,
            minAngleThisRep = minAngleThisRep
        )
        Logger.logSquat(minAngleThisRep, outcome)
        minAngleThisRep = 999f
    }

    private fun kneeAngleWithSide(lm: PoseLandmarks): Pair<Float?, String?> {
        val L = triple("left",  lm, "hip","knee","ankle")
        val R = triple("right", lm, "hip","knee","ankle")

        val leftVis  = L?.minVis ?: -1f
        val rightVis = R?.minVis ?: -1f
        val leftAng  = L?.angle
        val rightAng = R?.angle

        fun pickAuto(): String? {
            // 1) 若本回合已有鎖側，直接沿用
            activeSide?.let { return it }

            // 2) 先比可見度（minVis）> 0.6
            val th = 0.6f
            val leftOk = leftVis >= th && leftAng != null
            val rightOk = rightVis >= th && rightAng != null
            if (leftOk && !rightOk) return "L"
            if (!leftOk && rightOk) return "R"
            if (leftOk && rightOk) {
                // 都清楚：用膝角較小（較深）的那側
                return if (leftAng!! <= rightAng!!) "L" else "R"
            }
            // 3) 皆不清楚：退而求其次，比較 minVis；再不行就看哪邊有角度
            if (leftVis > rightVis && leftAng != null) return "L"
            if (rightVis > leftVis && rightAng != null) return "R"
            if (leftAng != null) return "L"
            if (rightAng != null) return "R"
            return null
        }

        val side = when (sideMode) {
            SideMode.LEFT  -> "L"
            SideMode.RIGHT -> "R"
            SideMode.AUTO  -> pickAuto()
        }

        val ang = when (side) {
            "L" -> leftAng
            "R" -> rightAng
            else -> null
        }
        return ang to side
    }


    // 小工具：同時計算角度與三點 min visibility
    private data class Trio(val angle: Float, val minVis: Float)
    private fun triple(prefix: String, lm: PoseLandmarks, hip: String, knee: String, ankle: String): Trio? {
        val h = lm.points["${prefix}_$hip"] ?: return null
        val k = lm.points["${prefix}_$knee"] ?: return null
        val a = lm.points["${prefix}_$ankle"] ?: return null
        if (h.visibility!! <= 0.5f || k.visibility!! <= 0.5f || a.visibility!! <= 0.5f) return null
        val ang = angleDeg(h, k, a)
        val mvis = minOf(h.visibility, k.visibility, a.visibility)
        return Trio(ang, mvis)
    }


    private fun angleDeg(a: PosePoint, b: PosePoint, c: PosePoint): Float {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val dot = v1x * v2x + v1y * v2y
        val n1 = hypot(v1x, v1y); val n2 = hypot(v2x, v2y)
        val cos = (dot / (n1 * n2 + 1e-6)).coerceIn(-1.0, 1.0)
        return (acos(cos) * 180.0 / Math.PI).toFloat()
    }

    private fun smoothFps(nowNs: Long): Float {
        val dt = (nowNs - (lastTsNs ?: nowNs)).coerceAtLeast(1L)
        val inst = 1_000_000_000f / dt
        avgFps = if (avgFps == 0f) inst else (0.1f * inst + 0.9f * avgFps)
        lastTsNs = nowNs
        return avgFps
    }
}