package rehabcore.domain

import android.util.Log
import java.util.Locale

object Logger {
    private const val TAG = "Rehab"

    fun logCalf(id: Int, base: Float?, peak: Float?, holdSec: Float, outcome: String) {
        val msg = buildString {
            append("[CALF LOG] ")
            append("id=").append(id).append(' ')
            append("base=").append(fmt(base)).append(' ')
            append("peak=").append(fmt(peak)).append(' ')
            append("hold=").append(String.format(Locale.US, "%.2f", holdSec)).append(' ')
            append("outcome=").append(outcome)
        }
        Log.i(TAG, msg)      // 不要用命名參數
    }

    fun logSquat(minAngle: Float, outcome: String) {
        val msg = "[SQUAT LOG] min=${String.format(Locale.US, "%.1f", minAngle)} outcome=$outcome"
        Log.i(TAG, msg)
    }

    private fun fmt(v: Float?): String =
        if (v == null) "--" else String.format(Locale.US, "%.1f", v)

    // 新增：狀態轉換診斷
    fun state(tag:String, from:String, to:String, angle:Float?) {
        val a = angle?.let { "%.1f".format(it) } ?: "--"
        Log.d(TAG, "[$tag STATE] $from -> $to @angle=$a")
    }
}