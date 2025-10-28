package com.example.bot.render

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

interface HallRenderer {
    /**
     * Вернуть PNG-байты схемы зала.
     * scale — множитель для Retina/увеличенного качества (например, 2.0).
     */
    suspend fun render(
        clubId: Long,
        startUtc: String,
        scale: Double,
        stateKey: String,
    ): ByteArray
}

/**
 * Демонстрационный renderer на Java2D (headless). Не требует внешних файлов.
 * Рисует сетку, рамку, минимальные подписи.
 */
class DefaultHallRenderer : HallRenderer {
    private companion object {
        const val BASE_W = 800
        const val BASE_H = 500
        const val GRID_STEP = 40
        const val MIN_GRID_STEP = 20
        const val FRAME_MARGIN = 20
        const val FRAME_STROKE = 3
        const val TITLE_FONT = 18
        const val TEXT_FONT = 14
        const val TEXT_X = 30
        const val TITLE_Y = 40
        const val STATE_Y = 60
        const val VER_Y = 80
        const val LEGEND_Y_OFFSET = 60
        const val LEGEND_LABEL_DX = 14
        const val LEGEND_LABEL_DY = 5
        const val LEGEND_STEP = 80
        const val DOT_RADIUS = 8
        const val MIN_SCALE = 0.1
        const val DEFAULT_SCALE = 1.0
        val bg = Color(15, 17, 21)
        val grid = Color(32, 38, 50)
        val frameColor = Color(96, 165, 250)
        val textColor = Color(203, 213, 225)
        val freeColor = Color(34, 197, 94)
        val holdColor = Color(234, 179, 8)
        val bookedColor = Color(239, 68, 68)
    }

    override suspend fun render(
        clubId: Long,
        startUtc: String,
        scale: Double,
        stateKey: String,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            val s = if (scale.isFinite() && scale > MIN_SCALE) scale else DEFAULT_SCALE
            val w = (BASE_W * s).toInt()
            val h = (BASE_H * s).toInt()

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // фон
                g.color = bg
                g.fillRect(0, 0, w, h)

                // сетка
                g.color = grid
                val step = (GRID_STEP * s).toInt().coerceAtLeast(MIN_GRID_STEP)
                var x = 0
                while (x < w) {
                    g.drawLine(x, 0, x, h)
                    x += step
                }
                var y = 0
                while (y < h) {
                    g.drawLine(0, y, w, y)
                    y += step
                }

                // рамка зала
                g.color = frameColor
                g.stroke = BasicStroke((FRAME_STROKE * s).toFloat())
                g.drawRect(
                    (FRAME_MARGIN * s).toInt(),
                    (FRAME_MARGIN * s).toInt(),
                    (w - 2 * FRAME_MARGIN * s).toInt(),
                    (h - 2 * FRAME_MARGIN * s).toInt(),
                )

                // подписи
                g.font = Font("SansSerif", Font.BOLD, (TITLE_FONT * s).toInt())
                g.color = textColor
                g.drawString("Club #$clubId  •  Night: $startUtc", (TEXT_X * s).toInt(), (TITLE_Y * s).toInt())
                g.font = Font("SansSerif", Font.PLAIN, (TEXT_FONT * s).toInt())
                g.drawString("state: $stateKey", (TEXT_X * s).toInt(), (STATE_Y * s).toInt())
                val ver = System.getenv("HALL_BASE_IMAGE_VERSION") ?: "1"
                g.drawString("v$ver  •  scale=${"%.1f".format(s)}", (TEXT_X * s).toInt(), (VER_Y * s).toInt())

                // легенда
                val legendY = h - (LEGEND_Y_OFFSET * s).toInt()
                drawLegend(g, (TEXT_X * s).toInt(), legendY, s)
            } finally {
                g.dispose()
            }

            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "png", baos)
            baos.toByteArray()
        }

    private fun drawLegend(
        g: java.awt.Graphics2D,
        x: Int,
        y: Int,
        s: Double,
    ) {
        val r = (DOT_RADIUS * s).toInt()

        fun dot(
            cx: Int,
            cy: Int,
            color: Color,
        ) {
            g.color = color
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
        }
        var cx = x
        val cy = y
        g.font = Font("SansSerif", Font.PLAIN, (TEXT_FONT * s).toInt())
        dot(cx, cy, freeColor)
        g.color = textColor
        g.drawString("FREE", cx + (LEGEND_LABEL_DX * s).toInt(), cy + (LEGEND_LABEL_DY * s).toInt())
        cx += (LEGEND_STEP * s).toInt()
        dot(cx, cy, holdColor)
        g.color = textColor
        g.drawString("HOLD", cx + (LEGEND_LABEL_DX * s).toInt(), cy + (LEGEND_LABEL_DY * s).toInt())
        cx += (LEGEND_STEP * s).toInt()
        dot(cx, cy, bookedColor)
        g.color = textColor
        g.drawString("BOOKED", cx + (LEGEND_LABEL_DX * s).toInt(), cy + (LEGEND_LABEL_DY * s).toInt())
    }
}
