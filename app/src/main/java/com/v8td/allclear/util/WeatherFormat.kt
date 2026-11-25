package com.v8td.allclear.util

import android.content.Context
import androidx.core.content.edit
import com.v8td.allclear.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Форматирование значений погоды для UI.
 * Единицы хранятся в том же файле настроек ("allclear_settings"):
 * температура °C/°F, ветер м/с или км/ч, давление мм рт. ст. или гПа.
 * По умолчанию: °C, м/с, мм рт. ст. (как принято в России).
 */
object WeatherFormat {

    const val KEY_TEMP_UNIT = "unit_temperature"   // "c" | "f"
    const val KEY_WIND_UNIT = "unit_wind"          // "ms" | "kmh"
    const val KEY_PRESSURE_UNIT = "unit_pressure"  // "mmhg" | "hpa"

    const val DASH = "—"

    private fun prefs(context: Context) =
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun tempUnit(context: Context): String =
        prefs(context).getString(KEY_TEMP_UNIT, "c") ?: "c"

    fun windUnit(context: Context): String =
        prefs(context).getString(KEY_WIND_UNIT, "ms") ?: "ms"

    fun pressureUnit(context: Context): String =
        prefs(context).getString(KEY_PRESSURE_UNIT, "mmhg") ?: "mmhg"

    fun setTempUnit(context: Context, unit: String) =
        prefs(context).edit { putString(KEY_TEMP_UNIT, unit) }

    fun setWindUnit(context: Context, unit: String) =
        prefs(context).edit { putString(KEY_WIND_UNIT, unit) }

    fun setPressureUnit(context: Context, unit: String) =
        prefs(context).edit { putString(KEY_PRESSURE_UNIT, unit) }

    // --- Температура ---

    /** "-4°" (округление до целого, как в MIUI). null -> "—" */
    fun temp(context: Context, celsius: Double?): String {
        if (celsius == null || celsius.isNaN()) return DASH
        val value = if (tempUnit(context) == "f") celsius * 9.0 / 5.0 + 32.0 else celsius
        return "${value.roundToInt()}°"
    }

    /** Число без градуса — для графиков. */
    fun tempValue(context: Context, celsius: Double): Int {
        val value = if (tempUnit(context) == "f") celsius * 9.0 / 5.0 + 32.0 else celsius
        return value.roundToInt()
    }

    // --- Ветер ---

    /** "3 м/с" */
    fun wind(context: Context, ms: Double?): String {
        if (ms == null || ms.isNaN()) return DASH
        return if (windUnit(context) == "kmh") {
            "${(ms * 3.6).roundToInt()} ${context.getString(R.string.unit_kmh)}"
        } else {
            "${ms.roundToInt()} ${context.getString(R.string.unit_ms)}"
        }
    }

    /** Направление буквами: С, СВ, В, ... */
    fun windDirection(context: Context, degrees: Int): String {
        val dirs = intArrayOf(
            R.string.wind_dir_n, R.string.wind_dir_ne, R.string.wind_dir_e, R.string.wind_dir_se,
            R.string.wind_dir_s, R.string.wind_dir_sw, R.string.wind_dir_w, R.string.wind_dir_nw
        )
        val index = (((degrees % 360) + 360 + 22) % 360) / 45
        return context.getString(dirs[index])
    }

    /** "ЮВ 3 м/с" — компактно для верхней строки. */
    fun windCompact(context: Context, ms: Double?, degrees: Int): String {
        val speed = wind(context, ms)
        if (speed == DASH) return DASH
        return "${windDirection(context, degrees)} $speed"
    }

    // --- Давление ---

    /** "770 мм рт. ст." или "1027 гПа" */
    fun pressure(context: Context, hpa: Double?): String {
        if (hpa == null || hpa.isNaN() || hpa <= 0.0) return DASH
        return if (pressureUnit(context) == "hpa") {
            "${hpa.roundToInt()} ${context.getString(R.string.unit_hpa)}"
        } else {
            "${(hpa * 0.750062).roundToInt()} ${context.getString(R.string.unit_mhg)}"
        }
    }

    // --- Прочие значения ---

    fun percent(value: Int?): String =
        if (value == null || value < 0) DASH else "$value%"

    fun visibility(context: Context, km: Double?): String {
        if (km == null || km.isNaN()) return DASH
        return if (km >= 10.0) "10+ ${context.getString(R.string.unit_km)}"
        else String.format(Locale.getDefault(), "%.1f %s", km, context.getString(R.string.unit_km))
    }

