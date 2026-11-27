package com.v8td.allclear.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Провайдер MET Norway (Yr.no), версия complete.
 * Бесплатный, User-Agent с контактом обязателен.
 * ПРАВИЛА (ред. 05.11.2026): запросы ТОЛЬКО когда приложение используется.
 * В фоне (WorkManager) репозиторий подменяет его на Open-Meteo.
 * Координаты — максимум 4 знака после запятой (иначе 403).
 * Атрибуция: CC BY 4.0.
 */
class MetNorwayProvider : BaseWeatherProvider() {

    override val name: String = "MET Norway"

    companion object {
        const val USER_AGENT = "AllClear/1.0 github.com/V8TD/allclear"
        private const val BASE_URL =
            "https://api.met.no/weatherapi/locationforecast/2.0/complete"
    }

    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val url = "$BASE_URL?lat=${roundCoord(lat)}&lon=${roundCoord(lon)}"
        val json = fetchJson(url, mapOf("User-Agent" to USER_AGENT)) ?: return null
        return try {
            parseResponse(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResponse(json: JSONObject): WeatherData? {
        val properties = json.getJSONObject("properties")
        val timeseries = properties.getJSONArray("timeseries")
        if (timeseries.length() == 0) return null

        val currentEntry = timeseries.getJSONObject(0)
        val currentData = currentEntry.getJSONObject("data")
        val details = currentData.getJSONObject("instant").getJSONObject("details")

        val temperature = details.getDouble("air_temperature")
        val feelsLike = details.optDouble("apparent_air_temperature", temperature)
        val humidity = details.optDouble("relative_humidity", 0.0).toInt()
        val windSpeed = details.optDouble("wind_speed", 0.0)
        val windDirection = details.optDouble("wind_from_direction", 0.0).toInt()
        val pressure = details.optDouble("air_pressure_at_sea_level", 0.0)
        val dewPoint = details.optDoubleOrNull("dew_point_temperature")
        val uvIndex = details.optDoubleOrNull("ultraviolet_index_clear_sky")
        val windGust = details.optDoubleOrNull("wind_speed_of_gust")

        val next1h = currentData.optJSONObject("next_1_hours")
        val symbolCode = next1h?.optJSONObject("summary")
            ?.optString("symbol_code", "") ?: ""
        val precipAmount = next1h?.optJSONObject("details")
            ?.optDouble("precipitation_amount", -1.0) ?: -1.0

        // Если символа нет (бывает на дальних часах) — берём из 6-часового блока
        val effectiveSymbol = symbolCode.ifEmpty {
            currentData.optJSONObject("next_6_hours")
                ?.optJSONObject("summary")?.optString("symbol_code", "") ?: ""
        }

        // Почасовой прогноз: 24 записи с текущего часа включительно
        val hourlyList = mutableListOf<HourlyForecast>()
        for (i in 0 until timeseries.length()) {
            if (hourlyList.size >= 24) break
            val entry = timeseries.getJSONObject(i)
            val data = entry.getJSONObject("data")
            val d = data.getJSONObject("instant").getJSONObject("details")
            val n1 = data.optJSONObject("next_1_hours")
            val sym = n1?.optJSONObject("summary")?.optString("symbol_code", "")
                ?: data.optJSONObject("next_6_hours")
                    ?.optJSONObject("summary")?.optString("symbol_code", "") ?: ""
            val amount = n1?.optJSONObject("details")
                ?.optDouble("precipitation_amount", -1.0) ?: -1.0
            val timeMs = parseUtcTime(entry.getString("time"))

            hourlyList.add(
                HourlyForecast(
                    time = timeMs,
                    temp = d.getDouble("air_temperature"),
                    conditionCode = metSymbolToYahoo(sym),
                    // MET даёт количество осадков, не вероятность.
                    // >0 мм — осадки ожидаются (100), 0 — не ожидаются (0), нет данных — -1.
                    precipProb = when {
                        amount < 0 -> -1
                        amount > 0.0 -> 100
                        else -> 0
                    },
                    isDay = sym.contains("_day") || (!sym.contains("_night"))
                )
            )
        }

        // Дневной прогноз: собираем мин/макс из 6-часовых блоков + добор мгновенными
        data class DayAgg(
            var min: Double = Double.MAX_VALUE,
            var max: Double = -Double.MAX_VALUE,
            val codes: MutableList<Int> = mutableListOf(),
            var hadBlock: Boolean = false
        )

        val dayMap = linkedMapOf<String, DayAgg>()
        for (i in 0 until timeseries.length()) {
            val entry = timeseries.getJSONObject(i)
            val dateStr = entry.getString("time").substring(0, 10)
            val agg = dayMap.getOrPut(dateStr) { DayAgg() }
            val data = entry.getJSONObject("data")
            val temp = data.getJSONObject("instant")
                .getJSONObject("details").getDouble("air_temperature")
            agg.min = minOf(agg.min, temp)
            agg.max = maxOf(agg.max, temp)

            val n6 = data.optJSONObject("next_6_hours")
            val n6details = n6?.optJSONObject("details")
            if (n6details != null) {
                val bMin = n6details.optDouble("air_temperature_min", Double.NaN)
                val bMax = n6details.optDouble("air_temperature_max", Double.NaN)
                if (!bMin.isNaN()) { agg.min = minOf(agg.min, bMin); agg.hadBlock = true }
                if (!bMax.isNaN()) { agg.max = maxOf(agg.max, bMax); agg.hadBlock = true }
            }
            val sym = n6?.optJSONObject("summary")?.optString("symbol_code", "")
                ?: data.optJSONObject("next_1_hours")
                    ?.optJSONObject("summary")?.optString("symbol_code", "") ?: ""
            if (sym.isNotEmpty()) agg.codes.add(metSymbolToYahoo(sym))
        }

        val dailyList = dayMap.entries.take(8).mapNotNull { (dateStr, agg) ->
            if (agg.min == Double.MAX_VALUE) return@mapNotNull null
            val dominant = agg.codes.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: 3200
            DailyForecast(
                date = parseUtcDate(dateStr),
                tempMin = agg.min,
                tempMax = agg.max,
                conditionCode = dominant,
                precipProb = -1  // вероятности MET не даёт
            )
        }

        return WeatherData(
            temperature = temperature,
            feelsLike = feelsLike,
            conditionCode = metSymbolToYahoo(effectiveSymbol),
            description = metSymbolToDescription(effectiveSymbol),
            humidity = humidity,
            windSpeed = windSpeed,
            windGust = windGust,
            windDirection = windDirection,
            pressure = pressure,
            visibility = null,
            uvIndex = uvIndex,
            precipitationProb = when {
                precipAmount < 0 -> null
                precipAmount > 0.0 -> 100
                else -> 0
            },
            dewPoint = dewPoint,
            sunrise = 0L,  // locationforecast не содержит восход/закат
            sunset = 0L,
            observationTime = parseUtcTime(currentEntry.getString("time")),
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }

    /** symbol_code -> Yahoo/Chronus. Суффиксы _day/_night/_polartwilight учитываются. */
    private fun metSymbolToYahoo(symbol: String): Int {
        if (symbol.isEmpty()) return 3200
        val isNight = symbol.contains("_night")
        val base = symbol.substringBefore("_")
        return when (base) {
            "clearsky" -> if (isNight) 31 else 32
            "fair" -> if (isNight) 33 else 34
            "partlycloudy" -> if (isNight) 29 else 30
            "cloudy" -> 26
            "fog" -> 20
            "lightrainshowers", "rainshowers", "heavyrainshowers" -> 11
            "lightrain", "rain", "heavyrain" -> 12
            "lightrainandthunder", "rainandthunder", "heavyrainandthunder",
            "lightrainshowersandthunder", "rainshowersandthunder",
            "heavyrainshowersandthunder" -> 4
            "lightsleet", "sleet", "heavysleet" -> 18
            "lightsleetshowers", "sleetshowers", "heavysleetshowers" -> 6
            "lightsleetandthunder", "sleetandthunder", "heavysleetandthunder",
            "lightsleetshowersandthunder", "sleetshowersandthunder",
            "heavysleetshowersandthunder" -> 4
            "lightsnow", "snow", "heavysnow" -> 16
            "lightsnowshowers", "snowshowers", "heavysnowshowers" -> 14
            "lightsnowandthunder", "snowandthunder", "heavysnowandthunder",
            "lightsnowshowersandthunder", "snowshowersandthunder",
            "heavysnowshowersandthunder" -> 4
            else -> 3200
        }
    }

    private fun metSymbolToDescription(symbol: String): String {
        if (symbol.isEmpty()) return "Нет данных"
        return when (symbol.substringBefore("_")) {
            "clearsky" -> "Ясно"
            "fair" -> "Малооблачно"
            "partlycloudy" -> "Переменная облачность"
            "cloudy" -> "Пасмурно"
            "fog" -> "Туман"
            "lightrainshowers" -> "Небольшой ливень"
            "rainshowers" -> "Ливень"
            "heavyrainshowers" -> "Сильный ливень"
            "lightrain" -> "Небольшой дождь"
            "rain" -> "Дождь"
            "heavyrain" -> "Сильный дождь"
            "lightsleet", "sleet", "heavysleet",
            "lightsleetshowers", "sleetshowers", "heavysleetshowers" -> "Мокрый снег"
            "lightsnow" -> "Небольшой снег"
            "snow" -> "Снег"
            "heavysnow" -> "Сильный снег"
            "lightsnowshowers", "snowshowers", "heavysnowshowers" -> "Снегопад"
            else -> if (symbol.contains("thunder")) "Гроза" else "Нет данных"
        }
    }

    /** "2026-11-25T22:00:00Z" -> Unix ms (UTC). */
    private fun parseUtcTime(iso: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseUtcDate(date: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
