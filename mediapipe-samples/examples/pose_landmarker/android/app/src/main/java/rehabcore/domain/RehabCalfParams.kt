package rehabcore.domain

data class RehabCalfParams(
    val emaAlpha: Float = 0.35f,       // 平滑因子
    val holdSeconds: Float = 3.0f,     // 維持秒數
    val raiseEnterDeg: Float = 3f,    // 進入抬升角閾值
    val holdMinDeg: Float = 5f,       // 成功維持區起始角
    val lowerExitDeg: Float = 6f,     // 提前放下角度閾值
    val idleThreshold: Float = 3f,     // 靜止角閾值
    val maxLowerSpeedDeg: Float = 40f, // 每秒下降角速度上限
)