    fun uv(context: Context, value: Double?): String {
        if (value == null || value.isNaN()) return DASH
        val v = value.roundToInt()
        val level = when {
            v <= 2 -> R.string.uv_low
            v <= 5 -> R.string.uv_moderate
            v <= 7 -> R.string.uv_high
            v <= 10 -> R.string.uv_very_high
            else -> R.string.uv_extreme
        }
        return "$v · ${context.getString(level)}"
    }

    fun dewPoint(context: Context, celsius: Double?): String = temp(context, celsius)

    // --- Время ---

    /** "07:28" локальное время устройства. 0/null -> "—" */
    fun time(timeMs: Long?): String {
        if (timeMs == null || timeMs <= 0L) return DASH
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
    }

    /** "26 нояб." */
    fun shortDate(timeMs: Long): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timeMs))

    /** "Сегодня" / "Завтра" / "Сб" — для дневного прогноза. */
    fun dayLabel(context: Context, dayStartMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val dayMs = 86_400_000L
        return when {
            abs(dayStartMs - todayStart) < dayMs / 2 -> context.getString(R.string.today)
            abs(dayStartMs - todayStart - dayMs) < dayMs / 2 -> context.getString(R.string.tomorrow)
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(dayStartMs))
                .replaceFirstChar { it.uppercase() }
        }
    }

    /** «Обновлено 5 мин назад» */
    fun updatedAgo(context: Context, timeMs: Long): String {
        if (timeMs <= 0L) return context.getString(R.string.no_data)
        val diffMin = (System.currentTimeMillis() - timeMs) / 60_000L
        return when {
            diffMin < 1 -> context.getString(R.string.updated_just_now)
            diffMin < 60 -> context.getString(R.string.updated_min_ago, diffMin.toInt())
            else -> context.getString(R.string.updated_hours_ago, (diffMin / 60).toInt())
        }
    }

    /** Длительность дня: "9 ч 20 мин" */
    fun dayLength(context: Context, sunriseMs: Long, sunsetMs: Long): String {
        if (sunriseMs <= 0L || sunsetMs <= 0L || sunsetMs <= sunriseMs) return DASH
        val totalMin = (sunsetMs - sunriseMs) / 60_000L
        return context.getString(R.string.day_length_format, (totalMin / 60).toInt(), (totalMin % 60).toInt())
    }

    // --- Луна (локальный расчёт, без сети) ---

    /**
     * Фаза луны 0..7: 0 новолуние, 1 растущий серп, 2 первая четверть,
     * 3 растущая выпуклая, 4 полнолуние, 5 убывающая выпуклая, 6 последняя четверть, 7 убывающий серп.
     * Точность ~1 день — для плитки достаточно.
     */
    fun moonPhaseIndex(timeMs: Long = System.currentTimeMillis()): Int {
        // Известное новолуние: 6 января 2000, 18:14 UTC
        val knownNewMoon = 947_182_440_000.0
        val synodicMonth = 29.530588853 * 86_400_000.0
        val days = (timeMs - knownNewMoon) / 86_400_000.0
        val cycles = days / 29.530588853
        val age = (cycles - floor(cycles)) * 29.530588853 // возраст луны в днях
        return when {
            age < 1.84566 -> 0
            age < 5.53699 -> 1
            age < 9.22831 -> 2
            age < 12.91963 -> 3
            age < 16.61096 -> 4
            age < 20.30228 -> 5
            age < 23.99361 -> 6
            age < 27.68493 -> 7
            else -> 0
        }
    }

    fun moonPhaseName(context: Context, timeMs: Long = System.currentTimeMillis()): String {
        val names = intArrayOf(
            R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent
        )
        return context.getString(names[moonPhaseIndex(timeMs)])
    }

    /** Освещённость луны 0..1 — для рисунка фазы. */
    fun moonIllumination(timeMs: Long = System.currentTimeMillis()): Double {
        val phaseAngle = 2.0 * Math.PI * moonAgeFraction(timeMs)
        return (1.0 - kotlin.math.cos(phaseAngle)) / 2.0
    }

    private fun moonAgeFraction(timeMs: Long): Double {
        val knownNewMoon = 947_182_440_000.0
        val cycles = (timeMs - knownNewMoon) / (29.530588853 * 86_400_000.0)
        return cycles - floor(cycles)
    }
}
