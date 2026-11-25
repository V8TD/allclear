package com.v8td.allclear.data

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.pow

/**
 * Провайдер AviationWeather METAR — только ТЕКУЩАЯ погода, без прогноза.
 * Ответ API — JSON-массив (не объект!). Лимит 100 запросов/мин.
 * Поиск ближайшей станции: /api/data/stationinfo?bbox=... (координаты — 3 знака).
 *
 * Исправлено в ревизии:
 * - Ответ парсится как массив.
 * - Правильный эндпоинт stationinfo + bbox вместо выдуманного station?lat&lon.
 * - Давление altim приходит уже в гПа — конвертация убрана.
 * - Видимость бывает строкой "6+" — парсится вручную.
 * - Категория называется fltCat (не flightCategory).
 * - Направление ветра бывает "VRB" — обработано.
 */
class MetarProvider : BaseWeatherProvider() {

    override val name: String = "METAR"

    companion object {
        private const val METAR_URL = "https://aviationweather.gov/api/data/metar"
        private const val STATION_URL = "https://aviationweather.gov/api/data/stationinfo"
        private const val UA = "AllClear/1.0 github.com/V8TD/allclear"
    }

    /** Запрос по конкретному ICAO-коду (ручной ввод в настройках). */
    suspend fun getWeatherByIcao(icao: String): WeatherData? {
        val code = icao.trim().uppercase()
        if (code.length != 4) return null
        val url = "$METAR_URL?ids=$code&format=json&hours=2"
        val arr = fetchJsonArray(url, mapOf("User-Agent" to UA)) ?: return null
        if (arr.length() == 0) return null
        return try {
            parseMetar(arr.getJSONObject(0))
        } catch (e: Exception) {
            null
        }
    }

