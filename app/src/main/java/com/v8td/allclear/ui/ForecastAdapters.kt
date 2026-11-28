package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.v8td.allclear.R
import com.v8td.allclear.util.WeatherFormat

/**
 * Временный маппинг кодов Yahoo/Chronus на встроенные иконки-заглушки.
 * Полные наборы (0–47, Chronus-паки) — Этап 6.
 * Старые HourlyAdapter/DailyAdapter удалены: прогнозы рисуют ленты
 * HourlyStripView/DailyStripView (стиль WM8).
 */
object WeatherIcons {

    fun iconFor(code: Int, isDay: Boolean): Int = when (code) {
        31 -> R.drawable.ic_weather_moon
        32, 36 -> R.drawable.ic_weather_sun
        33, 34 -> if (isDay) R.drawable.ic_weather_cloud_sun else R.drawable.ic_weather_cloud_moon
        29, 30, 44 -> if (isDay) R.drawable.ic_weather_cloud_sun else R.drawable.ic_weather_cloud_moon
        27, 28 -> R.drawable.ic_weather_cloud
        26 -> R.drawable.ic_weather_cloud
        0, 1, 2, 3, 4, 35, 37, 38, 39, 45, 47 -> R.drawable.ic_weather_thunder
        in 5..12, 17, 18, 40 -> R.drawable.ic_weather_rain
        in 13..16, 41, 42, 43, 46 -> R.drawable.ic_weather_snow
        in 19..22, 23, 24 -> R.drawable.ic_weather_fog
        else -> R.drawable.ic_weather_na
    }

    /** Краткое название группы погоды для колонки прогноза. */
    fun conditionName(context: Context, code: Int): String = when (code) {
        31, 32, 36 -> context.getString(R.string.cond_clear)
        33, 34 -> context.getString(R.string.cond_fair)
        27, 28, 29, 30, 44 -> context.getString(R.string.cond_partly)
        26 -> context.getString(R.string.cond_overcast)
        0, 1, 2, 3, 4, 35, 37, 38, 39, 45, 47 -> context.getString(R.string.cond_thunder)
        in 5..8, 17, 18 -> context.getString(R.string.cond_sleet)
        in 9..12, 40 -> context.getString(R.string.cond_rain)
        in 13..16, 41, 42, 43, 46 -> context.getString(R.string.cond_snow)
        in 19..22 -> context.getString(R.string.cond_fog)
        else -> WeatherFormat.DASH
    }
}

/**
 * Рисунок фазы луны: светлый диск + тень терминатора.
 * Без аллокаций в onDraw. waxing = растущая (свет справа).
 */
class MoonPhaseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
    }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
    }
    private val path = Path()
    private val oval = RectF()
    private var illumination = 0.5f   // 0..1
    private var waxing = true

    fun setPhase(illum: Double, waxing: Boolean) {
        this.illumination = illum.coerceIn(0.0, 1.0).toFloat()
        this.waxing = waxing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = minOf(w, h) / 2f - 2f
        val cx = w / 2f
        val cy = h / 2f

        // Тёмный диск целиком
        canvas.drawCircle(cx, cy, r, darkPaint)
        val k = illumination
        if (k <= 0.02f) return          // новолуние — всё тёмное
        if (k >= 0.98f) {               // полнолуние — всё светлое
            canvas.drawCircle(cx, cy, r, litPaint)
            return
        }

        // Светлая половина (справа у растущей, слева у убывающей)
        path.reset()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        if (waxing) {
            path.addArc(oval, -90f, 180f)   // правая половина
        } else {
            path.addArc(oval, 90f, 180f)    // левая половина
        }

        // Терминатор — полуэллипс; ширина зависит от освещённости
        val termR = r * kotlin.math.abs(1f - 2f * k)
        oval.set(cx - termR, cy - r, cx + termR, cy + r)
        if (k > 0.5f) {
            // Выпуклая луна: добавляем эллипс к светлой половине
            if (waxing) path.addArc(oval, -90f, -180f) else path.addArc(oval, 90f, -180f)
            canvas.drawPath(path, litPaint)
        } else {
            // Серп: светлая половина, потом тень эллипсом поверх
            canvas.drawPath(path, litPaint)
            path.reset()
            if (waxing) path.addArc(oval, -90f, -180f) else path.addArc(oval, 90f, -180f)
            path.close()
            canvas.drawPath(path, darkPaint)
        }
    }
}
