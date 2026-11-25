package com.v8td.allclear.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Базовый интерфейс для всех провайдеров погоды.
 */
interface WeatherProvider {
    val name: String
    suspend fun getWeather(lat: Double, lon: Double): WeatherData?
}

/**
 * Универсальная модель данных погоды (единый формат для всех источников).
 * Температура °C, ветер м/с, давление гПа, видимость км.
 * null = данных нет (в UI показывается прочерк).
 */
data class WeatherData(
    val temperature: Double,
    val feelsLike: Double,
    val conditionCode: Int,           // Код Yahoo/Chronus (0-47)
    val description: String,
    val humidity: Int,                // %
    val windSpeed: Double,            // м/с
    val windGust: Double?,            // м/с
    val windDirection: Int,           // градусы
    val pressure: Double,             // гПа
    val visibility: Double?,          // км
    val uvIndex: Double?,
    val precipitationProb: Int?,      // %
    val dewPoint: Double?,            // точка росы °C
    val sunrise: Long,                // ms, 0 = нет данных
    val sunset: Long,                 // ms, 0 = нет данных
    val observationTime: Long,        // ms, время актуальности данных
    val dailyForecast: List<DailyForecast>,
    val hourlyForecast: List<HourlyForecast>
)

data class DailyForecast(
    val date: Long,           // начало локального дня, ms
    val tempMin: Double,
    val tempMax: Double,
    val conditionCode: Int,
    val precipProb: Int       // 0-100, -1 = нет данных
)

data class HourlyForecast(
    val time: Long,
    val temp: Double,
    val conditionCode: Int,
    val precipProb: Int,      // 0-100, -1 = нет данных
    val isDay: Boolean
)

/**
 * Общий HTTP-клиент (один на всё приложение — экономия памяти и соединений)
 * и утилиты для провайдеров.
 */
abstract class BaseWeatherProvider : WeatherProvider {

    companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /**
         * Округление координат до 4 знаков.
         * Требование MET Norway (5+ знаков = 403 Forbidden) и просто разумно:
         * точность ~11 метров, погоду это не меняет, а кэш не плодит дубли.
         */
        fun roundCoord(value: Double): Double {
            return String.format(Locale.US, "%.4f", value).toDouble()
        }
    }

    /** GET-запрос, ответ — JSON-объект. null при любой ошибке. */
    protected suspend fun fetchJson(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        val body = fetchBody(url, headers) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    /** GET-запрос, ответ — JSON-массив (нужно для AviationWeather). */
    protected suspend fun fetchJsonArray(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): JSONArray? {
        val body = fetchBody(url, headers) ?: return null
        return try {
            JSONArray(body)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchBody(
        url: String,
        headers: Map<String, String>
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string()?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Безопасное чтение Double из JSONObject (поля бывают строками или null). */
    protected fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.replace("+", "").trim().toDoubleOrNull()
            else -> null
        }
    }
}
