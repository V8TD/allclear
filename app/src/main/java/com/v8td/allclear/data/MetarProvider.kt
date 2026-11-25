package com.v8td.allclear.data

import org.json.JSONObject

/**
 * Провайдер AviationWeather METAR.
 * Только ТЕКУЩАЯ погода (без прогноза).
 * Источник: автометеостанция по ближайшему ICAO-коду или ручной ввод.
 * Лимит: ~100 запросов/мин (публичный API).
 * Не требует ключа.
 */
class MetarProvider : BaseWeatherProvider() {
    
    override val name: String = "METAR"
    
    companion object {
        private const val STATION_URL = "https://aviationweather.gov/api/data/metar"
        private const val NEAREST_URL = "https://aviationweather.gov/api/data/station"
    }
    
    /**
     * Если icao != null, запрашивает конкретную станцию.
     * Иначе ищет ближайшую по координатам.
     */
    suspend fun getWeatherByIcao(icao: String): WeatherData? {
        val url = "$STATION_URL?ids=$icao&format=json"
        val json = fetchJson(url) ?: return null
        val arr = json.optJSONArray("metar") ?: return null
        if (arr.length() == 0) return null
        
        return try {
            parseMetar(arr.getJSONObject(0))
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        // Find nearest station
        val stationUrl = "$NEAREST_URL?format=json&lat=$lat&lon=$lon&limit=1"
        val stationJson = fetchJson(stationUrl) ?: return null
        val stations = stationJson.optJSONArray("stations") ?: return null
        if (stations.length() == 0) return null
        
        val icao = stations.getJSONObject(0).optString("icaoId", "")
        if (icao.isEmpty()) return null
        
        return getWeatherByIcao(icao)
    }
    
    private fun parseMetar(metar: JSONObject): WeatherData {
        val temp = metar.optDouble("temp", Double.NaN)
        val dewpoint = metar.optDouble("dewp", Double.NaN)
        val windSpeed = metar.optDouble("wspd", Double.NaN)
        val windDir = metar.optInt("wdir", 0)
        val windGust = metar.optDouble("wgst", Double.NaN).let {
            if (it.isNaN()) null else it
        }
        val visibility = metar.optDouble("visib", Double.NaN).let {
            if (it.isNaN()) null else it * 1.60934 // miles → km
        }
        val pressure = metar.optDouble("altim", Double.NaN).let {
            if (it.isNaN()) null else it * 33.8639 // inHg → hPa
        }
        val flightCategory = metar.optString("flightCategory", "VFR")
        val wxString = metar.optString("wxString", "")
        
        // Calculate humidity from temp and dewpoint
        val humidity = if (!temp.isNaN() && !dewpoint.isNaN()) {
            val rh = 100.0 * Math.exp((17.625 * dewpoint) / (243.04 + dewpoint)) /
                     Math.exp((17.625 * temp) / (243.04 + temp))
            rh.toInt().coerceIn(0, 100)
        } else 0
        
        // Map METAR conditions to Yahoo code
        val conditionCode = metarWxToYahoo(wxString, flightCategory)
        val description = metarWxToDescription(wxString, flightCategory)
        
        // Feels like approximation (no direct METAR field)
        val feelsLike = if (!temp.isNaN() && !windSpeed.isNaN()) {
            calculateWindChill(temp, windSpeed * 0.514444) // knots → m/s
        } else temp
        
        return WeatherData(
            temperature = if (temp.isNaN()) 0.0 else temp,
            feelsLike = if (feelsLike.isNaN()) 0.0 else feelsLike,
            conditionCode = conditionCode,
            description = description,
            humidity = humidity,
            windSpeed = if (windSpeed.isNaN()) 0.0 else windSpeed * 0.514444, // knots → m/s
            windGust = windGust?.times(0.514444),
            windDirection = windDir,
            pressure = pressure ?: 1013.25,
            visibility = visibility,
            uvIndex = null,
            precipitationProb = null,
            sunrise = 0L, // METAR doesn't provide sunrise/sunset
            sunset = 0L,
            dailyForecast = emptyList(), // No forecast in METAR
            hourlyForecast = emptyList() // No forecast in METAR
        )
    }
    
    private fun calculateWindChill(temp: Double, windSpeedMs: Double): Double {
        if (temp > 10.0 || windSpeedMs < 1.3) return temp
        val wsKmh = windSpeedMs * 3.6
        val wc = 13.12 + 0.6215 * temp - 11.37 * Math.pow(wsKmh, 0.16) +
                 0.3965 * temp * Math.pow(wsKmh, 0.16)
        return wc.coerceAtMost(temp)
    }
    
    /**
     * Парсинг METAR wxString и flightCategory в Yahoo код.
     */
    private fun metarWxToYahoo(wxString: String, flightCategory: String): Int {
        val wx = wxString.uppercase()
        
        return when {
            wx.contains("TS") || wx.contains("VCTS") -> 4   // Thunderstorm
            wx.contains("GR") || wx.contains("GS") -> 4     // Hail
            wx.contains("SN") || wx.contains("SG") -> 16    // Snow
            wx.contains("RA") && wx.contains("SH") -> 11    // Rain showers
            wx.contains("RA") -> 12                         // Rain
            wx.contains("DZ") -> 9                          // Drizzle
            wx.contains("FG") || wx.contains("BR") -> 20    // Fog/Mist
            wx.contains("HZ") || wx.contains("FU") -> 22    // Haze/Smoke
            wx.contains("SQ") -> 4                          // Squall
            flightCategory == "LIFR" -> 26                   // Low IFR → Overcast
            flightCategory == "IFR" -> 26                    // IFR → Cloudy
            flightCategory == "MVFR" -> 30                   // Marginal VFR → Partly cloudy
            flightCategory == "VFR" -> 32                    // VFR → Clear
            else -> 32                                       // Default clear
        }
    }
    
    private fun metarWxToDescription(wxString: String, flightCategory: String): String {
        val wx = wxString.uppercase()
        
        return when {
            wx.contains("TS") -> "Гроза"
            wx.contains("GR") || wx.contains("GS") -> "Град"
            wx.contains("SN") -> "Снег"
            wx.contains("SHRA") -> "Ливень"
            wx.contains("RA") -> "Дождь"
            wx.contains("DZ") -> "Морось"
            wx.contains("FG") -> "Туман"
            wx.contains("BR") -> "Дымка"
            wx.contains("HZ") -> "Мгла"
            wx.contains("FU") -> "Дым"
            wx.contains("SQ") -> "Шквал"
            flightCategory == "LIFR" -> "Низкая облачность"
            flightCategory == "IFR" -> "Облачно"
            flightCategory == "MVFR" -> "Переменная облачность"
            flightCategory == "VFR" -> "Ясно"
            else -> "Нет данных"
        }
    }
}
