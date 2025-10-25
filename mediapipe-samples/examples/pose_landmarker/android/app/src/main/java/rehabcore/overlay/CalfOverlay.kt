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
        val ax = (hud.extra["baseToeX"]  as? Number)?.toFloat()
        val ay = (hud.extra["baseToeY"]  as? Number)?.toFloat()
        val bx = (hud.extra["baseHeelX"] as? Number)?.toFloat()
        val by = (hud.extra["baseHeelY"] as? Number)?.toFloat()
        val toeX = (hud.extra["toeX"] as? Number)?.toFloat() ?: return
        val toeY = (hud.extra["toeY"] as? Number)?.toFloat() ?: return
        val heelX = (hud.extra["heelX"] as? Number)?.toFloat() ?: return
        val heelY = (hud.extra["heelY"] as? Number)?.toFloat() ?: return

        // 畫 toe / heel 當前點
        canvas.drawCircle(toeX, toeY, 10f, linePaint)
        canvas.drawCircle(heelX, heelY, 10f, linePaint)

        if (ax != null && ay != null && bx != null && by != null) {
            // 基準線（斜線）
            canvas.drawLine(ax, ay, bx, by, dashPaint)

            // heel 到基準線的垂線
            val ABx = bx - ax; val ABy = by - ay
            val APx = heelX - ax; val APy = heelY - ay
            val ab2 = (ABx*ABx + ABy*ABy)
            val t = if (ab2 > 0f) ((APx*ABx + APy*ABy) / ab2) else 0f
            val projX = ax + t * ABx
            val projY = ay + t * ABy
            canvas.drawLine(projX, projY, heelX, heelY, linePaint)

            // 畫角度文字
            hud.angleDeg?.let { a ->
                canvas.drawText(String.format("%.1f°", a), heelX + 16f, (projY + heelY) / 2f, textPaint)
            }
        } else {
            // 基準未就緒就只畫點
            hud.angleDeg?.let { a ->
                canvas.drawText(String.format("%.1f°", a), heelX + 16f, heelY, textPaint)
            }
        }
    }

}