    /** Автопоиск ближайшей станции по координатам. */
    override suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        val icao = findNearestStation(lat, lon) ?: return null
        return getWeatherByIcao(icao)
    }

    /**
     * Ищем станции в расширяющемся квадрате вокруг точки (примерно 50 → 100 → 200 км),
     * выбираем ближайшую из тех, что передают METAR.
     */
    private suspend fun findNearestStation(lat: Double, lon: Double): String? {
        for (radiusDeg in doubleArrayOf(0.5, 1.0, 2.0)) {
            val lat0 = fmt3(lat - radiusDeg)
            val lat1 = fmt3(lat + radiusDeg)
            // Поправка на сжатие меридианов к полюсам
            val lonDelta = radiusDeg / kotlin.math.maxOf(0.2, kotlin.math.cos(Math.toRadians(lat)))
            val lon0 = fmt3(lon - lonDelta)
            val lon1 = fmt3(lon + lonDelta)

            val url = "$STATION_URL?bbox=$lat0,$lon0,$lat1,$lon1&format=json"
            val arr = fetchJsonArray(url, mapOf("User-Agent" to UA)) ?: continue
            if (arr.length() == 0) continue

            var bestIcao: String? = null
            var bestDist = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val st = arr.getJSONObject(i)
                val types = st.optJSONArray("siteType") ?: continue
                var hasMetar = false
                for (j in 0 until types.length()) {
                    if (types.optString(j) == "METAR") { hasMetar = true; break }
                }
                if (!hasMetar) continue
                val sLat = st.optDouble("lat", Double.NaN)
                val sLon = st.optDouble("lon", Double.NaN)
                if (sLat.isNaN() || sLon.isNaN()) continue
                val dLat = sLat - lat
                val dLon = (sLon - lon) * kotlin.math.cos(Math.toRadians(lat))
                val dist = dLat * dLat + dLon * dLon
                if (dist < bestDist) {
                    bestDist = dist
                    bestIcao = st.optString("icaoId", "").ifEmpty { null }
                }
            }
            if (bestIcao != null) return bestIcao
        }
        return null
    }

    /** AviationWeather требует максимум 3 знака в bbox. */
    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun parseMetar(m: JSONObject): WeatherData {
        val temp = m.optDoubleOrNull("temp") ?: return throw IllegalStateException("no temp")
        val dewp = m.optDoubleOrNull("dewp")

        // Ветер: узлы -> м/с. wdir может быть числом, строкой "VRB" или null.
        val wspdKnots = m.optDoubleOrNull("wspd") ?: 0.0
        val windSpeed = wspdKnots * 0.514444
        val gustKnots = m.optDoubleOrNull("wgst")
        val windGust = gustKnots?.times(0.514444)
        val windDir = when (val v = m.opt("wdir")) {
            is Number -> v.toInt()
            else -> 0 // "VRB" (переменный) или нет данных
        }

        // Видимость: мили, бывает строка "6+" -> км
        val visibility = m.optDoubleOrNull("visib")?.times(1.60934)

        // Давление: altim УЖЕ в гПа. Если есть slp (уровень моря) — предпочитаем его.
        val pressure = m.optDoubleOrNull("slp") ?: m.optDoubleOrNull("altim") ?: 0.0

        val wxString = m.optString("wxString", "")
        val fltCat = m.optString("fltCat", "")
        val clouds = m.optJSONArray("clouds")

        // Влажность из температуры и точки росы (формула Магнуса)
        val humidity = if (dewp != null) {
            val rh = 100.0 * exp((17.625 * dewp) / (243.04 + dewp)) /
                exp((17.625 * temp) / (243.04 + temp))
            rh.toInt().coerceIn(0, 100)
        } else -1

        val feelsLike = windChill(temp, windSpeed)

        return WeatherData(
            temperature = temp,
            feelsLike = feelsLike,
            conditionCode = metarToYahoo(wxString, fltCat, clouds),
            description = metarToDescription(wxString, fltCat, clouds),
            humidity = humidity.coerceAtLeast(0),
            windSpeed = windSpeed,
            windGust = windGust,
            windDirection = windDir,
            pressure = pressure,
            visibility = visibility,
            uvIndex = null,
            precipitationProb = null,
            dewPoint = dewp,
            sunrise = 0L,
            sunset = 0L,
            observationTime = m.optLong("obsTime", 0L) * 1000L,
            dailyForecast = emptyList(),
            hourlyForecast = emptyList()
        )
    }

    private fun windChill(temp: Double, windMs: Double): Double {
        if (temp > 10.0 || windMs < 1.34) return temp
        val v = (windMs * 3.6).pow(0.16)
        val wc = 13.12 + 0.6215 * temp - 11.37 * v + 0.3965 * temp * v
        return if (wc < temp) wc else temp
    }

    /** Максимальная облачность из массива слоёв (CLR/FEW/SCT/BKN/OVC). */
    private fun maxCloudCover(clouds: org.json.JSONArray?): String {
        if (clouds == null || clouds.length() == 0) return ""
        var best = ""
        var bestRank = -1
        val rank = mapOf("CLR" to 0, "CAVOK" to 0, "FEW" to 1, "SCT" to 2, "BKN" to 3, "OVC" to 4, "OVX" to 4)
        for (i in 0 until clouds.length()) {
            val cover = clouds.getJSONObject(i).optString("cover", "")
            val r = rank[cover] ?: -1
            if (r > bestRank) { bestRank = r; best = cover }
        }
        return best
    }

    private fun metarToYahoo(wx: String, fltCat: String, clouds: org.json.JSONArray?): Int {
        val w = wx.uppercase()
        return when {
            w.contains("TS") -> 4
            w.contains("GR") -> 17
            w.contains("GS") -> 18
            w.contains("FZRA") || w.contains("FZDZ") -> 10
            w.contains("SN") && (w.contains("RA") || w.contains("DZ")) -> 5
            w.contains("BLSN") -> 15
            w.contains("SHSN") -> 14
            w.contains("SN") || w.contains("SG") -> 16
            w.contains("SHRA") -> 11
            w.contains("RA") -> 12
            w.contains("DZ") -> 9
            w.contains("FG") -> 20
            w.contains("BR") -> 21
            w.contains("HZ") -> 21
            w.contains("FU") -> 22
            w.contains("DU") || w.contains("SA") -> 19
            w.contains("SQ") -> 23
            else -> when (maxCloudCover(clouds)) {
                "OVC", "OVX" -> 26
                "BKN" -> 28
                "SCT" -> 30
                "FEW" -> 34
                "CLR", "CAVOK" -> 32
                else -> when (fltCat) {
                    "VFR" -> 32
                    "MVFR" -> 30
                    "IFR", "LIFR" -> 26
                    else -> 3200
                }
            }
        }
    }

    private fun metarToDescription(wx: String, fltCat: String, clouds: org.json.JSONArray?): String {
        val w = wx.uppercase()
        return when {
            w.contains("TS") -> "Гроза"
            w.contains("GR") -> "Град"
            w.contains("GS") -> "Снежная крупа"
            w.contains("FZRA") || w.contains("FZDZ") -> "Ледяной дождь"
            w.contains("SN") && (w.contains("RA") || w.contains("DZ")) -> "Дождь со снегом"
            w.contains("BLSN") -> "Позёмка"
            w.contains("SHSN") -> "Снегопад"
            w.contains("SN") -> "Снег"
            w.contains("SG") -> "Снежная крупа"
            w.contains("SHRA") -> "Ливень"
            w.contains("-RA") -> "Небольшой дождь"
            w.contains("RA") -> "Дождь"
            w.contains("DZ") -> "Морось"
            w.contains("FG") -> "Туман"
            w.contains("BR") -> "Дымка"
            w.contains("HZ") -> "Мгла"
            w.contains("FU") -> "Дым"
            w.contains("DU") || w.contains("SA") -> "Пыль"
            w.contains("SQ") -> "Шквал"
            else -> when (maxCloudCover(clouds)) {
                "OVC", "OVX" -> "Пасмурно"
                "BKN" -> "Облачно с прояснениями"
                "SCT" -> "Переменная облачность"
                "FEW" -> "Малооблачно"
                "CLR", "CAVOK" -> "Ясно"
                else -> "Нет данных"
            }
        }
    }
}
