package com.v8td.allclear.data

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Провайдер Open-Meteo.
 * Основной бесплатный источник, не требует API-ключа.
 * Атрибуция: CC BY 4.0 (обязательно в экране "О приложении").
 * Документация: https://open-meteo.com/en/docs
 */
class OpenMeteoProvider : BaseWeatherProvider() {
    
    override val name: String = "Open-Meteo"
    
    private companion object {
        const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        // WMO Weather interpretation codes → Yahoo/Chronus codes mapping
        // Full mapping table below in mapWmoToYahoo()
    }
    
    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val url = buildUrl(lat, lon)
        val json = fetchJson(url) ?: return null
        
        return try {
            parseResponse(json)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun buildUrl(lat: Double, lon: Double): String {
        return "$BASE_URL?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m," +
            "surface_pressure,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,sunrise,sunset,uv_index_max" +
            "&timezone=auto" +
            "&forecast_days=7&forecast_hours=24" +
            "&wind_speed_unit=ms&precipitation_unit=mm"
    }
    
    private fun parseResponse(json: JSONObject): WeatherData {
        val current = json.getJSONObject("current")
        val hourly = json.getJSONObject("hourly")
        val daily = json.getJSONObject("daily")
        
        val wmoCode = current.getInt("weather_code")
        val isDay = current.optInt("is_day", 1) == 1
        
        val temperature = current.getDouble("temperature_2m")
        val feelsLike = current.getDouble("apparent_temperature")
        val conditionCode = mapWmoToYahoo(wmoCode, isDay)
        val description = wmoToDescription(wmoCode)
        val humidity = current.getInt("relative_humidity_2m")
        val windSpeed = current.getDouble("wind_speed_10m")
        val windGust = current.optDouble("wind_gusts_10m", Double.NaN).let {
            if (it.isNaN()) null else it
        }
        val windDirection = current.getInt("wind_direction_10m")
        val pressure = current.getDouble("surface_pressure")
        
        // UV index from daily (today's max)
        val uvIndex = daily.optJSONArray("uv_index_max")?.optDouble(0, Double.NaN)?.let {
            if (it.isNaN()) null else it
        }
        
        // Sunrise/sunset for today
        val sunriseStr = daily.getJSONArray("sunrise").getString(0)
        val sunsetStr = daily.getJSONArray("sunset").getString(0)
        val sunrise = parseIsoToTimestamp(sunriseStr)
        val sunset = parseIsoToTimestamp(sunsetStr)
        
        // Hourly forecast (next 24 hours)
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")
        val hourlyCodes = hourly.getJSONArray("weather_code")
        val hourlyPrecip = hourly.getJSONArray("precipitation_probability")
        
        val hourlyList = mutableListOf<HourlyForecast>()
        val nowMs = System.currentTimeMillis()
        for (i in 0 until hourlyTimes.length()) {
            val timeMs = parseIsoToTimestamp(hourlyTimes.getString(i))
            if (timeMs >= nowMs && hourlyList.size < 24) {
                hourlyList.add(
                    HourlyForecast(
                        time = timeMs,
                        temp = hourlyTemps.getDouble(i),
                        conditionCode = mapWmoToYahoo(hourlyCodes.getInt(i), true),
                        precipProb = hourlyPrecip.optInt(i, 0)
                    )
                )
            }
        }
        
        // Daily forecast (7 days)
        val dailyDates = daily.getJSONArray("time")
        val dailyMax = daily.getJSONArray("temperature_2m_max")
        val dailyMin = daily.getJSONArray("temperature_2m_min")
        val dailyCodes = daily.getJSONArray("weather_code")
        val dailyPrecip = daily.getJSONArray("precipitation_probability_max")
        
        val dailyList = mutableListOf<DailyForecast>()
        for (i in 0 until dailyDates.length()) {
            dailyList.add(
                DailyForecast(
                    date = parseDateToTimestamp(dailyDates.getString(i)),
                    tempMin = dailyMin.getDouble(i),
                    tempMax = dailyMax.getDouble(i),
                    conditionCode = mapWmoToYahoo(dailyCodes.getInt(i), true),
                    precipProb = dailyPrecip.optInt(i, 0)
                )
            )
        }
        
        return WeatherData(
            temperature = temperature,
            feelsLike = feelsLike,
            conditionCode = conditionCode,
            description = description,
            humidity = humidity,
            windSpeed = windSpeed,
            windGust = windGust,
            windDirection = windDirection,
            pressure = pressure,
            visibility = null, // Open-Meteo doesn't provide visibility in free tier
            uvIndex = uvIndex,
            precipitationProb = hourlyPrecip.optInt(0, 0),
            sunrise = sunrise,
            sunset = sunset,
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }
    
    /**
     * Маппинг WMO кодов Open-Meteo в Yahoo/Chronus коды (0-47).
     * isDay учитывается для различия день/ночь где применимо.
     */
    private fun mapWmoToYahoo(wmo: Int, isDay: Boolean): Int {
        return when (wmo) {
            0 -> if (isDay) 32 else 31       // Clear sky → Sunny / Clear night
            1 -> if (isDay) 34 else 33       // Mainly clear → Fair day / Fair night
            2 -> if (isDay) 30 else 29       // Partly cloudy → Partly cloudy day/night
            3 -> 26                           // Overcast → Cloudy
            45, 48 -> 20                      // Fog → Foggy
            51, 53, 55 -> 9                   // Drizzle → Showers
            56, 57 -> 8                       // Freezing drizzle → Freezing rain
            61 -> 11                          // Slight rain → Showers
            63 -> 12                          // Moderate rain → Rain
            65 -> 12                          // Heavy rain → Rain
            66, 67 -> 8                       // Freezing rain → Freezing rain
            71 -> 14                          // Slight snow → Snow showers
            73 -> 16                          // Moderate snow → Snow
            75 -> 16                          // Heavy snow → Snow
            77 -> 13                          // Snow grains → Flurries
            80, 81, 82 -> 11                  // Rain showers → Showers
            85, 86 -> 14                      // Snow showers → Snow showers
            95 -> 4                           // Thunderstorm → Thunderstorms
            96, 99 -> 4                       // Thunderstorm with hail → Thunderstorms
            else -> 32                        // Fallback → Sunny
        }
    }
    
    private fun wmoToDescription(wmo: Int): String {
        return when (wmo) {
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
            80, 81, 82 -> "Ливень"
            85, 86 -> "Снегопад"
            95 -> "Гроза"
            96, 99 -> "Гроза с градом"
            else -> "Нет данных"
        }
    }
    
    /**
     * Парсинг ISO 8601 datetime строки (например "2026-11-26T14:00") в Unix timestamp ms.
     */
    private fun parseIsoToTimestamp(iso: String): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        // Format: YYYY-MM-DDTHH:MM or YYYY-MM-DDTHH:MM+HH:MM
        val parts = iso.replace("T", " ").split("+", "-").first()
        val dateTimeParts = parts.trim().split(" ")
        val dateParts = dateTimeParts[0].split("-")
        val timeParts = if (dateTimeParts.size > 1) dateTimeParts[1].split(":") else listOf("0", "0")
        
        cal.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1,
            dateParts[2].toInt(),
            timeParts.getOrNull(0)?.toInt() ?: 0,
            timeParts.getOrNull(1)?.toInt() ?: 0,
            0
        )
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    
    /**
     * Парсинг даты YYYY-MM-DD в Unix timestamp ms (начало дня).
     */
    private fun parseDateToTimestamp(date: String): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val parts = date.split("-")
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
