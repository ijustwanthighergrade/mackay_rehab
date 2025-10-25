package rehabcore.domain

import rehabcore.domain.model.*
import kotlin.math.*

class RehabCalfDetector(
    private val params: RehabCalfParams = RehabCalfParams(),
    private val diagnostics: Diagnostics? = null
) : Detector {

    private enum class S { STAND, RAISING, HOLDING, LOWERING }

    private var state = S.STAND
    private var emaAngle: Float? = null
    private var lastAngle: Float = 0f
    private var lastDt: Float = 0f
    private var success = 0
    private var fail = 0
    private var repId = 0
    private var holdSec = 0f
    private var repLogs = mutableListOf<RepLog>()
    private var lastTsNs: Long? = null
    private var avgFps = 0f

    override fun reset() {
        state = S.STAND
        emaAngle = null
        lastAngle = 0f
        success = 0
        fail = 0
        repId = 0
        holdSec = 0f
        repLogs.clear()
    }

    override fun getCounts(): Triple<Int, Int, Int> = Triple(success, fail, success + fail)

    override fun drainRepLogs(): List<RepLog> {
        val out = repLogs.toList()
        repLogs.clear()
        return out
    }

    override fun peekRecentRepLogs(limit: Int): List<RepLog> =
        if (repLogs.size <= limit) repLogs.toList() else repLogs.takeLast(limit)

    override fun onFrame(
        landmarks: PoseLandmarks,
        width: Int,
        height: Int,
        fps: Float,
        nowNanos: Long
    ): FrameHud {
        val leftAnkle = landmarks.points["left_ankle"]
        val leftHeel = landmarks.points["left_heel"]
        val leftToe = landmarks.points["left_foot_index"]

        val rightAnkle = landmarks.points["right_ankle"]
        val rightHeel = landmarks.points["right_heel"]
        val rightToe = landmarks.points["right_foot_index"]

        val angleLeft = if (leftAnkle != null && leftHeel != null && leftToe != null)
            angleDeg(leftAnkle, leftHeel, leftToe) else null
        val angleRight = if (rightAnkle != null && rightHeel != null && rightToe != null)
            angleDeg(rightAnkle, rightHeel, rightToe) else null

        // 使用可見度較高者
        val angle: Float = run {
            val candidates = listOfNotNull(angleLeft, angleRight)
            if (candidates.isEmpty()) {
                return FrameHud(null, state.name, holdSec, success, fail)
            }
            candidates.average().toFloat()   // Double -> Float
        }

        val smooth = if (emaAngle == null) angle else params.emaAlpha * angle + (1 - params.emaAlpha) * emaAngle!!
        emaAngle = smooth
        val dt = lastDtSec(nowNanos)
        val dtheta = (smooth - lastAngle) / max(dt, 1e-5f)
        lastAngle = smooth

        when (state) {
            S.STAND -> {
                if (smooth >= params.raiseEnterDeg) {
                    state = S.RAISING
                    holdSec = 0f
                }
            }
            S.RAISING -> {
                if (smooth >= params.holdMinDeg) {
                    state = S.HOLDING
                    holdSec = 0f
                } else if (dtheta < -params.maxLowerSpeedDeg / fps) {
                    commitFail("UNSTABLE_RAISE")
                }
            }
            S.HOLDING -> {
                holdSec += dt
                if (holdSec >= params.holdSeconds) {
                    commitSuccess(smooth, holdSec)
                    state = S.LOWERING
                } else if (smooth < params.lowerExitDeg) {
                    commitFail("EARLY_LOWER")
                    state = S.LOWERING
                }
            }
            S.LOWERING -> {
                if (smooth <= params.idleThreshold) {
                    state = S.STAND
                    holdSec = 0f
                }
            }
        }
        return FrameHud(smooth, state.name, holdSec, success, fail, mapOf(
            "fps" to smoothFps(nowNanos),
            "speedDegPerSec" to dtheta
        ))
    }

    private fun angleDeg(a: PosePoint, b: PosePoint, c: PosePoint): Float {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val dot = v1x * v2x + v1y * v2y
        val n1 = hypot(v1x, v1y); val n2 = hypot(v2x, v2y)
        val cos = (dot / (n1 * n2 + 1e-6)).coerceIn(-1.0, 1.0)
        return (acos(cos) * 180.0 / Math.PI).toFloat()
    }

    private fun commitSuccess(angle: Float, hold: Float) {
        repId++
        success++
        repLogs += RepLog(
            id = repId,
            baseDeg = angle,
            peakDeg = angle,
            holdSec = hold,
            outcome = "SUCCESS",
            minAngleThisRep = angle,
            epochMs = System.currentTimeMillis()
        )
        diagnostics?.event("REHAB_CALF_SUCCESS", mapOf("angle" to angle))
        Logger.logCalf(repId, angle, angle, hold, "SUCCESS")
    }

    private fun commitFail(reason: String) {
        repId++
        fail++
        repLogs += RepLog(
            id = repId,
            baseDeg = lastAngle,
            peakDeg = lastAngle,
            holdSec = 0f,
            outcome = reason,
            minAngleThisRep = null,
            epochMs = System.currentTimeMillis()
        )
        diagnostics?.event("REHAB_CALF_FAIL", mapOf("reason" to reason))
        Logger.logCalf(repId, lastAngle, lastAngle, 0f, reason)
    }

    private fun lastDtSec(nowNs: Long): Float {
        val prev = lastTsNs ?: run { lastTsNs = nowNs; return 0f }
        val dt = (nowNs - prev).coerceAtLeast(0L)
        lastTsNs = nowNs
        return dt / 1_000_000_000f
    }

    private fun smoothFps(nowNs: Long): Float {
        val prev = lastTsNs ?: run { lastTsNs = nowNs; return 0f }
        val dt = (nowNs - prev).coerceAtLeast(1L)
        val inst = 1_000_000_000f / dt
        avgFps = if (avgFps == 0f) inst else (0.1f * inst + 0.9f * avgFps)
        lastTsNs = nowNs
        return avgFps
    }
}