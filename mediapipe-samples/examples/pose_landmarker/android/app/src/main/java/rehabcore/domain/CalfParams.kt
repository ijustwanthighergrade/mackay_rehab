package rehabcore.domain

data class CalfParams(
    val aMin: Float = 7.5f,     // 依你目前實際 run 的設定
    val aMax: Float = 45f,
    val holdSeconds: Float = 3.0f,
    val emaAlpha: Float = 0.35f,
    val idleThreshold: Float = 8f,
    val calibFrames: Int = 45,
    val calibJitterPx: Float = 6f,
    val enforceToeGround: Boolean = true
)