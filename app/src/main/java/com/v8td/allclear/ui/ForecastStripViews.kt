package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Колонка почасового прогноза (одна точка кривой).
 * Ветер и мм появятся здесь после расширения моделей провайдеров.
 */
data class HourlyColumn(
    val time: String,        // "Сейчас" / "03:00"
    val date: String?,       // "28 нояб."
    val prob: Int?,          // % осадков, null = нет данных
    val temp: Double,
    val iconRes: Int
)

/** Колонка-день ежедневного прогноза (две точки: макс и мин). */
data class DailyColumn(
    val day: String,         // "Сегодня" / "Завтра" / "Пн"
    val date: String,        // "28 нояб."
    val desc: String,        // "Пасмурно"
    val iconRes: Int,
    val max: Double,
    val min: Double,
    val prob: Int?
)

/**
 * База горизонтальных лент прогноза в стиле WM8.
 * Ширина = число колонок; кладётся в HorizontalScrollView.
 * Кривая сплошная через всю ленту (Catmull-Rom через точки колонок).
 * Все аллокации — в setData, не в onDraw.
 */
open class StripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    protected val d = resources.displayMetrics.density
    protected val padH = 12f * d
    protected var columns = 0
    protected open val colW = 72f * d
    protected open val stripH = 150f * d

    protected val textSmall = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textValue = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textTemp = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dotInner = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val icons = ArrayList<Drawable?>()
    protected val path = Path()

    init {
        textSmall.textSize = 11f * d
        textSmall.textAlign = Paint.Align.CENTER
        textSmall.color = 0xFFB0BEC5.toInt()
        textValue.textSize = 13f * d
        textValue.textAlign = Paint.Align.CENTER
        textValue.color = 0xFFECEFF1.toInt()
        textTemp.textSize = 12f * d
        textTemp.textAlign = Paint.Align.CENTER
        textTemp.color = 0xFFECEFF1.toInt()
        curvePaint.style = Paint.Style.STROKE
        curvePaint.strokeWidth = 2f * d
        curvePaint.strokeCap = Paint.Cap.ROUND
        curvePaint.strokeJoin = Paint.Join.ROUND
        dotPaint.style = Paint.Style.FILL
        dotInner.style = Paint.Style.FILL
        dotInner.color = 0xFF263238.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (padH * 2 + columns * colW).toInt()
        val h = stripH.toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    protected fun cx(i: Int): Float = padH + colW * i + colW / 2f

    protected fun tempLabel(v: Double): String = "${v.roundToInt()}°"

    protected fun loadIcons(resIds: List<Int>) {
        icons.clear()
        for (r in resIds) icons.add(if (r == 0) null else ContextCompat.getDrawable(context, r))
    }

    protected fun drawIcon(canvas: Canvas, i: Int, centerY: Float, size: Float) {
        val dr = icons.getOrNull(i) ?: return
        val x = cx(i)
        dr.setBounds(
            (x - size / 2f).toInt(), (centerY - size / 2f).toInt(),
            (x + size / 2f).toInt(), (centerY + size / 2f).toInt()
        )
        dr.draw(canvas)
    }

    /** Сплошная сглаженная кривая через точки колонок + точки-маркеры. */
    protected fun drawCurve(canvas: Canvas, ys: FloatArray, color: Int) {
        if (ys.isEmpty()) return
        curvePaint.color = color
        path.reset()
        path.moveTo(cx(0), ys[0])
        for (i in 0 until ys.size - 1) {
            val i0 = if (i > 0) i - 1 else i
            val i3 = if (i + 2 < ys.size) i + 2 else i + 1
            val x1 = cx(i); val y1 = ys[i]
            val x2 = cx(i + 1); val y2 = ys[i + 1]
            val c1x = x1 + (x2 - cx(i0)) / 6f
            val c1y = y1 + (y2 - ys[i0]) / 6f
            val c2x = x2 - (cx(i3) - x1) / 6f
            val c2y = y2 - (ys[i3] - y1) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
        }
        canvas.drawPath(path, curvePaint)
        dotPaint.color = color
        for (i in ys.indices) {
            canvas.drawCircle(cx(i), ys[i], 3.2f * d, dotPaint)
            canvas.drawCircle(cx(i), ys[i], 1.6f * d, dotInner)
        }
    }

    protected fun bandY(value: Double, mn: Double, mx: Double, top: Float, bottom: Float): Float {
        val span = if (mx - mn < 0.001) 1.0 else mx - mn
        return (bottom - ((value - mn) / span) * (bottom - top)).toFloat()
    }
}

