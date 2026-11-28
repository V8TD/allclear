package com.v8td.allclear.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Собственный потягиватель обновления вместо SwipeRefreshLayout.
 *
 * Пока палец тянет вниз (страница в самом верху) содержимое чуть оттягивается
 * (не более 24dp) — только тактильный отклик. Во время обновления страница
 * стоит на месте, индикатор висит поверх сцены; прокрутка полностью свободна.
 * Размеры и границы прокрутки не меняются никогда — залипание невозможно.
 *
 * Внутри должен лежать ровно один ребёнок — ViewPager2.
 * Горизонтальные свайпы городов не перехватываются.
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Вызывается, когда пользователь дотянул до порога и отпустил. */
    var onRefresh: () -> Unit = {}

    /** true = текущая страница может прокручиваться вверх (потягивание запрещено). */
    var canScrollUp: () -> Boolean = { false }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val triggerPull = 64f * density   // порог срабатывания при отпускании
    private val maxPull = 150f * density      // предел оттягивания пальцем
    private val maxShift = 24f * density      // максимум сдвига содержимого
    private val spinnerY = 96f * density      // центр индикатора поверх сцены
    private val radius = 17f * density
    private val strokeW = 3f * density

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var refreshing = false
    private var pull = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    init {
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = strokeW
        trackPaint.color = 0xFF4FC3F7.toInt()
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = strokeW
        arcPaint.strokeCap = Paint.Cap.ROUND
        arcPaint.color = 0xFF4FC3F7.toInt()
    }

    /** Запуск обновления из кода (кнопка или потягивание). Идемпотентен. */
    fun beginRefresh() {
        if (refreshing) return
        refreshing = true
        animateTo(0f)          // содержимое сразу возвращается на место
        onRefresh()
        invalidate()
    }

    /** Вызывать по завершении загрузки (успех или ошибка). */
    fun finishRefresh() {
        refreshing = false
        animateTo(0f)
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pulling = false
                if (pull > 0f) animator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!pulling && dy > touchSlop && dy > abs(dx) && !canScrollUp()) {
                    pulling = true
                    animator?.cancel()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pulling = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                setPull(min(maxPull, max(0f, dy * 0.5f)))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (pull >= triggerPull) beginRefresh() else animateTo(0f)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawIndicator(canvas)
    }

    private fun drawIndicator(canvas: Canvas) {
        val progress = if (refreshing) 1f else min(1f, pull / triggerPull)
        if (progress <= 0.02f) return
        val cx = width / 2f
        val cy = spinnerY
        trackPaint.alpha = (70 * progress).toInt()
        canvas.drawCircle(cx, cy, radius, trackPaint)
        arcPaint.alpha = (255 * progress).toInt()
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (refreshing) {
            val t = (System.currentTimeMillis() % 900L) / 900f
            canvas.drawArc(arcRect, t * 360f, 280f, false, arcPaint)
            postInvalidateOnAnimation()
        } else {
            canvas.drawArc(arcRect, -90f, 270f * progress, false, arcPaint)
        }
    }

    private fun setPull(v: Float) {
        pull = v
        // Лёгкий тактильный сдвиг только пока тянет палец; при обновлении — ноль.
        getChildAt(0)?.translationY = if (refreshing) 0f else min(v, maxShift)
        invalidate()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(pull, target)
        a.duration = 240L
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { setPull(it.animatedValue as Float) }
        a.start()
        animator = a
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
