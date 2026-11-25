package com.v8td.allclear.data

import android.content.Context
import com.v8td.allclear.util.AppSettings

/**
 * Репозиторий погоды — единая точка получения данных.
 * Логика: свежий кэш → сеть → сохранение → при неудаче устаревший кэш.
 * MET Norway в фоне автоматически заменяется на Open-Meteo (правила API).
 * METAR не даёт прогноз — прогноз долистывается от Open-Meteo.
 */
class WeatherRepository(context: Context) {

    companion object {
        const val PROVIDER_OPEN_METEO = "open_meteo"
        const val PROVIDER_MET_NORWAY = "met_norway"
        const val PROVIDER_METAR = "metar"
        const val PROVIDER_OWM = "owm"
    }

    private val settings = AppSettings(context)
    private val cache = WeatherCache(context)

    fun getSelectedProviderId(): String = settings.providerId

    fun setSelectedProvider(providerId: String) {
        settings.providerId = providerId
    }

    fun setOwmApiKey(key: String) { settings.owmApiKey = key }
    fun getOwmApiKey(): String = settings.owmApiKey
    fun setMetarIcao(icao: String) { settings.metarIcao = icao }
    fun getMetarIcao(): String = settings.metarIcao
    fun getLastError(): String? = settings.lastError
    fun getLastUpdateTime(): Long = settings.lastUpdateTime

    fun createProvider(providerId: String): WeatherProvider = when (providerId) {
        PROVIDER_OPEN_METEO -> OpenMeteoProvider()
        PROVIDER_MET_NORWAY -> MetNorwayProvider()
        PROVIDER_METAR -> MetarProvider()
        PROVIDER_OWM -> OpenWeatherMapProvider(getOwmApiKey())
        else -> OpenMeteoProvider()
    }

    /**
     * Получить погоду.
     * @param forceRefresh true при ручном обновлении (игнорирует свежий кэш)
     * @param isBackground true при вызове из WorkManager
     */
    suspend fun getWeather(
        lat: Double,
        lon: Double,
        forceRefresh: Boolean = false,
        isBackground: Boolean = false
    ): Result<WeatherData> {
        val selected = getSelectedProviderId()

        // MET Norway запрещён в фоне — тихо подменяем
        val effectiveId = if (isBackground && selected == PROVIDER_MET_NORWAY) {
            PROVIDER_OPEN_METEO
        } else selected

        // OWM без ключа работать не может — сразу понятная ошибка
        if (effectiveId == PROVIDER_OWM && getOwmApiKey().isBlank()) {
            settings.lastError = "OpenWeatherMap: не задан API-ключ"
            return Result.failure(IllegalStateException("OWM API key missing"))
        }

        // Свежий кэш (если не принудительное обновление)
        if (!forceRefresh) {
            cache.load(effectiveId, lat, lon)?.let {
                return Result.success(it)
            }
        }

        // Сеть
        val data = fetchFromNetwork(effectiveId, lat, lon)

        if (data != null) {
            cache.save(effectiveId, lat, lon, data)
            settings.lastUpdateTime = System.currentTimeMillis()
            settings.lastError = null
            return Result.success(data)
        }

        // Сеть не дала данных — пробуем устаревший кэш (до 6 часов)
        cache.load(effectiveId, lat, lon, WeatherCache.STALE_CACHE_AGE_MS)?.let { stale ->
            settings.lastError = "Нет свежих данных, показаны сохранённые"
            return Result.success(stale)
        }

        val providerName = createProvider(effectiveId).name
        settings.lastError = "Не удалось получить данные ($providerName)"
        return Result.failure(Exception("Weather fetch failed: $providerName"))
    }

    /** Сетевой запрос с учётом особенностей провайдеров. */
    private suspend fun fetchFromNetwork(
        providerId: String,
        lat: Double,
        lon: Double
    ): WeatherData? {
        if (providerId == PROVIDER_METAR) {
            val metar = MetarProvider()
            val icao = getMetarIcao()
            val current = if (icao.isNotBlank()) {
                metar.getWeatherByIcao(icao)
            } else {
                metar.getWeather(lat, lon)
            } ?: return null

            // У METAR нет прогноза — берём его у Open-Meteo (без ключа, всегда доступен).
            // Если Open-Meteo недоступен, отдаём METAR как есть (плитки покажут прочерки).
            val forecast = OpenMeteoProvider().getWeather(lat, lon)
            return if (forecast != null) {
                current.copy(
                    dailyForecast = forecast.dailyForecast,
                    hourlyForecast = forecast.hourlyForecast,
                    sunrise = forecast.sunrise,
                    sunset = forecast.sunset,
                    uvIndex = current.uvIndex ?: forecast.uvIndex
                )
            } else current
        }

        return createProvider(providerId).getWeather(lat, lon)
    }

    suspend fun clearCache() = cache.clearAll()

    /** Возраст кэша текущего провайдера для подписи в UI. */
    suspend fun cacheAgeMs(lat: Double, lon: Double): Long? {
        val id = getSelectedProviderId()
        return cache.ageMs(id, lat, lon)
    }
}
