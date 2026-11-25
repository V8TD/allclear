package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v8td.allclear.R
import com.v8td.allclear.data.DailyForecast
import com.v8td.allclear.data.HourlyForecast
import com.v8td.allclear.databinding.ItemDailyForecastBinding
import com.v8td.allclear.databinding.ItemHourlyForecastBinding
import com.v8td.allclear.util.WeatherFormat

/**
 * Временный маппинг кодов Yahoo/Chronus на встроенные иконки-заглушки.
 * Полные наборы (0–47, Chronus-паки) — Этап 6.
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
}

/** Почасовой прогноз: горизонтальная лента. */
class HourlyAdapter(private val context: Context) :
    RecyclerView.Adapter<HourlyAdapter.Holder>() {

    private val items = mutableListOf<HourlyForecast>()

    fun submit(list: List<HourlyForecast>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemHourlyForecastBinding.inflate(LayoutInflater.from(context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.hourTime.text = if (position == 0) context.getString(R.string.now)
            else WeatherFormat.time(item.time)
        b.hourTemp.text = WeatherFormat.temp(context, item.temp)
        b.hourPrecip.text = if (item.precipProb >= 0) "${item.precipProb}%" else ""
        b.hourIcon.setImageResource(WeatherIcons.iconFor(item.conditionCode, item.isDay))
    }

    class Holder(val binding: ItemHourlyForecastBinding) :
        RecyclerView.ViewHolder(binding.root)
}

/** Ежедневный прогноз: строки. */
class DailyAdapter(private val context: Context) :
    RecyclerView.Adapter<DailyAdapter.Holder>() {

    private val items = mutableListOf<DailyForecast>()

    fun submit(list: List<DailyForecast>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemDailyForecastBinding.inflate(LayoutInflater.from(context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.dayName.text = WeatherFormat.dayLabel(context, item.date)
        b.dayDate.text = WeatherFormat.shortDate(item.date)
        b.dayIcon.setImageResource(WeatherIcons.iconFor(item.conditionCode, true))
        b.dayDesc.text = conditionName(context, item.conditionCode)
        b.dayPrecip.text = if (item.precipProb >= 0) "${item.precipProb}%" else ""
        b.dayTempMax.text = WeatherFormat.temp(context, item.tempMax)
        b.dayTempMin.text = WeatherFormat.temp(context, item.tempMin)
    }

    /** Краткое название группы погоды для строки прогноза. */
    private fun conditionName(context: Context, code: Int): String = when (code) {
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

    class Holder(val binding: ItemDailyForecastBinding) :
        RecyclerView.ViewHolder(binding.root)
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

    private var illumination = 0.5   // 0..1
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
        val bulgeRight = if (waxing) k < 0.5f else k > 0.5f
        // Дорисовываем/срезаем серп эллипсом
        if ((waxing && k > 0.5f) || (!waxing && k > 0.5f)) {
            // выпуклая: добавляем эллипс к светлой половине
            if (waxing) path.addArc(oval, -90f, -180f) else path.addArc(oval, 90f, -180f)
            canvas.drawPath(path, litPaint)
        } else {
            // серп: сначала вся светлая половина, потом тень эллипсом поверх
            canvas.drawPath(path, litPaint)
            path.reset()
            if (waxing) path.addArc(oval, -90f, -180f) else path.addArc(oval, 90f, -180f)
            path.close()
            canvas.drawPath(path, darkPaint)
        }
    }
}
