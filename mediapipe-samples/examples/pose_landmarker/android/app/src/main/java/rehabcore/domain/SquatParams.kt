package rehabcore.domain

data class SquatParams(
    val standUpDeg: Float = 170f,
    val succMinDeg: Float = 95f,
    val succMaxDeg: Float = 135f,
    val failMinDeg: Float = 136f,
    val failMaxDeg: Float = 162f,
    val emaAlpha: Float = 0.35f,
    val smoothN: Int = 5
)