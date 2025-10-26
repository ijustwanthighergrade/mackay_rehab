data class CalfParams(
    // —— 變化量（Δangle）判定 —— //
    val aMin: Float = 7.5f,          // 成功窗口：最低 Δ角
    val aMax: Float = 60f,           // 成功窗口：最高 Δ角
    val idleThreshold: Float = 6f,   // 視為「放下」的 Δ角
    val holdSeconds: Float = 3f,   // 必須連續維持在 [aMin, aMax] 的秒數
    val emaAlpha: Float = 0.35f,     // 角度 EMA 平滑係數
    val angleNoiseMax: Float = 60f,  // 絕對角鉗制上限，抑制尖峰

    // 起跳 gating（.py 的「先休息再起跳」）
    val raiseEnterDeg: Float = 3f,  // 從 IDLE 允許進 RAISING 的起跳角（絕對角，用於起跳瞬間）
    val restNeedSec: Float = 0.15f,  // 進 RAISING 前需在低角度休息的秒數

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
    val enforceToeGround: Boolean = false,
    // —— HOLDING 離場條件（抗抖動）—— //
    val holdDropGraceSec: Float = 0.25f,   // 連續低於 idleThreshold 的寬限秒數
    val holdExitBelowFrames: Int = 2,      // 或連續幾幀都低於 idleThreshold

    // CalfParams.kt — 加在檔案最後一段「其他」前後皆可
    val holdBandToleranceDeg: Float = 5f,  // 計時視窗的雙向寬容度
    val allowOvershootHold: Boolean = true, // 超過 aMax 但不離譜時仍持續計時
    val fastRaiseOverrideSec: Float = 1.2f,  // 若休息未達標，但 Δ角瞬間達到 aMin+2° 且持續 > 這段時間，仍允許進 RAISING
// —— 冷卻期離開條件 —— //
    val cooldownExitFrames: Int = 2,   // 連續幀數低於 idleThreshold 即回到 IDLE
    val cooldownMaxSec: Float = 1.0f,  // 最長等待秒數，超時也回到 IDLE

// —— 統計策略 —— //
    val countSmallAsFail: Boolean = true, // Δ角< aMin 但維持≥holdSeconds 是否算失敗（預設不算）

    val toeLiftMaxRatio: Float = 0.06f // 由 3% 放寬到 6% 足長

)