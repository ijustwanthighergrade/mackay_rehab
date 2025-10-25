data class CalfParams(
    // —— 變化量（Δangle）判定 —— //
    val aMin: Float = 7.5f,          // 成功窗口：最低 Δ角
    val aMax: Float = 45f,           // 成功窗口：最高 Δ角
    val idleThreshold: Float = 6f,   // 視為「放下」的 Δ角
    val holdSeconds: Float = 2.5f,   // 必須連續維持在 [aMin, aMax] 的秒數
    val emaAlpha: Float = 0.35f,     // 角度 EMA 平滑係數
    val angleNoiseMax: Float = 60f,  // 絕對角鉗制上限，抑制尖峰

    // 起跳 gating（.py 的「先休息再起跳」）
    val raiseEnterDeg: Float = 12f,  // 從 IDLE 允許進 RAISING 的起跳角（絕對角，用於起跳瞬間）
    val restNeedSec: Float = 0.25f,  // 進 RAISING 前需在低角度休息的秒數

    // —— 初次鎖基線（CALIB：站穩） —— //
    val standStillFrames: Int = 8,
    val standStillSpeedPx: Float = 8f,
    val standStillSlopeMaxDeg: Float = 20f,
    val standStillAngleMax: Float = 6f,
    val maxCalibWaitMs: Long = 2500L,
    val minFootLenPx: Float = 40f,
    val minFootLenRatio: Float = 0.08f,

    // 保留舊式水平基線參數（相容用途；本實作不依賴）
    val calibFrames: Int = 10,
    val calibJitterPx: Float = 10f,
    val calibJitterRatio: Float = 0.012f,

    // —— IDLE 期間的兩段式基線更新 —— //
    val softDriftPx: Float = 15f,
    val softLenDriftRatio: Float = 0.25f,
    val softSlopeMaxDeg: Float = 25f,

    val hardDriftPx: Float = 40f,
    val hardLenDriftRatio: Float = 0.50f,
    val hardSlopeMaxDeg: Float = 45f,

    val recalibFastFrames: Int = 4,
    val recalibFastTimeoutMs: Long = 1200L,
    val recalibCooldownAfterIdleMs: Long = 1200L,
    val recalibNeedStationaryFrames: Int = 6,
    val recalibRequireConsecFrames: Int = 8,

    // 其他
    val enforceToeGround: Boolean = true
)