package rehabcore.domain
import CalfParams
import rehabcore.domain.model.*
import kotlin.math.*

class CalfDetector(
    private val params: CalfParams = CalfParams(),
    private val diagnostics: Diagnostics? = null
) : Detector {

    private enum class S { CALIB, IDLE, RAISING, HOLDING, COOLDOWN }

    private var state = S.CALIB
    private var emaAngle: Float? = null
    private var frames = 0
    private var lastTsNs: Long? = null
    private var avgFps: Float = 0f
    private var framesSampled = 0

    // 角度紀錄（注意：peakDeg 將改為「Δ角峰值」）
    private var baseDeg = 0f        // 回合起點的「絕對角」（repBaseDeg 的副本，用於輸出）
    private var peakDeg = 0f        // 回合內「Δ角」的峰值（用於輸出/判定）
    private var holdSec = 0f
    private var repId = 0

    private var success = 0
    private var fail = 0
    private val repLogs = mutableListOf<RepLog>()

    // —— 基線（toe↔heel 斜線） —— //
    private var baseToeX: Float? = null
    private var baseToeY: Float? = null
    private var baseHeelX: Float? = null
    private var baseHeelY: Float? = null
    private var footLenPx: Float? = null     // 足長 L（pixel）

    private var windowHoldSec = 0f
    private var lastFpsTsNs: Long? = null
    private var smallHoldSec = 0f

    // 鎖定使用哪一隻腳（"left"/"right"）
    private var footSide: String? = null

    // —— CALIB（站穩判定） —— //
    private var stableCount = 0
    private var prevToeX: Float? = null
    private var prevToeY: Float? = null
    private var prevHeelX: Float? = null
    private var prevHeelY: Float? = null
    private var prevCalibNs: Long? = null
    private var calibStartMs: Long? = null

    // —— 兩段式基線更新（IDLE） —— //
    private var inFastRecalib: Boolean = false
    private var allowRecalibAfterMs: Long = 0L
    private var hardBreachStreak: Int = 0
    private var idleSinceMs = 0L
    private var lastCalibAtMs = 0L
    private var lastToeX = 0f; private var lastToeY = 0f
    private var lastHeelX = 0f; private var lastHeelY = 0f
    private var stationaryConsec = 0
    private var hardDriftConsec = 0
    private var softDriftConsec = 0

    // —— 起跳 gating（.py 行為） —— //
    private var restFrames = 0
    private var canRaise = false

    // —— Δ角用：回合起始絕對角（在進 RAISING 那一刻鎖定） —— //
    private var repBaseDeg: Float = 0f
    // —— 冷卻期追蹤 —— //
    private var cooldownLowFrames = 0
    private var cooldownStartMs: Long? = null

    private var raiseStartMs: Long = 0L
    private var minAbsDuringRaise: Float = Float.MAX_VALUE

    // —— HOLDING 抗抖動 —— //
    private var holdLowFrames = 0
    private var holdLowStartMs = 0L

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

        baseToeX = null; baseToeY = null
        baseHeelX = null; baseHeelY = null
        footLenPx = null

        windowHoldSec = 0f
        smallHoldSec = 0f

        // CALIB
        stableCount = 0
        prevToeX = null; prevToeY = null
        prevHeelX = null; prevHeelY = null
        prevCalibNs = null
        calibStartMs = null

        // Recalib
        inFastRecalib = false
        allowRecalibAfterMs = 0L
        hardBreachStreak = 0
        idleSinceMs = 0L
        lastCalibAtMs = 0L
        lastToeX = 0f; lastToeY = 0f
        lastHeelX = 0f; lastHeelY = 0f
        stationaryConsec = 0
        hardDriftConsec = 0
        softDriftConsec = 0

        // 起跳 gating
        restFrames = 0
        canRaise = false

        // Δ角
        repBaseDeg = 0f
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
        frames++
        framesSampled++
        if (lastTsNs == null) lastTsNs = nowNanos

        val ptToe = pickBestToe(landmarks) ?: return FrameHud(null, state.name, holdSec, success, fail)
        val ptHeel = pickBestHeel(landmarks) ?: return FrameHud(null, state.name, holdSec, success, fail)

        val toeX = ptToe.x * width
        val toeY = ptToe.y * height
        val heelX = ptHeel.x * width
        val heelY = ptHeel.y * height

        // ===== CALIB：站穩後鎖 toe↔heel 基線 =====
        if (state == S.CALIB) {
            val nowNs = nowNanos
            val dtNs = prevCalibNs?.let { nowNs - it } ?: 0L
            val dtSec = if (dtNs > 0) dtNs / 1_000_000_000f else 0f

            val toeSpeed = if (dtSec > 0f && prevToeX != null && prevToeY != null)
                hypot(toeX - prevToeX!!, toeY - prevToeY!!) / dtSec else 0f
            val heelSpeed = if (dtSec > 0f && prevHeelX != null && prevHeelY != null)
                hypot(heelX - prevHeelX!!, heelY - prevHeelY!!) / dtSec else 0f

            prevToeX = toeX; prevToeY = toeY
            prevHeelX = heelX; prevHeelY = heelY
            prevCalibNs = nowNs

            val slopeDeg = abs(atan2(heelY - toeY, heelX - toeX) * 180f / Math.PI.toFloat())
            val Lraw = hypot(heelX - toeX, heelY - toeY).toFloat().coerceAtLeast(1f)
            val heelLiftProxy = abs(heelY - toeY)
            val angleProxy = atan2(heelLiftProxy, Lraw) * 180f / Math.PI.toFloat()


            if (calibStartMs == null) calibStartMs = System.currentTimeMillis()
            val waitedMs = System.currentTimeMillis() - calibStartMs!!

            val framesNeed = if (inFastRecalib) params.recalibFastFrames else params.standStillFrames
            val timeoutMs  = if (inFastRecalib) params.recalibFastTimeoutMs else params.maxCalibWaitMs

            val stillEnough =
                toeSpeed  <= params.standStillSpeedPx &&
                        heelSpeed <= params.standStillSpeedPx &&
                        slopeDeg  <= params.standStillSlopeMaxDeg &&
                        angleProxy <= params.standStillAngleMax

            stableCount = if (stillEnough) stableCount + 1 else 0

            val okByFrames  = stableCount >= framesNeed
            val okByTimeout = waitedMs >= timeoutMs &&
                    toeSpeed  <= params.standStillSpeedPx * 2f &&
                    heelSpeed <= params.standStillSpeedPx * 2f
            val longEnough  = Lraw >= params.minFootLenPx
            val okByFallback = waitedMs >= 4000L && longEnough

            if ((okByFrames || okByTimeout || okByFallback) && longEnough) {
                baseToeX = toeX;  baseToeY = toeY
                baseHeelX = heelX; baseHeelY = heelY
                footLenPx = Lraw
                recordStateChange(S.CALIB, S.IDLE, 0f)
                enterIdle(0f, toeX, toeY, heelX, heelY)
                inFastRecalib = false
                stableCount = 0
                prevToeX = null; prevToeY = null; prevHeelX = null; prevHeelY = null
                prevCalibNs = null
                calibStartMs = null
            }

            return FrameHud(
                angleDeg = null,
                state = state.name,
                holdSec = 0f,
                success = success,
                fail = fail,

                extra = mapOf(
                    "repBaseDeg" to repBaseDeg,
                    // ★ 新增：即時 toe/heel（給 CalfOverlay 用）
                    // 基線資訊
                    "toeX" to toeX, "toeY" to toeY,
                    "heelX" to heelX, "heelY" to heelY,
                    "heelLiftPx" to Lraw,

                    // 其他
                    "peakDeltaDeg" to peakDeg,
                    "fps" to smoothFps(nowNanos),
                    "holdTarget" to params.holdSeconds,
                    "smallHoldSec" to smallHoldSec,
                    "canRaise" to canRaise,
                    "restFrames" to restFrames
                )
            )
        }

        // ===== 計算絕對角（相對基線） → EMA → 鉗制 → Δ角 =====
        val L = footLenPx ?: return FrameHud(null, state.name, holdSec, success, fail)
        val ax = baseToeX ?: return FrameHud(null, state.name, holdSec, success, fail)
        val ay = baseToeY ?: return FrameHud(null, state.name, holdSec, success, fail)
        val bx = baseHeelX ?: return FrameHud(null, state.name, holdSec, success, fail)
        val by = baseHeelY ?: return FrameHud(null, state.name, holdSec, success, fail)

// 基線 AB（校正鎖定），P = 當前 heel
        val ABx = bx - ax
        val ABy = by - ay
        val APx = heelX - ax
        val APy = heelY - ay

// heel 對基線之垂距（像素）
        val cross    = abs(APx * ABy - APy * ABx)
        val heelLift = cross / max(1f, hypot(ABx, ABy))


        if (params.enforceToeGround && state == S.HOLDING) {
            val APx_t = toeX - ax; val APy_t = toeY - ay
            val cross_t = abs(APx_t * ABy - APy_t * ABx)
            val toeLift = (cross_t / max(1f, hypot(ABx, ABy))).toFloat()
            val toeLiftLimit = max(params.calibJitterPx, L * params.toeLiftMaxRatio)
            if (toeLift > toeLiftLimit) {
                commitFail("TOE_OFF_GROUND", windowHoldSec)
                state = S.COOLDOWN
                cooldownStartMs = System.currentTimeMillis()
                cooldownLowFrames = 0
                holdSec = 0f; windowHoldSec = 0f; smallHoldSec = 0f
            }
        }

        // 當前「絕對角」（度）
        val thetaAbs = atan2(heelLift, L) * 180f / Math.PI.toFloat()

        // EMA 平滑 + 上限鉗制
        emaAngle = if (emaAngle == null) thetaAbs else (params.emaAlpha * thetaAbs + (1 - params.emaAlpha) * emaAngle!!)
        val absAngle = min(emaAngle!!, params.angleNoiseMax)   // 絕對角（鉗制後）

        // Δ角（以回到 IDLE 時鎖的基準角為 0）
        val deltaDeg = max(0f, absAngle - repBaseDeg)
        val deltaRaw = max(0f, thetaAbs - repBaseDeg)  // 用原始角判斷是否已達 7.5°

        val dt = lastDtSec(nowNanos)

        // ===== 狀態機（以 Δ角判斷） =====
        when (state) {
            S.IDLE -> {
                maybeRefreshBaselineInIdle(absAngle, toeX, toeY, heelX, heelY, L)
                if (state == S.CALIB) return FrameHud(null, "CALIB", 0f, success, fail)

                // 起跳 gating：低角度休息一段時間
                val restNeedFrames = max(2, (params.restNeedSec * smoothFps(nowNanos)).toInt()) // ★ 修正：用平滑 fps，且最低只需 2 幀
                if (deltaDeg <= params.idleThreshold) {
                    if (restFrames < restNeedFrames) restFrames++
                    if (restFrames >= restNeedFrames) canRaise = true
                } else {
                    if (deltaDeg > params.idleThreshold + 1.5f) {
                        restFrames = 0
                        // ★ 修正：不要馬上把 canRaise 打回 false，保留 0.3 秒寬限（避免一點點雜訊失去資格）
                        if (canRaise) {
                            // do nothing; 在短時間內保留 canRaise 資格
                        } else {
                            canRaise = false
                        }
                    }
              }

                holdSec = 0f; windowHoldSec = 0f; smallHoldSec = 0f
                baseDeg = repBaseDeg    // 顯示/輸出時的回合基準角

            // ★ 修正：兩條路可以進 RAISING
            // 1) 正常：已休息夠，且 Δ角 ≥ max(idleThreshold, raiseEnterDeg)
            // 2) 例外：尚未滿足休息，但 Δ角已達 aMin+2° 且持續 > fastRaiseOverrideSec（快速抬腳的使用者）
                val raiseEnter = max(params.raiseEnterDeg, params.idleThreshold)
                val canEnterByRest = canRaise && deltaDeg >= raiseEnter
                val canEnterByFast = deltaDeg >= (params.aMin + 2f)
                if (canEnterByRest || canEnterByFast) {
                    recordStateChange(state, S.RAISING, 0f)
                    state = S.RAISING
                    baseDeg = repBaseDeg
                    peakDeg = 0f
                    restFrames = 0
                    canRaise = false
                    raiseStartMs = System.currentTimeMillis()
                    minAbsDuringRaise = absAngle
                }
            }

            S.RAISING -> {
                peakDeg = max(peakDeg, deltaDeg)
// ★ 起跳後前 0.25s 把回合基準往下校正到這段時間的最小角，避免一開始站姿不夠平
                minAbsDuringRaise = min(minAbsDuringRaise, absAngle)
                val raiseElapsed = (System.currentTimeMillis() - raiseStartMs) / 1000f
                if (raiseElapsed <= 0.25f) {
                    if (minAbsDuringRaise < repBaseDeg) {
                        repBaseDeg = minAbsDuringRaise
                    }
                }

                val inSmall = deltaDeg >= params.idleThreshold && deltaDeg < params.aMin
                if (inSmall) {
                    smallHoldSec += dt
                    if (smallHoldSec >= params.holdSeconds) {
                        if (params.countSmallAsFail) {
                            commitFail("FAIL_SMALL_KEPT", smallHoldSec) // 只有打開參數才記為失敗
                        }
                        recordStateChange(state, S.COOLDOWN, deltaDeg)
                        state = S.COOLDOWN
                        cooldownStartMs = System.currentTimeMillis()
                        cooldownLowFrames = 0
                        smallHoldSec = 0f; holdSec = 0f; windowHoldSec = 0f
                    }
                }

                // ★ 用原始角 deltaRaw 進 HOLDING，避免 EMA 把門檻抹掉
                if (!inSmall && deltaRaw  >= params.aMin) {
                    recordStateChange(state, S.HOLDING, deltaRaw)
                    state = S.HOLDING
                    windowHoldSec = 0f
                    holdSec = 0f
                    smallHoldSec = 0f
                } else if (!inSmall && deltaDeg < params.idleThreshold) {
                    recordStateChange(state, S.IDLE, deltaDeg)
                    enterIdle(absAngle, toeX, toeY, heelX, heelY)
                    holdSec = 0f; windowHoldSec = 0f; smallHoldSec = 0f
                }
            }

            S.HOLDING -> {
                peakDeg = max(peakDeg, deltaDeg)

                val tol = params.holdBandToleranceDeg
                val inBand = deltaDeg >= (params.aMin - tol) && deltaDeg <= (params.aMax + tol)

                if (inBand) {
                    windowHoldSec += dt
                    holdSec = windowHoldSec
                } else if (deltaDeg >= params.idleThreshold) {
                    // 超出窗口但仍高於 idleThreshold：視為仍在抬腳，暫停計時但不清零
                    if (deltaDeg > params.aMax + tol) { // 只有嚴重超標才歸零
                        windowHoldSec = 0f
                        holdSec = 0f
                    }
                    // 只要 >= idleThreshold 就把「低角統計」重置
                    holdLowFrames = 0
                    holdLowStartMs = 0L
                }

                // ★ 抗抖動：連續低於 idleThreshold 才離場
                if (deltaDeg < params.idleThreshold) {
                    holdLowFrames++
                    if (holdLowStartMs == 0L) holdLowStartMs = System.currentTimeMillis()
                    val lowElapsed = (System.currentTimeMillis() - holdLowStartMs) / 1000f

                    if (holdLowFrames >= params.holdExitBelowFrames || lowElapsed >= params.holdDropGraceSec) {
                        recordStateChange(state, S.COOLDOWN, deltaDeg)
                        finalizeRep(successOverride = windowHoldSec >= params.holdSeconds)
                        state = S.COOLDOWN
                        cooldownStartMs = System.currentTimeMillis()
                        cooldownLowFrames = 0
                        holdLowFrames = 0
                        holdLowStartMs = 0L
                    }
                } else {
                    // 回到 >= idleThreshold，低角統計清零
                    holdLowFrames = 0
                    holdLowStartMs = 0L
                }
            }


            S.COOLDOWN -> {
                // ★ 更寬鬆的退場條件 + 上限秒數，避免卡死在 COOLDOWN
                if (cooldownStartMs == null) cooldownStartMs = System.currentTimeMillis()
                val elapsed = (System.currentTimeMillis() - cooldownStartMs!!) / 1000f

                val lowEnough = deltaDeg < params.idleThreshold // 低於 idleThreshold (=6°) 即可
                val timeout   = elapsed >= 1.0f                 // 或滿 1 秒強制回 IDLE

                if (lowEnough || timeout) {
                    recordStateChange(state, S.IDLE, deltaDeg)
                    enterIdle(absAngle, toeX, toeY, heelX, heelY)
                    cooldownStartMs = null
                }
            }


            S.CALIB -> { /* 已在上面 return */ }
        }

        // ===== HUD =====
        val hud = FrameHud(
            angleDeg = deltaDeg,  // 顯示 Δ角（更符合你的需求）
            state = state.name,
            holdSec = holdSec,
            success = success,
            fail = fail,
            extra = mapOf(
                "toeX" to toeX, "toeY" to toeY,
                "heelX" to heelX, "heelY" to heelY,

                // 基線資訊
                "baseToeX" to ax, "baseToeY" to ay,
                "baseHeelX" to bx, "baseHeelY" to by,
                "heelLiftPx" to heelLift,
                // 角度資訊（同時輸出絕對角供除錯）
                "absAngle" to absAngle,
                "deltaDeg" to deltaDeg,
                "repBaseDeg" to repBaseDeg,
                // 其他
                "peakDeltaDeg" to peakDeg,
                "fps" to smoothFps(nowNanos),
                "holdTarget" to params.holdSeconds,
                "smallHoldSec" to smallHoldSec,
                "canRaise" to canRaise,
                "restFrames" to restFrames
            )
        )
        return hud
    }

    private fun finalizeRep(successOverride: Boolean? = null) {
        diagnostics?.event("CALF_FINALIZE", mapOf("baseAbs" to baseDeg, "peakDelta" to peakDeg, "holdSec" to windowHoldSec))
        repId++

        val outcome = when (successOverride) {
            true  -> { success++; "SUCCESS" }
            false -> { fail++;    "FAIL_HOLD_SHORT" }
            else  -> if (windowHoldSec >= params.holdSeconds) { success++; "SUCCESS" } else { fail++; "FAIL_HOLD_SHORT" }
        }

        repLogs += RepLog(
            id = repId,
            baseDeg = baseDeg,      // 回合起點「絕對角」
            peakDeg = peakDeg,      // 回合內「Δ角峰值」
            holdSec = windowHoldSec,
            outcome = outcome,
            side = null,
            minAngleThisRep = null,
            epochMs = System.currentTimeMillis()
        )
        Logger.logCalf(repId, baseDeg, peakDeg, windowHoldSec, outcome)

        // 重置回合
        baseDeg = 0f
        peakDeg = 0f
        holdSec = 0f
        windowHoldSec = 0f
    }

    private fun commitFail(reason: String, hold: Float) {
        repId++; fail++
        repLogs += RepLog(
            id = repId,
            baseDeg = baseDeg,
            peakDeg = peakDeg,
            holdSec = hold,
            outcome = reason,
            side = null,
            minAngleThisRep = null,
            epochMs = System.currentTimeMillis()
        )
        Logger.logCalf(repId, baseDeg, peakDeg, hold, reason)

        baseDeg = 0f
        peakDeg = 0f
        holdSec = 0f
        windowHoldSec = 0f
    }

    // —— IDLE 期間：Soft Refresh / Fast Recalib —— //
    private fun maybeRefreshBaselineInIdle(
        absAngle: Float,
        toeX: Float, toeY: Float,
        heelX: Float, heelY: Float,
        footLenPxNow: Float,
        now: Long = System.currentTimeMillis()
    ) {
        if (now < allowRecalibAfterMs) return
        if (now - idleSinceMs < params.recalibCooldownAfterIdleMs) return
        if (absAngle > params.idleThreshold * 0.6f) return

        val toeV = hypot(toeX - lastToeX, toeY - lastToeY)
        val heelV = hypot(heelX - lastHeelX, heelY - lastHeelY)
        val pxPerFrame = max(toeV, heelV)
        if (pxPerFrame <= max(1f, footLenPxNow * 0.005f)) {
            stationaryConsec++
        } else {
            stationaryConsec = 0
        }
        if (stationaryConsec < params.recalibNeedStationaryFrames) {
            lastToeX = toeX; lastToeY = toeY
            lastHeelX = heelX; lastHeelY = heelY
            return
        }

        val driftPx = baselineDriftPx(toeX, toeY, heelX, heelY)
        val slopeDeg = baselineSlopeDeg(toeX, toeY, heelX, heelY)
        val lenRatio = baselineLenRatio(toeX, toeY, heelX, heelY)

        val softHit = driftPx > max(params.softDriftPx, footLenPxNow * params.softLenDriftRatio) ||
                abs(slopeDeg) > params.softSlopeMaxDeg
        val hardHit = driftPx > max(params.hardDriftPx, footLenPxNow * params.hardLenDriftRatio) ||
                abs(slopeDeg) > params.hardSlopeMaxDeg

        softDriftConsec = if (softHit) softDriftConsec + 1 else 0
        hardDriftConsec = if (hardHit) hardDriftConsec + 1 else 0

        if (hardDriftConsec >= params.recalibRequireConsecFrames) {
            enterCalib()
            lastCalibAtMs = now
            hardDriftConsec = 0
            softDriftConsec = 0
            stationaryConsec = 0
            allowRecalibAfterMs = now + params.recalibCooldownAfterIdleMs
        }

        // Soft refresh：小幅漂移時，以 EMA 拉回
        if (!hardHit && softHit && baseToeX != null && baseHeelX != null) {
            val a = 0.2f
            baseToeX  = a * toeX  + (1f - a) * baseToeX!!
            baseToeY  = a * toeY  + (1f - a) * baseToeY!!
            baseHeelX = a * heelX + (1f - a) * baseHeelX!!
            baseHeelY = a * heelY + (1f - a) * baseHeelY!!
            footLenPx = hypot(baseHeelX!! - baseToeX!!, baseHeelY!! - baseToeY!!).toFloat()
        }

        lastToeX = toeX; lastToeY = toeY
        lastHeelX = heelX; lastHeelY = heelY
    }

    private fun enterCalib() {
        state = S.CALIB
        inFastRecalib = true
        calibStartMs = null
        stableCount = 0
        prevToeX = null; prevToeY = null
        prevHeelX = null; prevHeelY = null
        prevCalibNs = null
    }
    // 與既有基線端點（baseToe, baseHeel）的最大位移量（像素）
    private fun baselineDriftPx(toeX: Float, toeY: Float, heelX: Float, heelY: Float): Float {
        val ax = baseToeX ?: return Float.MAX_VALUE
        val ay = baseToeY ?: return Float.MAX_VALUE
        val bx = baseHeelX ?: return Float.MAX_VALUE
        val by = baseHeelY ?: return Float.MAX_VALUE
        val dToe  = kotlin.math.hypot((toeX - ax).toDouble(),  (toeY - ay).toDouble()).toFloat()
        val dHeel = kotlin.math.hypot((heelX - bx).toDouble(), (heelY - by).toDouble()).toFloat()
        return max(dToe, dHeel)
    }

    // 目前 toe→heel 與水平線的夾角（度），用來判斷是否過度傾斜
    private fun baselineSlopeDeg(toeX: Float, toeY: Float, heelX: Float, heelY: Float): Float {
        val rad = kotlin.math.atan2((heelY - toeY).toDouble(), (heelX - toeX).toDouble())
        return abs((rad * 180.0 / Math.PI).toFloat())
    }

    // 目前足長相對於鎖定時足長的相對變化（比例）
    private fun baselineLenRatio(toeX: Float, toeY: Float, heelX: Float, heelY: Float): Float {
        val L0 = footLenPx ?: return Float.MAX_VALUE
        val L  = kotlin.math.hypot((heelX - toeX).toDouble(), (heelY - toeY).toDouble()).toFloat()
        return abs(L - L0) / max(1f, L0)
    }

    private fun pickBestToe(lm: PoseLandmarks): PosePoint? {
        val l = lm.points["left_foot_index"]
        val r = lm.points["right_foot_index"]
        return when (footSide) {
            "left"  -> l ?: r
            "right" -> r ?: l
            else -> {
                val cand = listOfNotNull(
                    l?.let { "left" to it },
                    r?.let { "right" to it }
                ).maxByOrNull { (_, p) -> ((p.visibility ?: 0f) * 1000f) + p.y }
                footSide = cand?.first
                cand?.second
            }
        }
    }

    private fun pickBestHeel(lm: PoseLandmarks): PosePoint? {
        val l = lm.points["left_heel"]
        val r = lm.points["right_heel"]
        return when (footSide) {
            "left"  -> l ?: r
            "right" -> r ?: l
            else -> {
                val cand = listOfNotNull(
                    l?.let { "left" to it },
                    r?.let { "right" to it }
                ).maxByOrNull { (_, p) -> ((p.visibility ?: 0f) * 1000f) + p.y }
                footSide = cand?.first
                cand?.second
            }
        }
    }

    private fun enterIdle(
        absAngleNow: Float,
        toeX: Float? = null, toeY: Float? = null,
        heelX: Float? = null, heelY: Float? = null
    ) {
        state = S.IDLE
        val now = System.currentTimeMillis()
        idleSinceMs = now
        allowRecalibAfterMs = now + params.recalibCooldownAfterIdleMs
        hardDriftConsec = 0
        softDriftConsec = 0
        stationaryConsec = 0
        toeX?.let { lastToeX = it }; toeY?.let { lastToeY = it }
        heelX?.let { lastHeelX = it }; heelY?.let { lastHeelY = it }
// reset gating/timers for next cycle
        restFrames = 0
        canRaise = false
        windowHoldSec = 0f
        holdSec = 0f
        smallHoldSec = 0f

//        // ★ 新增：在休息狀態鎖定下一輪 Δ角基準
//        repBaseDeg = if (emaAngle != null) emaAngle!!.coerceAtLeast(0f) else absAngleNow.coerceAtLeast(0f)
//        baseDeg = repBaseDeg
        // 改成（僅在回到低角度時才鎖基準；否則沿用上一輪基準）
        val lowNow = absAngleNow <= params.idleThreshold + 0.5f
        repBaseDeg = if (lowNow) (emaAngle ?: absAngleNow).coerceAtLeast(0f) else repBaseDeg

//        repBaseDeg = if (lowNow) {
//            (emaAngle ?: absAngleNow).coerceAtLeast(0f)
//        } else {
//            // 仍偏高：暫不更新，避免把“非休息位”當作新基準
//            repBaseDeg
//        }
        baseDeg = repBaseDeg
    }

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

    private fun recordStateChange(from: S, to: S, deltaNow: Float) {
        repLogs += RepLog(
            id = repId,
            baseDeg = null,
            peakDeg = deltaNow,   // 記錄當下 Δ角，方便追蹤
            holdSec = 0f,
            outcome = "STATE_${from.name}_TO_${to.name}",
            side = null,
            minAngleThisRep = null,
            epochMs = System.currentTimeMillis()
        )
        Logger.state("CALF", from.name, to.name, deltaNow)
    }
}
