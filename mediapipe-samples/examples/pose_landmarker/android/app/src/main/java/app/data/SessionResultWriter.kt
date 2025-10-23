package app.data

import android.content.Context
import rehabcore.domain.model.SessionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object SessionResultWriter {
    private val json = Json { prettyPrint = true }
    fun write(context: Context, result: SessionResult, filename: String): File {
        val dir = File(context.filesDir, "rehab-sessions").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(json.encodeToString(result))
        return file
    }
}
