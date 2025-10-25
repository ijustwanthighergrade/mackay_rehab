package app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import androidx.camera.core.Logger
import rehabcore.domain.model.SessionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rehabcore.domain.model.ParamValue
import rehabcore.domain.model.RepLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionResultWriter {

    private val json = Json { prettyPrint = true }

    /**
     * 安全地建立檔名。
     */
    fun defaultFileName(prefix: String = "session"): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$ts.json"
    }

    /**
     * 將訓練結果以安全方式寫入檔案。
     * 若寫檔失敗將回傳 Result 物件而非直接丟例外。
     */
    fun writeSafe(context: Context, result: SessionResult, filename: String = defaultFileName()): Result<File> {
        return try {
            val dir = File(context.filesDir, "rehab-sessions").apply { mkdirs() }
            val file = File(dir, filename)
            file.writeText(json.encodeToString(result))
            android.util.Log.i("SessionResultWriter", "Saved to ${file.absolutePath}")
            Result.success(file)
        } catch (e: Exception) {
            android.util.Log.e("SessionResultWriter", "Failed to save: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 包含運動統計資料的便利方法，內部自動封裝 SessionResult。
     */
    fun save(
        context: Context,
        action: String,
        fps: Float,
        params: Map<String, ParamValue>,
        success: Int,
        fail: Int,
        total: Int,
        successRate: Float,
        reps: List<RepLog>,
        framesSampled: Int,
        filename: String = "${action.lowercase()}-${System.currentTimeMillis()}.json"
    ): Result<File> = runCatching {
        val result = SessionResult(
            action = action,
            fps = fps,
            params = params,
            success = success,
            fail = fail,
            total = total,
            successRate = successRate,
            reps = reps,
            framesSampled = framesSampled
        )
        writeSafe(context, result, filename).getOrThrow()
    }
}

