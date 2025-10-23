package rehabcore.domain

import android.content.Context
import java.io.File

class Diagnostics(private val context: Context) {
    private val frames = StringBuilder("timestamp_ms,angle,state,holdSec,baselineY,heelLift")


    fun frame(angle: Float?, state: String, holdSec: Float, baselineY: Float?, heelLift: Float?) {
        val ts = System.currentTimeMillis()
        frames.append("${ts},${angle ?: ""},${state},${"%.2f".format(holdSec)},${baselineY ?: ""},${heelLift ?: ""}")
    }


    fun event(name: String, kv: Map<String, Any?>) {
// 可擴充：另外寫 events 檔或追加到 frames 後面
        frames.append("#EVENT,${System.currentTimeMillis()},${name},${kv}")
    }


    fun dumpCsvTo(dir: File): File {
        val outDir = File(dir, "rehab-sessions").apply { mkdirs() }
        val name = "frames_${System.currentTimeMillis()}.csv"
        return File(outDir, name).apply { writeText(frames.toString()) }
    }
}