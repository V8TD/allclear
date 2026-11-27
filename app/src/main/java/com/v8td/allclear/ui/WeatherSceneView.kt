package com.v8td.allclear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.v8td.allclear.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Живая сцена погоды в стиле MIUI 8: небо, холмы, флюгер и ветровой мешок,
 * осадки частицами. Всё рисуется примитивами Canvas — без картинок и библиотек.
 *
 * Сцена занимает только шапку экрана (~400dp) и скроллится вместе с контентом.
 * Низ сцены плавно растворяется в тёмном фоне страницы (#1A222C).
 *
 * Энергоэффективность:
 * - все объекты созданы заранее, в onDraw нет выделения памяти;
 * - перерисовка ~30 раз/с через postDelayed;
 * - setRunning(false) полностью останавливает анимацию.
 */
class WeatherSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val FRAME_DELAY_MS = 33L   // ~30 FPS
        private const val RAIN_COUNT = 70
        private const val SNOW_COUNT = 50
        private const val STAR_COUNT = 40
        // Коды Yahoo/Chronus, которые показываем как грозу
        private val THUNDER_CODES = intArrayOf(0, 1, 2, 3, 4, 35, 37, 38, 39, 45, 47)
        // Цвет фона страницы — в него растворяется низ сцены
        private val PAGE_BACKGROUND = Color.parseColor("#FF1A222C")
    }

    // --- Состояние погоды ---
    private var conditionCode = 3200
    private var isDay = true
    private var windMs = 0.0
    private var windDirDeg = 0

    private var running = false
    private var lastFrameTime = 0L
    private var animTime = 0f
    private var lightningTimer = 0f
    private var lightningFlash = 0f

    // --- Палитра ---
    private var skyTop = Color.parseColor("#4A90D9")
    private var skyBottom = Color.parseColor("#A8D0F0")
    private var hillFar = Color.parseColor("#7FB2E5")
    private var hillNear = Color.parseColor("#5E97D1")

    // --- Краски (создаются один раз) ---
    private val skyPaint = Paint()
    private val fadePaint = Paint()
    private val hillFarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillNearPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_pole)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val sockOrangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_sock_orange)
    }
    private val sockWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_sock_white)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_pole)
        style = Paint.Style.FILL
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_rain_drop)
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_snow_flake)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_star)
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_sun)
    }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scene_moon)
    }
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66CFD8DC")
    }
    private val flashPaint = Paint().apply { color = Color.WHITE }

    // --- Геометрия ---
    private val hillFarPath = Path()
    private val hillNearPath = Path()
    private val sockPath = Path()
    private val arrowPath = Path()

    // --- Частицы ---
    private val rainX = FloatArray(RAIN_COUNT)
    private val rainY = FloatArray(RAIN_COUNT)
    private val rainSpeed = FloatArray(RAIN_COUNT)
    private val rainLen = FloatArray(RAIN_COUNT)
    private val snowX = FloatArray(SNOW_COUNT)
    private val snowY = FloatArray(SNOW_COUNT)
    private val snowSpeed = FloatArray(SNOW_COUNT)
    private val snowSize = FloatArray(SNOW_COUNT)
    private val snowPhase = FloatArray(SNOW_COUNT)
    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private var particlesReady = false

    private val frameRunnable = Runnable { invalidate() }

    init {
        val rnd = Random(42)
        for (i in 0 until STAR_COUNT) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat() * 0.45f
            starSize[i] = 1f + rnd.nextFloat() * 1.8f
        }
        applyPalette()
    }

    /** Установить погоду для сцены. */
    fun setWeather(code: Int, day: Boolean, windSpeedMs: Double, windDirectionDeg: Int) {
        val changed = code != conditionCode || day != isDay
        conditionCode = code
        isDay = day
        windMs = windSpeedMs
        windDirDeg = windDirectionDeg
        if (changed) {
            applyPalette()
            rebuildShaders()
        }
        invalidate()
    }

    /** Включить/выключить анимацию. */
    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        removeCallbacks(frameRunnable)
        if (value && isAttachedToWindow) {
            lastFrameTime = SystemClock.uptimeMillis()
            postDelayed(frameRunnable, FRAME_DELAY_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) {
            lastFrameTime = SystemClock.uptimeMillis()
            postDelayed(frameRunnable, FRAME_DELAY_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        buildHills(w, h)
        rebuildShaders()
        initParticles(w, h)
    }

    private fun rebuildShaders() {
        if (width <= 0 || height <= 0) return
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            skyTop, skyBottom, Shader.TileMode.CLAMP
        )
        // Растворение низа сцены в фоне страницы
        fadePaint.shader = LinearGradient(
            0f, height * 0.78f, 0f, height.toFloat(),
            Color.TRANSPARENT, PAGE_BACKGROUND, Shader.TileMode.CLAMP
        )
    }

    private fun buildHills(w: Int, h: Int) {
        // Дальний холм: гребень ~66% высоты шапки
        hillFarPath.reset()
        hillFarPath.moveTo(0f, h * 0.66f)
        val steps = 24
        for (i in 0..steps) {
            val x = w.toFloat() * i / steps
            val y = h * 0.66f - sin(i.toFloat() / steps * PI * 1.3 + 0.4).toFloat() * h * 0.06f
            hillFarPath.lineTo(x, y)
        }
        hillFarPath.lineTo(w.toFloat(), h.toFloat())
        hillFarPath.lineTo(0f, h.toFloat())
        hillFarPath.close()

        // Ближний холм: гребень ~82%
        hillNearPath.reset()
        hillNearPath.moveTo(0f, h * 0.82f)
        for (i in 0..steps) {
            val x = w.toFloat() * i / steps
            val y = h * 0.82f - sin(i.toFloat() / steps * PI * 1.1 + 2.2).toFloat() * h * 0.05f
            hillNearPath.lineTo(x, y)
        }
        hillNearPath.lineTo(w.toFloat(), h.toFloat())
        hillNearPath.lineTo(0f, h.toFloat())
        hillNearPath.close()
    }

    private fun initParticles(w: Int, h: Int) {
        val rnd = Random(7)
        for (i in 0 until RAIN_COUNT) {
            rainX[i] = rnd.nextFloat() * w
            rainY[i] = rnd.nextFloat() * h
            rainSpeed[i] = h * (0.9f + rnd.nextFloat() * 0.6f)
            rainLen[i] = h * 0.02f + rnd.nextFloat() * h * 0.015f
        }
        for (i in 0 until SNOW_COUNT) {
            snowX[i] = rnd.nextFloat() * w
            snowY[i] = rnd.nextFloat() * h
            snowSpeed[i] = h * (0.08f + rnd.nextFloat() * 0.08f)
            snowSize[i] = 2f + rnd.nextFloat() * 3.5f
            snowPhase[i] = rnd.nextFloat() * 6.28f
        }
        particlesReady = true
    }

    // --- Группы погоды ---

    private fun isThunder(): Boolean = conditionCode in THUNDER_CODES
    private fun isRain(): Boolean = conditionCode in 5..12 || conditionCode == 17 || conditionCode == 18 || conditionCode == 40
    private fun isSnow(): Boolean = conditionCode in 13..16 || conditionCode in 41..43 || conditionCode == 46
    private fun isFog(): Boolean = conditionCode in 19..22
    private fun isClear(): Boolean = conditionCode == 31 || conditionCode == 32 || conditionCode == 33 || conditionCode == 34 || conditionCode == 36

    private fun applyPalette() {
        val night = !isDay
        fun c(id: Int) = ContextCompat.getColor(context, id)

        when {
            night && isClear() -> {
                skyTop = c(R.color.scene_clear_night_top); skyBottom = c(R.color.scene_clear_night_bottom)
                hillFar = c(R.color.scene_clear_night_hill_far); hillNear = c(R.color.scene_clear_night_hill_near)
            }
            night -> {
                skyTop = c(R.color.scene_cloudy_night_top); skyBottom = c(R.color.scene_cloudy_night_bottom)
                hillFar = c(R.color.scene_cloudy_night_hill_far); hillNear = c(R.color.scene_cloudy_night_hill_near)
            }
            isSnow() -> {
                skyTop = c(R.color.scene_snow_day_top); skyBottom = c(R.color.scene_snow_day_bottom)
                hillFar = c(R.color.scene_snow_day_hill_far); hillNear = c(R.color.scene_snow_day_hill_near)
            }
            isRain() || isThunder() -> {
                skyTop = c(R.color.scene_rain_day_top); skyBottom = c(R.color.scene_rain_day_bottom)
                hillFar = c(R.color.scene_rain_day_hill_far); hillNear = c(R.color.scene_rain_day_hill_near)
            }
            isClear() -> {
                skyTop = c(R.color.scene_clear_day_top); skyBottom = c(R.color.scene_clear_day_bottom)
                hillFar = c(R.color.scene_clear_day_hill_far); hillNear = c(R.color.scene_clear_day_hill_near)
            }
            else -> {
                skyTop = c(R.color.scene_cloudy_day_top); skyBottom = c(R.color.scene_cloudy_day_bottom)
                hillFar = c(R.color.scene_cloudy_day_hill_far); hillNear = c(R.color.scene_cloudy_day_hill_near)
            }
        }
        hillFarPaint.color = hillFar
        hillNearPaint.color = hillNear
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = SystemClock.uptimeMillis()
        val dt = min((now - lastFrameTime) / 1000f, 0.05f)
        lastFrameTime = now
        if (running) animTime += dt

        // Небо
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // Звёзды
        if (!isDay && (isClear() || conditionCode == 29 || conditionCode == 30)) {
            for (i in 0 until STAR_COUNT) {
                canvas.drawCircle(starX[i] * w, starY[i] * h, starSize[i], starPaint)
            }
        }

        // Солнце или луна
        if (isClear() || conditionCode == 29 || conditionCode == 30 || conditionCode == 27 || conditionCode == 28) {
            if (isDay) {
                canvas.drawCircle(w * 0.80f, h * 0.14f, h * 0.05f, sunPaint)
            } else {
                canvas.drawCircle(w * 0.80f, h * 0.14f, h * 0.04f, moonPaint)
            }
        }

        // Холмы, носок на дальнем, флюгер на ближнем (оба справа от текста)
        canvas.drawPath(hillFarPath, hillFarPaint)
        drawWindsock(canvas, w, h)
        canvas.drawPath(hillNearPath, hillNearPaint)
        drawVane(canvas, w, h)

        // Туман
        if (isFog()) {
            val drift = sin(animTime * 0.15f) * w * 0.04f
            canvas.drawOval(-w * 0.2f + drift, h * 0.45f, w * 0.8f + drift, h * 0.60f, fogPaint)
            canvas.drawOval(w * 0.3f - drift, h * 0.60f, w * 1.2f - drift, h * 0.75f, fogPaint)
            canvas.drawOval(-w * 0.1f + drift * 0.5f, h * 0.75f, w * 0.9f + drift * 0.5f, h * 0.92f, fogPaint)
        }

        // Осадки
        if (particlesReady) {
            if (isRain() || isThunder()) drawRain(canvas, w, h, dt)
            if (isSnow()) drawSnow(canvas, w, h, dt)
        }

        // Гроза
        if (isThunder() && running) {
            lightningTimer += dt
            if (lightningTimer > 7f) {
                lightningTimer = 0f
                lightningFlash = 0.55f
            }
            if (lightningFlash > 0f) {
                flashPaint.alpha = (lightningFlash * 255).toInt()
                canvas.drawRect(0f, 0f, w, h, flashPaint)
                lightningFlash -= dt * 3f
            }
        }

        // Растворение низа в фоне страницы
        canvas.drawRect(0f, h * 0.78f, w, h, fadePaint)

        // Следующий кадр
        if (running && isAttachedToWindow) {
            removeCallbacks(frameRunnable)
            postDelayed(frameRunnable, FRAME_DELAY_MS)
        }
    }

    private fun drawRain(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val slant = (windMs.toFloat().coerceIn(0f, 15f) / 15f) * 14f *
            (if (windDirDeg in 90..270) -1f else 1f)
        for (i in 0 until RAIN_COUNT) {
            if (running) {
                rainY[i] += rainSpeed[i] * dt
                rainX[i] += slant * dt * 12f
                if (rainY[i] > h) {
                    rainY[i] = -rainLen[i]
                    rainX[i] = (rainX[i] + w * 0.37f) % w
                }
                if (rainX[i] > w) rainX[i] -= w
                if (rainX[i] < 0) rainX[i] += w
            }
            canvas.drawLine(rainX[i], rainY[i], rainX[i] + slant, rainY[i] + rainLen[i], rainPaint)
        }
    }

    private fun drawSnow(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val drift = (windMs.toFloat().coerceIn(0f, 10f) / 10f) * 30f *
            (if (windDirDeg in 90..270) -1f else 1f)
        for (i in 0 until SNOW_COUNT) {
            if (running) {
                snowY[i] += snowSpeed[i] * dt
                snowX[i] += (sin(animTime * 1.2f + snowPhase[i]) * 12f + drift) * dt
                if (snowY[i] > h) {
                    snowY[i] = -6f
                    snowX[i] = (snowX[i] + w * 0.61f) % w
                }
                if (snowX[i] > w) snowX[i] -= w
                if (snowX[i] < 0) snowX[i] += w
            }
            canvas.drawCircle(snowX[i], snowY[i], snowSize[i], snowPaint)
        }
    }

    /**
     * Ветровой мешок на дальнем холме, справа от текста.
     * Штиль — висит, 15 м/с — почти горизонтален. Ветер с востока — мешок влево.
     */
    private fun drawWindsock(canvas: Canvas, w: Float, h: Float) {
        val baseX = w * 0.60f
        val baseY = h * 0.64f
        val poleH = h * 0.15f
        val topY = baseY - poleH

        canvas.drawLine(baseX, baseY, baseX, topY, polePaint)

        val strength = (windMs.toFloat().coerceIn(0f, 15f) / 15f)
        val sway = sin(animTime * 2.2f) * (3f + strength * 4f)
        val angleDeg = 90f - strength * 85f + sway * (1f - strength * 0.6f)
        val toLeft = windDirDeg in 90..270
        val dirSign = if (toLeft) -1f else 1f
        val angleRad = Math.toRadians(angleDeg.toDouble())

        val sockLen = h * 0.10f
        val segCount = 5
        val startRadius = h * 0.016f

        val dx = (cos(angleRad).toFloat()) * dirSign
        val dy = sin(angleRad).toFloat()

        for (s in 0 until segCount) {
            val t0 = s.toFloat() / segCount
            val t1 = (s + 1).toFloat() / segCount
            val r0 = startRadius * (1f - t0 * 0.55f)
            val r1 = startRadius * (1f - t1 * 0.55f)
            val x0 = baseX + dx * sockLen * t0
            val y0 = topY + dy * sockLen * t0
            val x1 = baseX + dx * sockLen * t1
            val y1 = topY + dy * sockLen * t1
            val px = -dy
            val py = dx
            sockPath.reset()
            sockPath.moveTo(x0 + px * r0, y0 + py * r0)
            sockPath.lineTo(x1 + px * r1, y1 + py * r1)
            sockPath.lineTo(x1 - px * r1, y1 - py * r1)
            sockPath.lineTo(x0 - px * r0, y0 - py * r0)
            sockPath.close()
            canvas.drawPath(sockPath, if (s % 2 == 0) sockOrangePaint else sockWhitePaint)
        }
    }

    /**
     * Флюгер на ближнем холме, у правого края.
     * Стрелка указывает, ОТКУДА дует ветер.
     */
    private fun drawVane(canvas: Canvas, w: Float, h: Float) {
        val baseX = w * 0.85f
        val baseY = h * 0.80f
        val poleH = h * 0.12f
        val topY = baseY - poleH

        canvas.drawLine(baseX, baseY, baseX, topY, polePaint)

        val horizontal = sin(Math.toRadians(windDirDeg.toDouble())).toFloat()
        val len = h * 0.045f
        val tailLen = len * 0.6f

        canvas.save()
        canvas.translate(baseX, topY)
        val jitter = if (windMs > 8) sin(animTime * 9f) * 2f else 0f
        canvas.rotate(jitter)

        arrowPath.reset()
        arrowPath.moveTo(horizontal * len, -len * 0.18f)
        arrowPath.lineTo(horizontal * len * 0.55f, -len * 0.30f)
        arrowPath.lineTo(horizontal * len * 0.55f, -len * 0.06f)
        arrowPath.lineTo(-horizontal * tailLen, -len * 0.06f)
        arrowPath.lineTo(-horizontal * tailLen, -len * 0.34f)
        arrowPath.lineTo(-horizontal * tailLen * 1.35f, -len * 0.12f)
        arrowPath.lineTo(-horizontal * tailLen, len * 0.10f)
        arrowPath.lineTo(-horizontal * tailLen, -len * 0.02f)
        arrowPath.lineTo(horizontal * len * 0.55f, -len * 0.02f)
        arrowPath.lineTo(horizontal * len * 0.55f, len * 0.06f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }
}
