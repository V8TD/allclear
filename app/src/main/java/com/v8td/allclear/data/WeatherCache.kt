package com.v8td.allclear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кэш погоды в JSON-файлах внутреннего хранилища.
 * Обновлён под новую модель: dewPoint, observationTime, isDay.
 * Формат файла: { "_cache_timestamp": ..., "_cache_data": {...} }
 */
class WeatherCache(private val context: Context) {

    companion object {
        private const val CACHE_DIR = "weather_cache"
        private const val KEY_TIMESTAMP = "_cache_timestamp"
        private const val KEY_DATA = "_cache_data"

        /** Обычный срок годности кэша — 30 минут. */
        const val MAX_CACHE_AGE_MS = 30 * 60 * 1000L

        /** Предельный возраст «запасного» кэша при отсутствии сети — 6 часов. */
        const val STALE_CACHE_AGE_MS = 6 * 60 * 60 * 1000L
    }

    private fun cacheDir(): File =
        File(context.filesDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun cacheFile(providerName: String, lat: Double, lon: Double): File {
        // Округляем до 4 знаков — координаты «дрожат» на метры, кэш не должен плодиться
        val la = BaseWeatherProvider.roundCoord(lat).toString().replace(".", "_").replace("-", "m")
        val lo = BaseWeatherProvider.roundCoord(lon).toString().replace(".", "_").replace("-", "m")
        val safeProvider = providerName.replace(Regex("[^A-Za-z0-9_]"), "_")
        return File(cacheDir(), "cache_${safeProvider}_${la}_${lo}.json")
    }

    suspend fun save(providerName: String, lat: Double, lon: Double, data: WeatherData) {
        withContext(Dispatchers.IO) {
            try {
                val wrapper = JSONObject().apply {
                    put(KEY_TIMESTAMP, System.currentTimeMillis())
                    put(KEY_DATA, toJson(data))
                }
                cacheFile(providerName, lat, lon).writeText(wrapper.toString())
            } catch (e: Exception) {
                // Кэш необязателен — ошибки записи не роняют приложение
            }
        }
    }

    /** Загрузка кэша не старше maxAgeMs. null если нет/просрочен/повреждён. */
    suspend fun load(
        providerName: String,
        lat: Double,
        lon: Double,
        maxAgeMs: Long = MAX_CACHE_AGE_MS
    ): WeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                val file = cacheFile(providerName, lat, lon)
                if (!file.exists()) return@withContext null
                val wrapper = JSONObject(file.readText())
                val age = System.currentTimeMillis() - wrapper.getLong(KEY_TIMESTAMP)
                if (age > maxAgeMs || age < 0) return@withContext null
                fromJson(wrapper.getJSONObject(KEY_DATA))
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Возраст кэша в мс, или null если кэша нет. Для подписи «обновлено N мин назад». */
    suspend fun ageMs(providerName: String, lat: Double, lon: Double): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val file = cacheFile(providerName, lat, lon)
                if (!file.exists()) return@withContext null
                val ts = JSONObject(file.readText()).getLong(KEY_TIMESTAMP)
                System.currentTimeMillis() - ts
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            try { cacheDir().deleteRecursively() } catch (e: Exception) { }
        }
    }

    suspend fun clearProvider(providerName: String) {
        withContext(Dispatchers.IO) {
            try {
                val safe = providerName.replace(Regex("[^A-Za-z0-9_]"), "_")
                cacheDir().listFiles()
                    ?.filter { it.name.startsWith("cache_${safe}_") }
                    ?.forEach { it.delete() }
            } catch (e: Exception) { }
        }
    }

    // --- Сериализация ---

    private fun toJson(d: WeatherData): JSONObject = JSONObject().apply {
        put("temperature", d.temperature)
        put("feelsLike", d.feelsLike)
        put("conditionCode", d.conditionCode)
        put("description", d.description)
        put("humidity", d.humidity)
        put("windSpeed", d.windSpeed)
        put("windGust", d.windGust ?: JSONObject.NULL)
        put("windDirection", d.windDirection)
        put("pressure", d.pressure)
        put("visibility", d.visibility ?: JSONObject.NULL)
        put("uvIndex", d.uvIndex ?: JSONObject.NULL)
        put("precipitationProb", d.precipitationProb ?: JSONObject.NULL)
        put("dewPoint", d.dewPoint ?: JSONObject.NULL)
        put("sunrise", d.sunrise)
        put("sunset", d.sunset)
        put("observationTime", d.observationTime)
        put("dailyForecast", JSONArray().apply {
            d.dailyForecast.forEach { f ->
                put(JSONObject().apply {
                    put("date", f.date)
                    put("tempMin", f.tempMin)
                    put("tempMax", f.tempMax)
                    put("conditionCode", f.conditionCode)
                    put("precipProb", f.precipProb)
                })
            }
        })
        put("hourlyForecast", JSONArray().apply {
            d.hourlyForecast.forEach { f ->
                put(JSONObject().apply {
                    put("time", f.time)
                    put("temp", f.temp)
                    put("conditionCode", f.conditionCode)
                    put("precipProb", f.precipProb)
                    put("isDay", f.isDay)
                })
            }
        })
    }

    private fun fromJson(j: JSONObject): WeatherData {
        val daily = mutableListOf<DailyForecast>()
        j.optJSONArray("dailyForecast")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                daily.add(DailyForecast(
                    date = o.getLong("date"),
                    tempMin = o.getDouble("tempMin"),
                    tempMax = o.getDouble("tempMax"),
                    conditionCode = o.getInt("conditionCode"),
                    precipProb = o.optInt("precipProb", -1)
                ))
            }
        }
        val hourly = mutableListOf<HourlyForecast>()
        j.optJSONArray("hourlyForecast")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                hourly.add(HourlyForecast(
                    time = o.getLong("time"),
                    temp = o.getDouble("temp"),
                    conditionCode = o.getInt("conditionCode"),
                    precipProb = o.optInt("precipProb", -1),
                    isDay = o.optBoolean("isDay", true)
                ))
            }
        }
        return WeatherData(
            temperature = j.getDouble("temperature"),
            feelsLike = j.getDouble("feelsLike"),
            conditionCode = j.getInt("conditionCode"),
            description = j.getString("description"),
            humidity = j.getInt("humidity"),
            windSpeed = j.getDouble("windSpeed"),
            windGust = nullableDouble(j, "windGust"),
            windDirection = j.getInt("windDirection"),
            pressure = j.getDouble("pressure"),
            visibility = nullableDouble(j, "visibility"),
            uvIndex = nullableDouble(j, "uvIndex"),
            precipitationProb = j.optInt("precipitationProb", -1).let { if (it < 0) null else it },
            dewPoint = nullableDouble(j, "dewPoint"),
            sunrise = j.getLong("sunrise"),
            sunset = j.getLong("sunset"),
            observationTime = j.optLong("observationTime", 0L),
            dailyForecast = daily,
            hourlyForecast = hourly
        )
    }

    private fun nullableDouble(j: JSONObject, key: String): Double? {
        if (!j.has(key) || j.isNull(key)) return null
        val v = j.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }
}
