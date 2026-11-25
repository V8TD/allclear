package com.v8td.allclear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Кэширование ответов погоды в JSON-файлы.
 * Файлы хранятся во внутреннем хранилище приложения.
 * Имя файла: cache_<provider>_<lat>_<lon>.json
 * Метаданные (timestamp) сохраняются внутри JSON.
 */
class WeatherCache(private val context: Context) {

    companion object {
        private const val CACHE_DIR = "weather_cache"
        private const val KEY_TIMESTAMP = "_cache_timestamp"
        private const val KEY_DATA = "_cache_data"
        // Максимальный возраст кэша: 30 минут (для фоновых обновлений)
        const val MAX_CACHE_AGE_MS = 30 * 60 * 1000L
    }

    private fun getCacheDir(): File {
        return File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    }

    private fun getCacheFile(providerName: String, lat: Double, lon: Double): File {
        val safeLat = lat.toString().replace(".", "_")
        val safeLon = lon.toString().replace(".", "_")
        val fileName = "cache_${providerName}_${safeLat}_${safeLon}.json"
        return File(getCacheDir(), fileName)
    }

    /**
     * Сохранить данные в кэш.
     * Оборачивает оригинальный JSON в объект с метаданными.
     */
    suspend fun save(
        providerName: String,
        lat: Double,
        lon: Double,
        weatherData: WeatherData
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = getCacheFile(providerName, lat, lon)
                val wrapper = JSONObject().apply {
                    put(KEY_TIMESTAMP, System.currentTimeMillis())
                    put(KEY_DATA, weatherDataToJson(weatherData))
                }
                file.writeText(wrapper.toString())
            } catch (e: IOException) {
                // Тихо игнорируем ошибки записи — кэш необязателен
            }
        }
    }

    /**
     * Загрузить данные из кэша.
     * Возвращает null если кэш отсутствует, повреждён или устарел.
     * @param maxAgeMs максимальный возраст кэша, по умолчанию MAX_CACHE_AGE_MS
     */
    suspend fun load(
        providerName: String,
        lat: Double,
        lon: Double,
        maxAgeMs: Long = MAX_CACHE_AGE_MS
    ): WeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                val file = getCacheFile(providerName, lat, lon)
                if (!file.exists()) return@withContext null

                val content = file.readText()
                val wrapper = JSONObject(content)
                val timestamp = wrapper.getLong(KEY_TIMESTAMP)
                val age = System.currentTimeMillis() - timestamp

                if (age > maxAgeMs) return@withContext null

                val dataJson = wrapper.getJSONObject(KEY_DATA)
                jsonToWeatherData(dataJson)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Проверить наличие актуального кэша без парсинга данных.
     */
    suspend fun hasValidCache(
        providerName: String,
        lat: Double,
        lon: Double,
        maxAgeMs: Long = MAX_CACHE_AGE_MS
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = getCacheFile(providerName, lat, lon)
                if (!file.exists()) return@withContext false
                val content = file.readText()
                val timestamp = JSONObject(content).getLong(KEY_TIMESTAMP)
                (System.currentTimeMillis() - timestamp) <= maxAgeMs
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Очистить весь кэш.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            getCacheDir().deleteRecursively()
        }
    }

    /**
     * Очистить кэш конкретного провайдера.
     */
    suspend fun clearProvider(providerName: String) {
        withContext(Dispatchers.IO) {
            getCacheDir().listFiles()?.filter {
                it.name.startsWith("cache_${providerName}_")
            }?.forEach { it.delete() }
        }
    }

    // --- Сериализация WeatherData в JSON ---

    private fun weatherDataToJson(data: WeatherData): JSONObject {
        return JSONObject().apply {
            put("temperature", data.temperature)
            put("feelsLike", data.feelsLike)
            put("conditionCode", data.conditionCode)
            put("description", data.description)
            put("humidity", data.humidity)
            put("windSpeed", data.windSpeed)
            put("windGust", data.windGust ?: JSONObject.NULL)
            put("windDirection", data.windDirection)
            put("pressure", data.pressure)
            put("visibility", data.visibility ?: JSONObject.NULL)
            put("uvIndex", data.uvIndex ?: JSONObject.NULL)
            put("precipitationProb", data.precipitationProb ?: JSONObject.NULL)
            put("sunrise", data.sunrise)
            put("sunset", data.sunset)

            val dailyArr = org.json.JSONArray()
            data.dailyForecast.forEach { d ->
                dailyArr.put(JSONObject().apply {
                    put("date", d.date)
                    put("tempMin", d.tempMin)
                    put("tempMax", d.tempMax)
                    put("conditionCode", d.conditionCode)
                    put("precipProb", d.precipProb)
                })
            }
            put("dailyForecast", dailyArr)

            val hourlyArr = org.json.JSONArray()
            data.hourlyForecast.forEach { h ->
                hourlyArr.put(JSONObject().apply {
                    put("time", h.time)
                    put("temp", h.temp)
                    put("conditionCode", h.conditionCode)
                    put("precipProb", h.precipProb)
                })
            }
            put("hourlyForecast", hourlyArr)
        }
    }

    // --- Десериализация JSON в WeatherData ---

    private fun jsonToWeatherData(json: JSONObject): WeatherData {
        val dailyList = mutableListOf<DailyForecast>()
        val dailyArr = json.getJSONArray("dailyForecast")
        for (i in 0 until dailyArr.length()) {
            val d = dailyArr.getJSONObject(i)
            dailyList.add(DailyForecast(
                date = d.getLong("date"),
                tempMin = d.getDouble("tempMin"),
                tempMax = d.getDouble("tempMax"),
                conditionCode = d.getInt("conditionCode"),
                precipProb = d.getInt("precipProb")
            ))
        }

        val hourlyList = mutableListOf<HourlyForecast>()
        val hourlyArr = json.getJSONArray("hourlyForecast")
        for (i in 0 until hourlyArr.length()) {
            val h = hourlyArr.getJSONObject(i)
            hourlyList.add(HourlyForecast(
                time = h.getLong("time"),
                temp = h.getDouble("temp"),
                conditionCode = h.getInt("conditionCode"),
                precipProb = h.getInt("precipProb")
            ))
        }

        return WeatherData(
            temperature = json.getDouble("temperature"),
            feelsLike = json.getDouble("feelsLike"),
            conditionCode = json.getInt("conditionCode"),
            description = json.getString("description"),
            humidity = json.getInt("humidity"),
            windSpeed = json.getDouble("windSpeed"),
            windGust = json.optDouble("windGust", Double.NaN).let {
                if (it.isNaN()) null else it
            },
            windDirection = json.getInt("windDirection"),
            pressure = json.getDouble("pressure"),
            visibility = json.optDouble("visibility", Double.NaN).let {
                if (it.isNaN()) null else it
            },
            uvIndex = json.optDouble("uvIndex", Double.NaN).let {
                if (it.isNaN()) null else it
            },
            precipitationProb = json.optInt("precipitationProb", -1).let {
                if (it < 0) null else it
            },
            sunrise = json.getLong("sunrise"),
            sunset = json.getLong("sunset"),
            dailyForecast = dailyList,
            hourlyForecast = hourlyList
        )
    }
}
