package com.v8td.allclear.data

import org.json.JSONObject

/**
 * Провайдер OpenWeatherMap (API 2.5, бесплатный ключ без карты).
 * Требует API-ключ. One Call 3.0 НЕ используем (требует привязку карты).
 * Используем отдельные endpoints: /weather (current) + /forecast (5-day/3-hour).
 * Ключ передаётся через параметр appid.
 */
class OpenWeatherMapProvider(private val apiKey: String) : BaseWeatherProvider() {
    
    override val name: String = "OpenWeatherMap"
    
    companion object {
        private const val CURRENT_URL = "https://api.openweathermap.org/data/2.5/weather"
        private const val FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast"
    }
    
    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val currentJson = fetchCurrent(lat, lon) ?: return null
        val forecastJson = fetchForecast(lat, lon) ?: return null
        
        return try {
            mergeResponses(currentJson, forecastJson)
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun fetchCurrent(lat: Double, lon: Double): JSONObject? {
        val url = "$CURRENT_URL?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=ru"
        return fetchJson(url)
    }
    
    private suspend fun fetchForecast(lat: Double, lon: Double): JSONObject? {
        val url = "$FORECAST_URL?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=ru&cnt=40"
        return fetchJson(url)
    }
    
    private fun mergeResponses(current: JSONObject, forecast: JSONObject): WeatherData {
        // Current weather
        val main = current.getJSONObject("main")
        val wind = current.getJSONObject("wind")
        val weatherArr = current.getJSONArray("weather")
        val sys = current.getJSONObject("sys")
        
        val temperature = main.getDouble("temp")
        val feelsLike = main.getDouble("feels_like")
        val humidity = main.getInt("humidity")
        val pressure = main.getDouble("pressure")
        val visibility = current.optDouble("visibility", Double.NaN).let {
            if (it.isNaN()) null else it / 1000.0 // meters → km
        }
        
        val owmId = if (weatherArr.length() > 0) weatherArr.getJSONObject(0).getInt("id") else 800
        val description = if (weatherArr.length() > 0) weatherArr.getJSONObject(0).getString("description") else "Нет данных"
        val conditionCode = owmIdToYahoo(owmId)
        
        val windSpeed = wind.getDouble("speed")
        val windGust = wind.optDouble("gust", Double.NaN).let {
            if (it.isNaN()) null else it
        }
        val windDirection = wind.optInt("deg", 0)
        
        val sunrise = sys.getLong("sunrise") * 1000
        val sunset = sys.getLong("sunset") * 1000
        
        // Forecast parsing
        val list = forecast.getJSONArray("list")
        
        // Hourly: take first 24 entries (3-hour intervals → 8 per day, 24 = 3 days)
        val hourlyList = mutableListOf<HourlyForecast>()
        for (i in 0 until minOf(list.length(), 24)) {
            val item = list.getJSONObject(i)
            val itemMain = item.getJSONObject("main")
            val itemWeather = item.getJSONArray("weather")
            val itemId = if (itemWeather.length() > 0) itemWeather.getJSONObject(0).getInt("id") else 800
            val pop = item.optDouble("pop", 0.0)
            
            hourlyList.add(
                HourlyForecast(
                    time = item.getLong("dt") * 1000,
                    temp = itemMain.getDouble("temp"),
                    conditionCode = owmIdToYahoo(itemId),
                    precipProb = (pop * 100).toInt()
                )
            )
        }
        
        // Daily: aggregate 3-hour forecasts into daily
        val dailyMap = mutableMapOf<Long, MutableList<Triple<Double, Int, Double>>>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val dt = item.getLong("dt") * 1000
            val dayStart = (dt / 86400000L) * 86400000L // Normalize to day start UTC
            val itemMain = item.getJSONObject("main")
            val itemWeather = item.getJSONArray("weather")
            val itemId = if (itemWeather.length() > 0) itemWeather.getJSONObject(0).getInt("id") else 800
            val pop = item.optDouble("pop", 0.0)
            
            dailyMap.getOrPut(dayStart) { mutableListOf() }
                .add(Triple(itemMain.getDouble("temp"), itemId, pop))
        }
        
        val dailyList = dailyMap.entries.take(7).map { (dayStart, entries) ->
            val temps = entries.map { it.first }
            val codes = entries.map { it.second }
            val pops = entries.map { it.third }
            val dominantCode = codes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 32
            val maxPop = pops.maxOrNull()?.let { (it * 100).toInt() } ?: 0
            
            DailyForecast(
                date = dayStart,
                tempMin = temps.min(),
                tempMax = temps.max(),
                conditionCode = dominantCode,
                precipProb = maxPop
            )
        }
        
        return WeatherData(
            temperature = temperature,
            feelsLike = feelsLike,
            conditionCode = conditionCode,
            description = description.replaceFirstChar { it.uppercase() },
            humidity = humidity,
            windSpeed = windSpeed,
            windGust = windGust,
            windDirection = windDirection,
            pressure = pressure,
            visibility = visibility,
            uvIndex = null, // Not available in free 2.5 API
            precipitationProb = if (list.length() > 0) {
                (list.getJSONObject(0).optDouble("pop", 0.0) * 100).toInt()
            } else null,
            sunrise = sunrise,
            sunset = sunset,
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }
    
    /**
     * Маппинг OWM weather condition IDs в Yahoo/Chronus коды.
     * https://openweathermap.org/weather-conditions
     */
    private fun owmIdToYahoo(id: Int): Int {
        return when {
            id >= 200 && id < 300 -> 4    // Thunderstorm
            id >= 300 && id < 400 -> 9    // Drizzle
            id >= 500 && id < 510 -> 12   // Rain
            id == 511 -> 8                // Freezing rain
            id >= 520 && id < 600 -> 11   // Shower rain
            id >= 600 && id < 700 -> 16   // Snow
            id >= 700 && id < 800 -> 20   // Atmosphere (fog, mist, etc.)
            id == 800 -> 32               // Clear sky
            id == 801 -> 34               // Few clouds
            id == 802 -> 30               // Scattered clouds
            id == 803 -> 28               // Broken clouds
            id == 804 -> 26               // Overcast
            else -> 32                    // Fallback
        }
    }
}
