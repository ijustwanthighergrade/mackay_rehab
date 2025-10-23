package rehabcore.overlay

import android.graphics.*
import rehabcore.domain.model.FrameHud

class CalfOverlay {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; color = Color.GREEN }

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.CYAN
        // 關鍵：在實例上設置 pathEffect（或呼叫 setPathEffect(...)）
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 42f; color = Color.YELLOW }

    fun draw(canvas: Canvas, hud: FrameHud) {
        val baselineY = (hud.extra["baselineY"] as? Number)?.toFloat() ?: return
        val toeX = (hud.extra["toeX"] as? Number)?.toFloat() ?: return
        val heelX = (hud.extra["heelX"] as? Number)?.toFloat() ?: return
        val toeY = (hud.extra["toeY"] as? Number)?.toFloat() ?: return
        val heelY = (hud.extra["heelY"] as? Number)?.toFloat() ?: return

        // 基準線（水平）
        canvas.drawLine(0f, baselineY, canvas.width.toFloat(), baselineY, dashPaint)
        // toe, heel 指示
        canvas.drawCircle(toeX, toeY, 10f, linePaint)
        canvas.drawCircle(heelX, heelY, 10f, linePaint)
        // heel 垂線到基準
        canvas.drawLine(heelX, baselineY, heelX, heelY, linePaint)
        // 角度文字
        hud.angleDeg?.let { a ->
            canvas.drawText(String.format("%.1f°", a), heelX + 16f, (baselineY + heelY) / 2f, textPaint)
        }
    }
}
