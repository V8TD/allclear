package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.v8td.allclear.util.WeatherFormat

/**
 * Дуга солнца как в WM8: пунктирная дуга от восхода до заката,
 * точка текущего положения (жёлтая днём, светлая ночью) и времена у краёв.
 * Статична между привязками данных — батарею не расходует.
 */
class SunArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private var sunrise = 0L
    private var sunset = 0L

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 1.5f * d
        arcPaint.color = 0x66FFFFFF
        arcPaint.pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = 0xFFFFEB3B.toInt()
        textPaint.textSize = 11f * d
        textPaint.color = 0xFFB0BEC5.toInt()
    }

    fun setSunTimes(riseMs: Long, setMs: Long) {
        sunrise = riseMs
        sunset = setMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sunrise <= 0L || sunset <= sunrise) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = 8f * d
        val right = w - 8f * d
        val baseY = h - 18f * d
        val topY = 6f * d
        val ctrlX = w / 2f
        val ctrlY = 2f * topY - baseY   // вершина дуги окажется на topY

        path.reset()
        path.moveTo(left, baseY)
        path.quadTo(ctrlX, ctrlY, right, baseY)
        canvas.drawPath(path, arcPaint)

        val now = System.currentTimeMillis()
        val t = ((now - sunrise).toDouble() / (sunset - sunrise).toDouble())
            .coerceIn(0.0, 1.0).toFloat()
        val mt = 1f - t
        val x = mt * mt * left + 2f * mt * t * ctrlX + t * t * right
        val y = mt * mt * baseY + 2f * mt * t * ctrlY + t * t * baseY
        dotPaint.color = if (now in sunrise..sunset) 0xFFFFEB3B.toInt() else 0xFFECEFF1.toInt()
        canvas.drawCircle(x, y, 5f * d, dotPaint)

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(WeatherFormat.time(sunrise), left, h - 4f * d, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(WeatherFormat.time(sunset), right, h - 4f * d, textPaint)
    }
}
