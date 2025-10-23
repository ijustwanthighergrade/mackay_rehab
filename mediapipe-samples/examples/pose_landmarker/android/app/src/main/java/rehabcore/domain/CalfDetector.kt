package rehabcore.domain
import rehabcore.domain.model.*
import kotlin.math.*
import android.os.SystemClock
import rehabcore.domain.Logger


class CalfDetector(private val params: CalfParams = CalfParams(), private val diagnostics: Diagnostics? = null) : Detector {


    private enum class S { CALIB, IDLE, RAISING, HOLDING, COOLDOWN }


    private var state = S.CALIB
    private var emaAngle: Float? = null
    private var frames = 0
    private var lastTsNs: Long? = null
    private var avgFps: Float = 0f
    private var framesSampled = 0


    private var baseDeg = 0f
    private var peakDeg = 0f
    private var holdSec = 0f
    private var repId = 0


    private var success = 0
    private var fail = 0


    private val repLogs = mutableListOf<RepLog>()


    // 校正相關
    private var calibCount = 0
    private var baseLineY: Float? = null // 腳底基準線 Y（像素）
    private var footLenPx: Float? = null // 足長 L（pixel）

    private var windowHoldSec = 0f
    private var lastFpsTsNs: Long? = null


    override fun reset() {
        state = S.CALIB
        emaAngle = null
        frames = 0
        framesSampled = 0
        baseDeg = 0f
        peakDeg = 0f
        holdSec = 0f
        repId = 0
        success = 0
        fail = 0
        repLogs.clear()
        calibCount = 0
        baseLineY = null
        footLenPx = null
        windowHoldSec = 0f

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
        frames++
        framesSampled++
        if (lastTsNs == null) lastTsNs = nowNanos

        val ptToe = pickBestToe(landmarks) ?: return FrameHud(null, state.name, holdSec, success, fail)
        val ptHeel = pickBestHeel(landmarks) ?: return FrameHud(null, state.name, holdSec, success, fail)

        val toeY = ptToe.y * height
        val heelY = ptHeel.y * height
        val toeX = ptToe.x * width
        val heelX = ptHeel.x * width

        // 校正：收斂 toe/heel 垂直位置，建立腳底基準線（取 toe, heel 平均 y）與足長 L
        if (state == S.CALIB) {
            val L = hypot(heelX - toeX, heelY - toeY).toFloat()
            if (L > 0) {
                footLenPx = L
                val baseline = (toeY + heelY) / 2f
                // jitter 門檻（像素）
                if (baseLineY == null) baseLineY = baseline
                val jitter = abs((baseline) - baseLineY!!)
                if (jitter <= params.calibJitterPx) {
                    calibCount++
                } else {
                    calibCount = 0
                    baseLineY = baseline
                }
                if (calibCount >= params.calibFrames) {
                    state = S.IDLE
                }
            }
            return FrameHud(null, state.name, 0f, success, fail, mapOf(
                "baselineY" to baseLineY,
                "footLenPx" to footLenPx,
                "calibProgress" to (calibCount.toFloat() / params.calibFrames),
                "toeX" to toeX, "toeY" to toeY, "heelX" to heelX, "heelY" to heelY
            ))
        }

        val L = footLenPx ?: return FrameHud(null, state.name, holdSec, success, fail)
        val baselineY = baseLineY ?: return FrameHud(null, state.name, holdSec, success, fail)

        // toe 必須貼地（若 enforceToeGround=true）
        if (params.enforceToeGround) {
            val toeLift = (baselineY - toeY)
            if (toeLift > params.calibJitterPx) {
                if (state == S.HOLDING) commitFail("TOE_OFF_GROUND", windowHoldSec)
                state = S.IDLE
                holdSec = 0f
                windowHoldSec = 0f    // ← 記得清掉連續窗內時間
            }
        }

        val heelLift = (baselineY - heelY).coerceAtLeast(0f) // 垂距（像素）
        val theta = atan2(heelLift, L) * 180f / Math.PI.toFloat()
        emaAngle = if (emaAngle == null) theta else (params.emaAlpha * theta + (1 - params.emaAlpha) * emaAngle!!)
        val angle = emaAngle!!

        // 狀態機
        when (state) {
            S.IDLE -> {
                holdSec = 0f
                windowHoldSec = 0f
                baseDeg = angle
                peakDeg = angle
                if (angle >= params.idleThreshold) {
                    Logger.state("CALF", state.name, "RAISING", angle)  // ← 加這行
                    state = S.RAISING
                }
            }
            S.RAISING -> {
                peakDeg = max(peakDeg, angle)
                if (angle >= params.aMin) {
                    Logger.state("CALF", state.name, "HOLDING", angle)
                    state = S.HOLDING
                    holdSec = 0f
                    windowHoldSec = 0f
                } else if (angle < params.idleThreshold) {
                    // 沒有成功進入窗口就掉回 → 視為沒動作
                    state = S.IDLE
                }
            }
            S.HOLDING -> {
                peakDeg = max(peakDeg, angle)

                // 真實時間增量（ns→s）
                val dt = lastDtSec(nowNanos)

                if (angle in params.aMin..params.aMax) {
                    // 只在「窗內」累加連續時間
                    windowHoldSec += dt
                    holdSec = windowHoldSec   // HUD 顯示用
                } else if (angle >= params.idleThreshold) {
                    // 還在抬腳但不在窗內（太小或太大）→ 重新計時
                    windowHoldSec = 0f
                    holdSec = 0f
                }

                // 放下（回到 idle）才結算成敗
                if (angle < params.idleThreshold) {
                    Logger.state("CALF", state.name, "COOLDOWN", angle)
                    finalizeRep(successOverride = windowHoldSec >= params.holdSeconds)
                    state = S.COOLDOWN
                }
            }
            S.COOLDOWN -> {
                // 防抖：要求回到很低的角度後再回 IDLE
                if (angle < params.idleThreshold * 0.7f) {
                    Logger.state("CALF", state.name, "IDLE", angle)
                    state = S.IDLE
                }
            }
            S.CALIB -> {
                // 已在上面處理校正並 return FrameHud，因此這裡不需要再做事。
                // 保留這個空分支，只是為了讓 when 覆蓋所有 enum 值，避免編譯器報「must be exhaustive」。
            }
        }

        val hud = FrameHud(
            angleDeg = angle,
            state = state.name,
            holdSec = holdSec,
            success = success,
            fail = fail,
            extra = mapOf(
                "baselineY" to baselineY,
                "heelLiftPx" to heelLift,
                "peakDeg" to peakDeg,
                "toeX" to toeX, "toeY" to toeY, "heelX" to heelX, "heelY" to heelY,
                "fps" to smoothFps(nowNanos),
                "holdTarget" to params.holdSeconds
            )
        )
        diagnostics?.frame(angle, state.name, holdSec, baselineY, heelLift)
        return hud
    }

