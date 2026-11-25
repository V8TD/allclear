package com.v8td.allclear.data

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Провайдер OpenWeatherMap, бесплатное API 2.5 (ключ без карты).
 * /weather (текущая) + /forecast (5 дней, шаг 3 часа). One Call 3.0 НЕ используем.
 *
 * Исправлено в ревизии:
 * - Дни группируются по ЛОКАЛЬНОМУ времени города (city.timezone), не по UTC.
 * - Ледяной дождь (511) -> код 10.
 * - Пустой ключ сразу отсекается без сетевого запроса.
 */
class OpenWeatherMapProvider(private val apiKey: String) : BaseWeatherProvider() {

    override val name: String = "OpenWeatherMap"

    companion object {
        private const val CURRENT_URL = "https://api.openweathermap.org/data/2.5/weather"
        private const val FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast"
    }

    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        if (apiKey.isBlank()) return null
        val la = roundCoord(lat)
        val lo = roundCoord(lon)

        val current = fetchJson(
            "$CURRENT_URL?lat=$la&lon=$lo&appid=$apiKey&units=metric&lang=ru"
        ) ?: return null
        val forecast = fetchJson(
            "$FORECAST_URL?lat=$la&lon=$lo&appid=$apiKey&units=metric&lang=ru&cnt=40"
        ) ?: return null

        return try {
            mergeResponses(current, forecast)
        } catch (e: Exception) {
            null
        }
    }

    private fun mergeResponses(current: JSONObject, forecast: JSONObject): WeatherData {
        val main = current.getJSONObject("main")
        val wind = current.getJSONObject("wind")
        val weatherArr = current.getJSONArray("weather")
        val sys = current.getJSONObject("sys")

        val owmId = if (weatherArr.length() > 0)
            weatherArr.getJSONObject(0).getInt("id") else 800
        val description = if (weatherArr.length() > 0)
            weatherArr.getJSONObject(0).getString("description") else "Нет данных"

        // Смещение часового пояса города (секунды) — из ответа forecast
        val tzOffsetSec = forecast.optJSONObject("city")?.optInt("timezone", 0) ?: 0
        val tzOffsetMs = tzOffsetSec * 1000L

        val list = forecast.getJSONArray("list")

        // Почасовой (шаг 3ч): первые 8 записей = сутки вперёд
        val hourlyList = mutableListOf<HourlyForecast>()
        for (i in 0 until minOf(list.length(), 8)) {
            val item = list.getJSONObject(i)
            val itemMain = item.getJSONObject("main")
            val w = item.getJSONArray("weather")
            val itemId = if (w.length() > 0) w.getJSONObject(0).getInt("id") else 800
            val icon = if (w.length() > 0) w.getJSONObject(0).optString("icon", "01d") else "01d"
            hourlyList.add(
                HourlyForecast(
                    time = item.getLong("dt") * 1000L,
                    temp = itemMain.getDouble("temp"),
                    conditionCode = owmIdToYahoo(itemId, icon.endsWith("d")),
                    precipProb = (item.optDouble("pop", 0.0) * 100).toInt(),
                    isDay = icon.endsWith("d")
                )
            )
        }

        // Дневной прогноз: группировка по локальной дате города
        data class DayAgg(
            var min: Double = Double.MAX_VALUE,
            var max: Double = -Double.MAX_VALUE,
            val ids: MutableList<Pair<Int, Boolean>> = mutableListOf(),
            var maxPop: Double = 0.0
        )

        val dayMap = linkedMapOf<Long, DayAgg>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val dtMs = item.getLong("dt") * 1000L
            val dayStart = localDayStart(dtMs, tzOffsetMs)
            val agg = dayMap.getOrPut(dayStart) { DayAgg() }

            val itemMain = item.getJSONObject("main")
            // temp_min/temp_max в 3-часовых блоках точнее, чем temp
            val tMin = itemMain.optDouble("temp_min", itemMain.getDouble("temp"))
            val tMax = itemMain.optDouble("temp_max", itemMain.getDouble("temp"))
            agg.min = minOf(agg.min, tMin)
            agg.max = maxOf(agg.max, tMax)

            val w = item.getJSONArray("weather")
            if (w.length() > 0) {
                val icon = w.getJSONObject(0).optString("icon", "01d")
                agg.ids.add(Pair(w.getJSONObject(0).getInt("id"), icon.endsWith("d")))
            }
            agg.maxPop = maxOf(agg.maxPop, item.optDouble("pop", 0.0))
        }

        val dailyList = dayMap.entries.take(6).mapNotNull { (dayStart, agg) ->
            if (agg.min == Double.MAX_VALUE) return@mapNotNull null
            // Доминирующий дневной код: предпочитаем дневные записи, иначе любые
            val dayIds = agg.ids.filter { it.second }.map { it.first }
                .ifEmpty { agg.ids.map { it.first } }
            val dominantId = dayIds.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: 800
            DailyForecast(
                date = dayStart,
                tempMin = agg.min,
                tempMax = agg.max,
                conditionCode = owmIdToYahoo(dominantId, true),
                precipProb = (agg.maxPop * 100).toInt()
            )
        }

        return WeatherData(
            temperature = main.getDouble("temp"),
            feelsLike = main.optDouble("feels_like", main.getDouble("temp")),
            conditionCode = owmIdToYahoo(owmId, true),
            description = description.replaceFirstChar { it.uppercase() },
            humidity = main.optInt("humidity", 0),
            windSpeed = wind.optDouble("speed", 0.0),
            windGust = wind.optDoubleOrNull("gust"),
            windDirection = wind.optInt("deg", 0),
            pressure = main.optDouble("pressure", 0.0),
            visibility = current.optDoubleOrNull("visibility")?.div(1000.0),
            uvIndex = null, // в бесплатном 2.5 нет УФ
            precipitationProb = if (list.length() > 0)
                (list.getJSONObject(0).optDouble("pop", 0.0) * 100).toInt() else null,
            dewPoint = null, // в 2.5 нет точки росы
            sunrise = sys.optLong("sunrise", 0L) * 1000L,
            sunset = sys.optLong("sunset", 0L) * 1000L,
            observationTime = current.optLong("dt", 0L) * 1000L,
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }

    /** Начало локального дня (Unix ms) для момента dtMs при смещении tzOffsetMs. */
    private fun localDayStart(dtMs: Long, tzOffsetMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = dtMs + tzOffsetMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis - tzOffsetMs
    }

    /** OWM condition ID -> Yahoo/Chronus. */
    private fun owmIdToYahoo(id: Int, isDay: Boolean): Int = when {
        id in 200..299 -> 4
        id in 300..399 -> 9
        id == 511 -> 10               // ледяной дождь (было ошибочно 8)
        id in 500..504 -> 12
        id in 520..531 -> 11
        id == 611 || id == 612 || id == 613 -> 18
        id == 615 || id == 616 -> 5
        id in 600..699 -> 16
        id == 701 || id == 741 -> 20
        id == 711 -> 22
        id == 721 -> 21
        id == 731 || id == 751 || id == 761 -> 19
        id == 762 -> 22
        id == 771 || id == 781 -> 0
        id == 800 -> if (isDay) 32 else 31
        id == 801 -> if (isDay) 34 else 33
        id == 802 -> if (isDay) 30 else 29
        id == 803 -> if (isDay) 28 else 27
        id == 804 -> 26
        else -> 3200
    }
}
