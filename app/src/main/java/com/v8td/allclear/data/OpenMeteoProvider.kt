package com.v8td.allclear.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Провайдер Open-Meteo (основной, без ключа).
 * Атрибуция: CC BY 4.0 — обязательна в экране «О приложении».
 * Документация: https://open-meteo.com/en/docs
 *
 * Исправлено в ревизии:
 * - Парсинг дат через SimpleDateFormat (старый split ломал дату и ронял весь ответ).
 * - Почасовой прогноз включает текущий час.
 * - Добавлены видимость, точка росы, давление на уровне моря.
 * - Ледяной дождь (66/67) -> код 10, а не 8.
 */
class OpenMeteoProvider : BaseWeatherProvider() {

    override val name: String = "Open-Meteo"

    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val url = buildUrl(roundCoord(lat), roundCoord(lon))
        val json = fetchJson(url) ?: return null
        if (json.optBoolean("error", false)) return null
        return try {
            parseResponse(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUrl(lat: Double, lon: Double): String {
        return "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m," +
            "pressure_msl,dew_point_2m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability," +
            "visibility,dew_point_2m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,sunrise,sunset,uv_index_max" +
            "&timezone=auto" +
            "&forecast_days=7&forecast_hours=25" +
            "&wind_speed_unit=ms&precipitation_unit=mm"
    }

    private fun parseResponse(json: JSONObject): WeatherData {
        val current = json.getJSONObject("current")
        val hourly = json.getJSONObject("hourly")
        val daily = json.getJSONObject("daily")

        // Смещение часового пояса локации (секунды). Все строки времени в ответе —
        // локальные для этой локации, поэтому парсим их как UTC и вычитаем смещение.
        val offsetMs = json.optInt("utc_offset_seconds", 0) * 1000L

        val wmoCode = current.getInt("weather_code")
        val isDayNow = current.optInt("is_day", 1) == 1

        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")
        val hourlyCodes = hourly.getJSONArray("weather_code")
        val hourlyPrecip = hourly.getJSONArray("precipitation_probability")
        val hourlyVis = hourly.optJSONArray("visibility")
        val hourlyDew = hourly.optJSONArray("dew_point_2m")
        val hourlyIsDay = hourly.optJSONArray("is_day")

        // Индекс текущего часа в почасовом массиве (forecast_hours начинает с текущего часа)
        val nowMs = System.currentTimeMillis()
        var currentHourIndex = 0
        for (i in 0 until hourlyTimes.length()) {
            val t = parseLocalIso(hourlyTimes.getString(i), offsetMs)
            if (t <= nowMs) currentHourIndex = i else break
        }

        // Видимость: метры -> км
        val visibility = hourlyVis?.optDouble(currentHourIndex, Double.NaN)?.let {
            if (it.isNaN()) null else it / 1000.0
        }
        val dewPoint = current.optDoubleOrNull("dew_point_2m")
            ?: hourlyDew?.optDouble(currentHourIndex, Double.NaN)?.let {
                if (it.isNaN()) null else it
            }

        val uvIndex = daily.optJSONArray("uv_index_max")
            ?.optDouble(0, Double.NaN)?.let { if (it.isNaN()) null else it }

        val sunriseArr = daily.optJSONArray("sunrise")
        val sunsetArr = daily.optJSONArray("sunset")
        val sunrise = if (sunriseArr != null && sunriseArr.length() > 0 && !sunriseArr.isNull(0))
            parseLocalIso(sunriseArr.getString(0), offsetMs) else 0L
        val sunset = if (sunsetArr != null && sunsetArr.length() > 0 && !sunsetArr.isNull(0))
            parseLocalIso(sunsetArr.getString(0), offsetMs) else 0L

        // Почасовой прогноз: 24 часа начиная с ТЕКУЩЕГО часа включительно
        val hourlyList = mutableListOf<HourlyForecast>()
        for (i in currentHourIndex until hourlyTimes.length()) {
            if (hourlyList.size >= 24) break
            val hourIsDay = hourlyIsDay?.optInt(i, 1) == 1
            hourlyList.add(
                HourlyForecast(
                    time = parseLocalIso(hourlyTimes.getString(i), offsetMs),
                    temp = hourlyTemps.getDouble(i),
                    conditionCode = mapWmoToYahoo(hourlyCodes.getInt(i), hourIsDay),
                    precipProb = hourlyPrecip.optInt(i, -1),
                    isDay = hourIsDay
                )
            )
        }

        // Дневной прогноз: 7 дней
        val dailyDates = daily.getJSONArray("time")
        val dailyMax = daily.getJSONArray("temperature_2m_max")
        val dailyMin = daily.getJSONArray("temperature_2m_min")
        val dailyCodes = daily.getJSONArray("weather_code")
        val dailyPrecip = daily.getJSONArray("precipitation_probability_max")

        val dailyList = mutableListOf<DailyForecast>()
        for (i in 0 until dailyDates.length()) {
            dailyList.add(
                DailyForecast(
                    date = parseLocalDate(dailyDates.getString(i), offsetMs),
                    tempMin = dailyMin.getDouble(i),
                    tempMax = dailyMax.getDouble(i),
                    conditionCode = mapWmoToYahoo(dailyCodes.getInt(i), true),
                    precipProb = dailyPrecip.optInt(i, -1)
                )
            )
        }

        return WeatherData(
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.getDouble("apparent_temperature"),
            conditionCode = mapWmoToYahoo(wmoCode, isDayNow),
            description = wmoToDescription(wmoCode),
            humidity = current.getInt("relative_humidity_2m"),
            windSpeed = current.getDouble("wind_speed_10m"),
            windGust = current.optDoubleOrNull("wind_gusts_10m"),
            windDirection = current.optInt("wind_direction_10m", 0),
            pressure = current.optDouble("pressure_msl", 0.0),
            visibility = visibility,
            uvIndex = uvIndex,
            precipitationProb = hourlyPrecip.optInt(currentHourIndex, -1).let {
                if (it < 0) null else it
            },
            dewPoint = dewPoint,
            sunrise = sunrise,
            sunset = sunset,
            observationTime = parseLocalIso(current.optString("time", ""), offsetMs),
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }

    /** WMO -> Yahoo/Chronus (0-47). */
    private fun mapWmoToYahoo(wmo: Int, isDay: Boolean): Int = when (wmo) {
        0 -> if (isDay) 32 else 31
        1 -> if (isDay) 34 else 33
        2 -> if (isDay) 30 else 29
        3 -> 26
        45, 48 -> 20
        51, 53, 55 -> 9
        56, 57 -> 8
        61 -> 11
        63, 65 -> 12
        66, 67 -> 10               // Ледяной дождь (было ошибочно 8)
        71 -> 14
        73, 75 -> 16
        77 -> 13
        80, 81, 82 -> 11
        85, 86 -> 14
        95, 96, 97, 99 -> 4
        else -> 3200               // «Нет данных» в формате Chronus
    }

    private fun wmoToDescription(wmo: Int): String = when (wmo) {
        0 -> "Ясно"
        1 -> "Малооблачно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51 -> "Лёгкая морось"
        53 -> "Морось"
        55 -> "Сильная морось"
        56, 57 -> "Ледяная морось"
        61 -> "Небольшой дождь"
        63 -> "Дождь"
        65 -> "Сильный дождь"
        66, 67 -> "Ледяной дождь"
        71 -> "Небольшой снег"
        73 -> "Снег"
        75 -> "Сильный снег"
        77 -> "Снежная крупа"
        80 -> "Небольшой ливень"
        81 -> "Ливень"
        82 -> "Сильный ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        97 -> "Сильная гроза"
        else -> "Нет данных"
    }

    /**
     * "2026-11-26T14:00" — локальное время локации без указания зоны.
     * Парсим как UTC и вычитаем utc_offset -> получаем настоящий Unix ms.
     */
    private fun parseLocalIso(iso: String, offsetMs: Long): Long {
        if (iso.isBlank()) return 0L
        return try {
            val pattern = if (iso.length > 16) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm"
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            (sdf.parse(iso)?.time ?: 0L) - offsetMs
        } catch (e: Exception) {
            0L
        }
    }

    /** "2026-11-26" -> начало локального дня локации в Unix ms. */
    private fun parseLocalDate(date: String, offsetMs: Long): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            (sdf.parse(date)?.time ?: 0L) - offsetMs
        } catch (e: Exception) {
            0L
        }
    }
}