/** ПОЧАСОВОЙ ПРОГНОЗ: % / кривая температуры / иконка / время / дата. */
class HourlyStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : StripView(context, attrs) {

    override val colW = 72f * d
    override val stripH = 150f * d

    private var cols: List<HourlyColumn> = emptyList()
    private var ys = FloatArray(0)

    fun setData(list: List<HourlyColumn>) {
        cols = list
        columns = list.size
        loadIcons(list.map { it.iconRes })
        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        for (c in list) { mn = min(mn, c.temp); mx = max(mx, c.temp) }
        if (list.isEmpty()) { mn = 0.0; mx = 1.0 }
        if (mx - mn < 1.0) { mn -= 1.0; mx += 1.0 }
        val top = 46f * d
        val bottom = 78f * d
        ys = FloatArray(list.size) { i -> bandY(list[i].temp, mn, mx, top, bottom) }
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cols.isEmpty()) return
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            textSmall.color = if ((c.prob ?: 0) > 0) 0xFF4FC3F7.toInt() else 0xFF78909C.toInt()
            canvas.drawText(if (c.prob != null) "${c.prob}%" else "—", x, 18f * d, textSmall)
            textTemp.color = 0xFFECEFF1.toInt()
            canvas.drawText(tempLabel(c.temp), x, ys[i] - 8f * d, textTemp)
        }
        drawCurve(canvas, ys, 0xFF4FC3F7.toInt())
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            drawIcon(canvas, i, 98f * d, 24f * d)
            textValue.color = 0xFFECEFF1.toInt()
            canvas.drawText(c.time, x, 122f * d, textValue)
            if (c.date != null) {
                textSmall.color = 0xFF78909C.toInt()
                canvas.drawText(c.date, x, 136f * d, textSmall)
            }
        }
    }
}

/** ЕЖЕДНЕВНЫЙ ПРОГНОЗ: день / дата / иконка / описание / кривые макс и мин / %. */
class DailyStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : StripView(context, attrs) {

    override val colW = 92f * d
    override val stripH = 210f * d

    private var cols: List<DailyColumn> = emptyList()
    private var ysMax = FloatArray(0)
    private var ysMin = FloatArray(0)

    fun setData(list: List<DailyColumn>) {
        cols = list
        columns = list.size
        loadIcons(list.map { it.iconRes })
        var mnMax = Double.MAX_VALUE; var mxMax = -Double.MAX_VALUE
        var mnMin = Double.MAX_VALUE; var mxMin = -Double.MAX_VALUE
        for (c in list) {
            mnMax = min(mnMax, c.max); mxMax = max(mxMax, c.max)
            mnMin = min(mnMin, c.min); mxMin = max(mxMin, c.min)
        }
        if (list.isEmpty()) { mnMax = 0.0; mxMax = 1.0; mnMin = 0.0; mxMin = 1.0 }
        if (mxMax - mnMax < 1.0) { mnMax -= 1.0; mxMax += 1.0 }
        if (mxMin - mnMin < 1.0) { mnMin -= 1.0; mxMin += 1.0 }
        ysMax = FloatArray(list.size) { i -> bandY(list[i].max, mnMax, mxMax, 100f * d, 128f * d) }
        ysMin = FloatArray(list.size) { i -> bandY(list[i].min, mnMin, mxMin, 152f * d, 180f * d) }
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cols.isEmpty()) return
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            textValue.color = if (i < 2) 0xFF4FC3F7.toInt() else 0xFFECEFF1.toInt()
            canvas.drawText(c.day, x, 18f * d, textValue)
            textSmall.color = 0xFF78909C.toInt()
            canvas.drawText(c.date, x, 32f * d, textSmall)
            drawIcon(canvas, i, 52f * d, 26f * d)
            textSmall.color = 0xFFB0BEC5.toInt()
            canvas.drawText(c.desc, x, 78f * d, textSmall)
            textTemp.color = 0xFFECEFF1.toInt()
            canvas.drawText(tempLabel(c.max), x, ysMax[i] - 6f * d, textTemp)
            textTemp.color = 0xFFB0BEC5.toInt()
            canvas.drawText(tempLabel(c.min), x, ysMin[i] + 14f * d, textTemp)
        }
        drawCurve(canvas, ysMax, 0xFFFF9800.toInt())
        drawCurve(canvas, ysMin, 0xFF4FC3F7.toInt())
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            textSmall.color = if ((c.prob ?: 0) > 0) 0xFF4FC3F7.toInt() else 0xFF78909C.toInt()
            canvas.drawText(if (c.prob != null) "${c.prob}%" else "—", x, 202f * d, textSmall)
        }
    }
}