    private fun finalizeRep(successOverride: Boolean? = null) {
        diagnostics?.event("CALF_FINALIZE", mapOf("base" to baseDeg, "peak" to peakDeg, "holdSec" to windowHoldSec))
        repId++

        val outcome = when (successOverride) {
            true  -> { success++; "SUCCESS" }
            false -> { fail++;    "FAIL_HOLD_SHORT" }
            else  -> { // 後備（理論上不會走到）
                if (windowHoldSec >= params.holdSeconds) { success++; "SUCCESS" }
                else                                      { fail++;    "FAIL_HOLD_SHORT" }
            }
        }

        repLogs += RepLog(
            id = repId,
            baseDeg = baseDeg,
            peakDeg = peakDeg,
            holdSec = windowHoldSec,   // 紀錄真正「連續窗內」的秒數
            outcome = outcome,
            side = null
        )
        Logger.logCalf(repId, baseDeg, peakDeg, windowHoldSec, outcome)

        // 重置回合
        baseDeg = 0f
        peakDeg = 0f
        holdSec = 0f
        windowHoldSec = 0f
    }


    private fun commitFail(reason: String, hold: Float) {
        repId++
        fail++
        repLogs += RepLog(
            id = repId,
            baseDeg = baseDeg,
            peakDeg = peakDeg,
            holdSec = hold,      // 把當下有效的窗內秒數寫入
            outcome = reason
        )
        Logger.logCalf(repId, baseDeg, peakDeg, hold, reason)

        baseDeg = 0f
        peakDeg = 0f
        holdSec = 0f
        windowHoldSec = 0f
    }


    private fun pickBestToe(lm: PoseLandmarks): PosePoint? =
        lm.points["left_foot_index"] ?: lm.points["right_foot_index"]

    private fun pickBestHeel(lm: PoseLandmarks): PosePoint? =
        lm.points["left_heel"] ?: lm.points["right_heel"]


    private fun lastDtSec(nowNs: Long): Float {
        val prev = lastTsNs ?: run { lastTsNs = nowNs; return 0f }
        val dt = (nowNs - prev).coerceAtLeast(0L)
        lastTsNs = nowNs
        return dt / 1_000_000_000f
    }


    private fun smoothFps(nowNs: Long): Float {
        val prev = lastFpsTsNs ?: run { lastFpsTsNs = nowNs; return 0f }
        val dt = (nowNs - prev).coerceAtLeast(1L)
        val inst = 1_000_000_000f / dt
        avgFps = if (avgFps == 0f) inst else (0.1f * inst + 0.9f * avgFps)
        lastFpsTsNs = nowNs
        return avgFps
    }
}
