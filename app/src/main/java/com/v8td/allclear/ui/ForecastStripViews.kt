package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Колонка почасового прогноза (одна точка кривой).
 * marker: 0 = обычный час, 1 = восход, 2 = закат (колонка-маркер WM8).
 * Ветер и мм появятся здесь после расширения моделей провайдеров.
 */
data class HourlyColumn(
    val time: String,
    val date: String?,
    val prob: Int?,
    val temp: Double,
    val iconRes: Int,
    val code: Int,
    val marker: Int = 0
)

/** Колонка-день ежедневного прогноза (две точки: макс и мин). */
data class DailyColumn(
    val day: String,
    val date: String,
    val desc: String,
    val iconRes: Int,
    val max: Double,
    val min: Double,
    val prob: Int?
)

/**
 * База горизонтальных лент прогноза в стиле WM8.
 * Колонки начинаются от самого края карточки (без левого поля).
 * Кривая сплошная через всю ленту; все аллокации — в setData.
 *
 * Защита жеста: при касании ленты горизонтальный свайп защищается от
 * старших контейнеров (ViewPager2 листал бы города вместо ленты),
 * но НЕ от собственного HorizontalScrollView. Явный вертикальный жест
 * снимает защиту — страница и потягивание работают как обычно.
 */
open class StripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    protected val d = resources.displayMetrics.density
    protected val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    protected var columns = 0
    protected open val colW = 58f * d
    protected open val stripH = 150f * d

    protected val textSmall = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textValue = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textTemp = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dotInner = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val icons = ArrayList<Drawable?>()
    protected val path = Path()
    protected val markerRect = RectF()

    private var tDownX = 0f
    private var tDownY = 0f

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
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = 1f * d
        dashPaint.color = 0x55FFFFFF
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = 0xFFFFC107.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (columns * colW).toInt()
        val h = stripH.toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tDownX = ev.x
                tDownY = ev.y
                guardHorizontal(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - tDownX
                val dy = ev.y - tDownY
                if (abs(dy) > abs(dx) * 1.2f && abs(dy) > touchSlop) guardHorizontal(false)
            }
        }
        return true
    }

    /**
     * Защита от старших контейнеров, минуя собственный HorizontalScrollView.
     * requestDisallowInterceptTouchEvent объявлен в ViewParent (не в View),
     * поэтому работаем с ссылками типа ViewParent без приведения к View.
     */
    private fun guardHorizontal(disallow: Boolean) {
        val hsv = parent ?: return          // HorizontalScrollView как ViewParent
        val above = hsv.parent ?: return    // карточка над ним как ViewParent
        above.requestDisallowInterceptTouchEvent(disallow)
    }

    protected fun cx(i: Int): Float = colW * i + colW / 2f

    protected fun tempLabel(v: Double): String = "${v.roundToInt()}°"

    /** Обрезка длинного названия многоточием, как в WM8 («Понедельн…»). */
    protected fun fit(text: String, maxW: Float, p: Paint): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

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

    /** Пунктирная вертикаль колонки (Сейчас / восход / закат). */
    protected fun drawDash(canvas: Canvas, i: Int, fromY: Float, toY: Float) {
        val x = cx(i)
        canvas.drawLine(x, fromY, x, toY, dashPaint)
    }

    /** Маркер восхода/заката: половинка солнца с лучами и стрелкой вверх/вниз. */
    protected fun drawSunMarker(canvas: Canvas, i: Int, y: Float, rise: Boolean) {
        val x = cx(i)
        val r = 5f * d
        markerRect.set(x - r, y - r, x + r, y + r)
        canvas.drawArc(markerRect, 180f, 180f, true, markerPaint)
        canvas.drawLine(x - r - 3f * d, y, x - r - 1f * d, y, markerPaint)
        canvas.drawLine(x + r + 1f * d, y, x + r + 3f * d, y, markerPaint)
        canvas.drawLine(x, y - r - 3f * d, x, y - r - 1f * d, markerPaint)
        val ay = y - r - 6f * d
        val tip = if (rise) ay - 5f * d else ay + 5f * d
        canvas.drawLine(x, ay, x, tip, markerPaint)
        canvas.drawLine(x, tip, x - 2.5f * d, tip + (if (rise) 2.5f * d else -2.5f * d), markerPaint)
        canvas.drawLine(x, tip, x + 2.5f * d, tip + (if (rise) 2.5f * d else -2.5f * d), markerPaint)
    }
}

/** ПОЧАСОВОЙ ПРОГНОЗ WM8: % / кривая / иконки только при смене погоды / время / дата. */
class HourlyStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : StripView(context, attrs) {

    override val colW = 58f * d
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
        ys = FloatArray(list.size) { i -> bandY(list[i].temp, mn, mx, 46f * d, 78f * d) }
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cols.isEmpty()) return
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            if (i == 0 || c.marker != 0) drawDash(canvas, i, 40f * d, 140f * d)
            if (c.marker == 0) {
                textSmall.color = if ((c.prob ?: 0) > 0) 0xFF4FC3F7.toInt() else 0xFF78909C.toInt()
                canvas.drawText(if (c.prob != null) "${c.prob}%" else "—", x, 18f * d, textSmall)
                textTemp.color = 0xFFECEFF1.toInt()
                canvas.drawText(tempLabel(c.temp), x, ys[i] - 8f * d, textTemp)
            } else {
                drawSunMarker(canvas, i, ys[i] - 10f * d, rise = c.marker == 1)
            }
        }
        drawCurve(canvas, ys, 0xFF4FC3F7.toInt())
        for (i in cols.indices) {
            val c = cols[i]
            val x = cx(i)
            // Иконка под кривой — только там, где погода меняется (как в WM8).
            if (c.marker == 0 && (i == 0 || c.code != cols[i - 1].code)) {
                drawIcon(canvas, i, 98f * d, 22f * d)
            }
            textValue.color = 0xFFECEFF1.toInt()
            canvas.drawText(c.time, x, 122f * d, textValue)
            if (c.date != null) {
                textSmall.color = 0xFF78909C.toInt()
                canvas.drawText(c.date, x, 136f * d, textSmall)
            }
        }
    }
}

/** ЕЖЕДНЕВНЫЙ ПРОГНОЗ WM8: день / дата / иконка / описание / кривые макс и мин / %. */
class DailyStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : StripView(context, attrs) {

    override val colW = 72f * d
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
            canvas.drawText(fit(c.day, colW - 6f * d, textValue), x, 18f * d, textValue)
            textSmall.color = 0xFF78909C.toInt()
            canvas.drawText(c.date, x, 32f * d, textSmall)
            drawIcon(canvas, i, 52f * d, 26f * d)
            textSmall.color = 0xFFB0BEC5.toInt()
            canvas.drawText(fit(c.desc, colW - 6f * d, textSmall), x, 78f * d, textSmall)
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
