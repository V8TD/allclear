package com.v8td.allclear.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Базовый интерфейс для всех провайдеров погоды.
 * Каждый провайдер должен уметь получать текущую погоду и прогноз по координатам.
 */
interface WeatherProvider {
    val name: String
    
    /**
     * Получить данные о погоде.
     * @param lat Широта
     * @param lon Долгота
     * @return Объект WeatherData или null при ошибке
     */
    suspend fun getWeather(lat: Double, lon: Double): WeatherData?
}

/**
 * Универсальная модель данных погоды.
 * Агрегирует информацию от разных источников в единый формат.
 */
data class WeatherData(
    val temperature: Double,          // Текущая температура (°C)
    val feelsLike: Double,            // Ощущается как (°C)
    val conditionCode: Int,           // Код погоды (Yahoo/Chronus формат 0-47)
    val description: String,          // Текстовое описание
    val humidity: Int,                // Влажность (%)
    val windSpeed: Double,            // Скорость ветра (м/с)
    val windGust: Double?,            // Порывы ветра (м/с), может быть null
    val windDirection: Int,           // Направление ветра (градусы 0-360)
    val pressure: Double,             // Давление (гПа)
    val visibility: Double?,          // Видимость (км), может быть null
    val uvIndex: Double?,             // УФ-индекс, может быть null
    val precipitationProb: Int?,      // Вероятность осадков (%), может быть null
    val sunrise: Long,                // Восход (Unix timestamp ms)
    val sunset: Long,                 // Закат (Unix timestamp ms)
    val dailyForecast: List<DailyForecast>,   // Прогноз на 7 дней
    val hourlyForecast: List<HourlyForecast>  // Прогноз на 24 часа
)

data class DailyForecast(
    val date: Long,           // Дата (Unix timestamp ms, начало дня)
    val tempMin: Double,      // Минимальная температура
    val tempMax: Double,      // Максимальная температура
    val conditionCode: Int,   // Код погоды (дневной)
    val precipProb: Int       // Вероятность осадков (%)
)

data class HourlyForecast(
    val time: Long,           // Время (Unix timestamp ms)
    val temp: Double,         // Температура
    val conditionCode: Int,   // Код погоды
    val precipProb: Int       // Вероятность осадков (%)
)

/**
 * Базовый класс с общими утилитами для HTTP-запросов и парсинга.
 */
abstract class BaseWeatherProvider : WeatherProvider {
    
    protected suspend fun fetchJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val requestBuilder = okhttp3.Request.Builder().url(url)
                headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
                
                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }
                
                val body = response.body?.string()
                response.close()
                if (body.isNullOrEmpty()) return@withContext null
                
                JSONObject(body)
            } catch (e: IOException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Конвертация направления ветра из градусов в текстовое представление (опционально).
     */
    protected fun degreesToDirection(degrees: Int): String {
        val directions = arrayOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val index = ((degrees + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }
}
