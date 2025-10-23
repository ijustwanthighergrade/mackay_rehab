package rehabcore.overlay

import android.graphics.*
import rehabcore.domain.model.FrameHud
import java.util.Locale

class HudRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#44FFFFFF") }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFFFFFF") }


    fun draw(canvas: Canvas, hud: FrameHud) {
        val fps = (hud.extra["fps"] as? Number)?.toFloat()
        val lines = mutableListOf<String>()
        lines += "Angle: ${hud.angleDeg?.let { String.format("%.1f°", it) } ?: "--"}"
        lines += "State: ${hud.state}"
        lines += "Hold: ${String.format("%.2fs", hud.holdSec)}"
        fps?.let { lines += String.format("FPS: %.1f", it) }
        lines += "Success: ${hud.success} Fail: ${hud.fail}"


        val padding = 16f
        val lineH = paint.textSize + 10f
        val boxW = lines.maxOf { paint.measureText(it) } + padding * 2
        val boxH = lineH * lines.size + padding * 2 + 28f
        val rect = RectF(20f, 20f, 20f + boxW, 20f + boxH)
        canvas.drawRoundRect(rect, 16f, 16f, boxPaint)
        var y = rect.top + padding + paint.textSize
        for (s in lines) {
            canvas.drawText(s, rect.left + padding, y, paint)
            y += lineH
        }
// Hold 進度條
        val target = (hud.extra["holdTarget"] as? Number)?.toFloat() ?: 0f
        if (target > 0f) {
            val pct = (hud.holdSec / target).coerceIn(0f, 1f)
            val barLeft = rect.left + padding
            val barTop = rect.bottom - padding - 18f
            val barRight = rect.right - padding
            val barBottom = rect.bottom - padding
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 8f, 8f, barBg)
            val fillRight = barLeft + (barRight - barLeft) * pct
            canvas.drawRoundRect(RectF(barLeft, barTop, fillRight, barBottom), 8f, 8f, barFg)
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
    }

}