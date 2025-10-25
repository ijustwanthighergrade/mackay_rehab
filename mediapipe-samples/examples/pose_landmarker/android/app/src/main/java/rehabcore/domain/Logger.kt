package rehabcore.domain

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Logger {
    private const val TAG = "Rehab"

    // ---- 新增：環形緩衝 ----
    private const val MAX_LINES = 2000
    private val buf = ArrayDeque<String>(MAX_LINES)
    private val lock = ReentrantLock()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun now() = tsFmt.format(Date())
    private fun push(line: String) {
        lock.withLock {
            if (buf.size >= MAX_LINES) buf.removeFirst()
            buf.addLast(line)
        }
    }

    /** 給對話框用：把所有緩衝的 log 串成一段文字 */
    fun dumpAllLines(): String = lock.withLock { buf.joinToString("\n") }

    /** 清空（若你想在每次 session 開始先清掉） */
    fun clear() = lock.withLock { buf.clear() }


    fun logCalf(id: Int, base: Float?, peak: Float?, holdSec: Float, outcome: String) {
        val msg = buildString {
            append("[CALF LOG] ")
            append("id=").append(id).append(' ')
            append("base=").append(fmt(base)).append(' ')
            append("peak=").append(fmt(peak)).append(' ')
            append("hold=").append(String.format(Locale.US, "%.2f", holdSec)).append(' ')
            append("outcome=").append(outcome)
        }
        val line = "${now()} I/$TAG $msg"
        Log.i(TAG, msg)      // 不要用命名參數
        push(line)
    }

    fun logSquat(minAngle: Float, outcome: String) {
        val msg = "[SQUAT LOG] min=${String.format(Locale.US, "%.1f", minAngle)} outcome=$outcome"
        val line = "${now()} I/$TAG $msg"
        Log.i(TAG, msg)
        push(line)
    }

    fun state(tag: String, from: String, to: String, angle: Float?) {
        val a = angle?.let { "%.1f".format(it) } ?: "--"
        val msg = "[$tag STATE] $from -> $to @angle=$a"
        val line = "${now()} D/$TAG $msg"
        Log.d(TAG, msg)
        push(line)
    }

    private fun fmt(v: Float?): String =
        if (v == null) "--" else String.format(Locale.US, "%.1f", v)

}