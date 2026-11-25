package com.v8td.allclear.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Провайдер MET Norway (Yr.no).
 * Бесплатный, требует User-Agent с контактом.
 * ВАЖНО: По правилам от 05.11.2026 запрещено использовать в фоновых обновлениях.
 * Использовать ТОЛЬКО при открытии приложения или тапе пользователя.
 * В WorkManager всегда использовать OpenMeteoProvider.
 * Атрибуция: CC BY 4.0
 */
class MetNorwayProvider : BaseWeatherProvider() {
    
    override val name: String = "MET Norway"
    
    companion object {
        const val USER_AGENT = "AllClear/1.0 (github.com/V8TD/allclear)"
        private const val BASE_URL = "https://api.met.no/weatherapi/locationforecast/2.0/complete"
    }
    
    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val url = "$BASE_URL?lat=$lat&lon=$lon"
        val headers = mapOf("User-Agent" to USER_AGENT)
        val json = fetchJson(url, headers) ?: return null
        
        return try {
            parseResponse(json)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseResponse(json: JSONObject): WeatherData {
        val properties = json.getJSONObject("properties")
        val timeseries = properties.getJSONArray("timeseries")
        
        if (timeseries.length() == 0) return throw IllegalStateException("Empty timeseries")
        
        // Current weather = first entry
        val currentEntry = timeseries.getJSONObject(0)
        val currentTime = currentEntry.getString("time")
        val currentData = currentEntry.getJSONObject("data")
        val instant = currentData.getJSONObject("instant")
        val details = instant.getJSONObject("details")
        
        val temperature = details.getDouble("air_temperature")
        val humidity = details.getInt("relative_humidity")
        val windSpeed = details.getDouble("wind_speed")
        val windDirection = details.getInt("wind_from_direction")
        val pressure = details.getDouble("air_pressure_at_sea_level")
        
        // Next 1 hour forecast for condition code and precipitation
        val next1h = currentData.optJSONObject("next_1_hours")
        val summary = next1h?.optJSONObject("summary")
        val symbolCode = summary?.optString("symbol_code", "unknown") ?: "unknown"
        val precipAmount = next1h?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
        
        val conditionCode = metSymbolToYahoo(symbolCode)
        val description = metSymbolToDescription(symbolCode)
        
        // Apparent temperature approximation (MET Norway doesn't always provide it directly)
        val feelsLike = calculateFeelsLike(temperature, windSpeed, humidity)
        
        // Sunrise/sunset from separate endpoint would be needed, 
        // but locationforecast doesn't include them. Set to 0 for now.
        // TODO: Интегрировать sunriset endpoint позже
        
        // Parse hourly forecast (next 24 entries)
        val hourlyList = mutableListOf<HourlyForecast>()
        for (i in 1 until minOf(timeseries.length(), 25)) {
            val entry = timeseries.getJSONObject(i)
            val entryData = entry.getJSONObject("data")
            val entryInstant = entryData.getJSONObject("instant")
            val entryDetails = entryInstant.getJSONObject("details")
            
            val entryNext1h = entryData.optJSONObject("next_1_hours")
            val entrySummary = entryNext1h?.optJSONObject("summary")
            val entrySymbol = entrySummary?.optString("symbol_code", "unknown") ?: "unknown"
            val entryPrecip = entryNext1h?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
            
            hourlyList.add(
                HourlyForecast(
                    time = parseIsoTime(entry.getString("time")),
                    temp = entryDetails.getDouble("air_temperature"),
                    conditionCode = metSymbolToYahoo(entrySymbol),
                    precipProb = if (entryPrecip > 0) 100 else 0 // MET gives amount, not probability
                )
            )
        }
        
        // Daily forecast aggregation (group by date)
        val dailyMap = mutableMapOf<String, MutableList<Pair<Double, Int>>>()
        for (i in 1 until timeseries.length()) {
            val entry = timeseries.getJSONObject(i)
            val dateStr = entry.getString("time").substring(0, 10)
            val entryTemp = entry.getJSONObject("data")
                .getJSONObject("instant")
                .getJSONObject("details")
                .getDouble("air_temperature")
            val entrySymbol = entry.getJSONObject("data")
                .optJSONObject("next_1_hours")
                ?.optJSONObject("summary")
                ?.optString("symbol_code", "unknown") ?: "unknown"
            
            dailyMap.getOrPut(dateStr) { mutableListOf() }.add(Pair(entryTemp, metSymbolToYahoo(entrySymbol)))
        }
        
        val dailyList = dailyMap.entries.take(7).map { (dateStr, entries) ->
            val temps = entries.map { it.first }
            val codes = entries.map { it.second }
            // Most frequent condition code as daily representative
            val dominantCode = codes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 32
            
            DailyForecast(
                date = parseDateOnly(dateStr),
                tempMin = temps.min(),
                tempMax = temps.max(),
                conditionCode = dominantCode,
                precipProb = 0 // Aggregated probability not available directly
            )
        }
        
        return WeatherData(
            temperature = temperature,
            feelsLike = feelsLike,
            conditionCode = conditionCode,
            description = description,
            humidity = humidity,
            windSpeed = windSpeed,
            windGust = null, // Not always available in instant details
            windDirection = windDirection,
            pressure = pressure,
            visibility = null,
            uvIndex = null,
            precipitationProb = if (precipAmount > 0) 100 else 0,
            sunrise = 0L, // TODO: Sunriset integration
            sunset = 0L,  // TODO: Sunriset integration
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }
    
    /**
     * Упрощённый расчёт "ощущается как" по формуле MET Norway.
     */
    private fun calculateFeelsLike(temp: Double, windSpeed: Double, humidity: Int): Double {
        // Wind chill for cold temperatures
        if (temp <= 10.0 && windSpeed > 1.3) {
            val wc = 13.12 + 0.6215 * temp - 11.37 * Math.pow(windSpeed * 3.6, 0.16) +
                     0.3965 * temp * Math.pow(windSpeed * 3.6, 0.16)
            return wc.coerceAtMost(temp)
        }
        // Heat index approximation for warm temperatures
        if (temp >= 27.0) {
            val hi = temp + 0.33 * (humidity / 100.0 * 6.105 * Math.exp(17.27 * temp / (237.7 + temp))) - 4.0
            return hi.coerceAtLeast(temp)
        }
        return temp
    }
    
    /**
     * Маппинг MET Norway symbol_code в Yahoo/Chronus коды.
     * Symbol codes: https://api.met.no/weatherapi/weathericon/2.0/documentation
     */
    private fun metSymbolToYahoo(symbol: String): Int {
        // Strip _day/_night suffix for base code
        val base = symbol.replace("_day", "").replace("_night", "")
        val isNight = symbol.contains("_night")
        
        return when (base) {
            "clearsky" -> if (isNight) 31 else 32
            "fair" -> if (isNight) 33 else 34
            "partlycloudy" -> if (isNight) 29 else 30
            "cloudy" -> 26
            "rainshowers" -> 11
            "rainshowersandthunder" -> 4
            "sleetshowers" -> 6
            "snowshowers" -> 14
            "rain" -> 12
            "heavyrain" -> 12
            "rainandthunder" -> 4
            "sleet" -> 6
            "snow" -> 16
            "snowandthunder" -> 4
            "fog" -> 20
            "sleetshowersandthunder" -> 4
            "heavysnow" -> 16
            "heavysnowandthunder" -> 4
            "lightrainshowers" -> 9
            "lightrain" -> 9
            "lightsleet" -> 6
            "lightsnow" -> 13
            "lightsnowandthunder" -> 4
            else -> 32 // Fallback
        }
    }
    
    private fun metSymbolToDescription(symbol: String): String {
        val base = symbol.replace("_day", "").replace("_night", "")
        return when (base) {
            "clearsky" -> "Ясно"
            "fair" -> "Малооблачно"
            "partlycloudy" -> "Переменная облачность"
            "cloudy" -> "Пасмурно"
            "rainshowers", "lightrainshowers" -> "Ливень"
            "rain", "lightrain" -> "Дождь"
            "heavyrain" -> "Сильный дождь"
            "rainandthunder", "rainshowersandthunder" -> "Гроза"
            "sleet", "lightsleet", "sleetshowers" -> "Мокрый снег"
            "snow", "lightsnow" -> "Снег"
            "heavysnow" -> "Сильный снег"
            "snowshowers" -> "Снегопад"
            "fog" -> "Туман"
            "snowandthunder", "heavysnowandthunder", "lightsnowandthunder",
            "sleetshowersandthunder" -> "Гроза со снегом"
            else -> "Нет данных"
        }
    }
    
    private fun parseIsoTime(iso: String): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.parse(iso)?.time ?: 0L
    }
    
    private fun parseDateOnly(date: String): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.parse(date)?.time ?: 0L
    }
}
