package com.v8td.allclear.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.v8td.allclear.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Потягиватель обновления в стиле WM8.
 *
 * Пока палец тянет вниз (страница в самом верху) содержимое упруго едет за
 * пальцем; в открывающейся сверху полосе рисуются шевроны-уголки и надпись.
 * Отпустил ниже порога — плавно вернулось; выше — обновление.
 * Во время обновления полоса держится, шевроны бегут вниз; по завершении
 * кратко «Обновлено только что» и плавное закрытие.
 *
 * Движение — ТОЛЬКО translationY ребёнка: размеры и границы прокрутки не
 * меняются никогда, поэтому залипание и рывки невозможны конструктивно.
 * Внутри должен лежать ровно один ребёнок — ViewPager2.
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Вызывается, когда пользователь дотянул до порога и отпустил (или кнопка). */
    var onRefresh: () -> Unit = {}

    /** true = текущая страница может прокручиваться вверх (потягивание запрещено). */
    var canScrollUp: () -> Boolean = { false }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val barH = 56f * density      // высота полосы обновления
    private val maxShift = 100f * density // предел оттягивания пальцем

    private val stateIdle = 0
    private val statePull = 1
    private val stateRefresh = 2
    private val stateDone = 3
    private var state = stateIdle

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var shift = 0f
    private var doneUntil = 0L
    private var animator: ValueAnimator? = null

    private val chevPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevPath = Path()

    init {
        chevPaint.style = Paint.Style.STROKE
        chevPaint.strokeWidth = 2f * density
        chevPaint.strokeCap = Paint.Cap.ROUND
        chevPaint.strokeJoin = Paint.Join.ROUND
        chevPaint.color = 0xFF4FC3F7.toInt()
        labelPaint.textSize = 11f * density
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = 0xFFB0BEC5.toInt()
    }

    /** Запуск обновления из кода (кнопка). Идемпотентен. */
    fun beginRefresh() {
        if (state == stateRefresh) return
        state = stateRefresh
        animateShift(barH, stateRefresh)
        onRefresh()
    }

    /** Вызывать по завершении загрузки (успех или ошибка). */
    fun finishRefresh() {
        if (state != stateRefresh) return
        state = stateDone
        doneUntil = SystemClock.uptimeMillis() + 1200L
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (state == stateRefresh || state == stateDone) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pulling = false
                if (shift > 0f) animator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!pulling && dy > touchSlop * 2 && dy > abs(dx) * 1.5f && !canScrollUp()) {
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
                state = statePull
                setShift(min(maxShift, max(0f, dy * 0.5f)))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (shift >= barH) beginRefresh() else animateShift(0f, stateIdle)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawBar(canvas)
        if (state == stateRefresh || state == stateDone) postInvalidateOnAnimation()
        if (state == stateDone && SystemClock.uptimeMillis() > doneUntil) {
            animateShift(0f, stateIdle)
        }
    }

    private fun drawBar(canvas: Canvas) {
        if (shift <= 2f * density && state == stateIdle) return
        val progress = min(1f, shift / barH)
        val base = (255 * progress).toInt()
        val cx = width / 2f
        val t = SystemClock.uptimeMillis() / 1000f

        when (state) {
            stateDone -> {
                val left = ((doneUntil - SystemClock.uptimeMillis()) / 1200f).coerceIn(0f, 1f)
                val alpha = (255 * left).toInt()
                chevPaint.alpha = alpha
                chevron(canvas, cx, shift * 0.32f, up = true)
                labelPaint.alpha = alpha
                canvas.drawText(
                    context.getString(R.string.updated_just_now), cx, shift * 0.82f, labelPaint
                )
            }
            stateRefresh -> {
                for (k in 0..2) {
                    val phase = (t * 1.1f + k * 0.33f) % 1f
                    val y = shift * 0.20f + phase * 18f * density
                    chevPaint.alpha = (base * sin(phase * Math.PI.toFloat())).toInt()
                    chevron(canvas, cx, y, up = false)
                }
                labelPaint.alpha = base
                canvas.drawText(
                    context.getString(R.string.refresh_running), cx, shift * 0.86f, labelPaint
                )
            }
            else -> {
                for (k in 0..2) {
                    chevPaint.alpha = base
                    chevron(canvas, cx, shift * 0.24f + k * 7f * density, up = false)
                }
                if (shift > 24f * density) {
                    labelPaint.alpha = base
                    val text = if (shift >= barH) R.string.refresh_release else R.string.refresh_pull
                    canvas.drawText(context.getString(text), cx, shift * 0.86f, labelPaint)
                }
            }
        }
    }

    private fun chevron(canvas: Canvas, cx: Float, y: Float, up: Boolean) {
        val w = 6f * density
        val h = 5f * density
        chevPath.reset()
        if (up) {
            chevPath.moveTo(cx - w, y + h)
            chevPath.lineTo(cx, y)
            chevPath.lineTo(cx + w, y + h)
        } else {
            chevPath.moveTo(cx - w, y)
            chevPath.lineTo(cx, y + h)
            chevPath.lineTo(cx + w, y)
        }
        canvas.drawPath(chevPath, chevPaint)
    }

    private fun setShift(v: Float) {
        shift = v
        getChildAt(0)?.translationY = v
        invalidate()
    }

    private fun animateShift(target: Float, endState: Int) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(shift, target)
        a.duration = 240L
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { setShift(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state != stateRefresh) state = endState
            }
        })
        a.start()
        animator = a
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
