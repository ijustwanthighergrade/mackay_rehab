package rehabcore.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PosePoint(val x: Float, val y: Float, val z: Float, val visibility: Float?)
data class PoseLandmarks(val points: Map<String, PosePoint>) // 例如 "left_knee", "right_ankle" ...

data class FrameHud(
    val angleDeg: Float?,           // 例如：膝角 or atan2(h/L)
    val state: String,              // IDLE/RAISING/HOLDING/...
    val holdSec: Float,             // 當前已保持秒
    val success: Int,
    val fail: Int,
    val extra: Map<String, Any?> = emptyMap()
)
@Serializable
data class RepLog(                 // 對應 Python 的 [CALF LOG] / [SQUAT LOG]
    val id: Int,
    val baseDeg: Float?,
    val peakDeg: Float?,
    val holdSec: Float,
    val outcome: String,
    val side: String? = null,
    val minAngleThisRep: Float? = null // 深蹲可用
)

@Serializable
sealed class ParamValue {
    @Serializable data class S(val v: String): ParamValue()
    @Serializable data class I(val v: Int): ParamValue()
    @Serializable data class F(val v: Float): ParamValue()
    @Serializable data class B(val v: Boolean): ParamValue()
}
@Serializable
data class SessionResult(
    val action: String,
    val fps: Float,
    val params: Map<String, ParamValue> = emptyMap(),
    val success: Int,
    val fail: Int,
    val total: Int,
    val successRate: Float,
    val reps: List<RepLog>,
    val framesSampled: Int
)
