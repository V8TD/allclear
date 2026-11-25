package com.v8td.allclear.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Репозиторий погоды.
 * Единая точка доступа к данным: кэш → сеть → сохранение в кэш.
 * Управляет выбором провайдера и обработкой ошибок.
 */
class WeatherRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "allclear_weather_prefs"
        private const val KEY_PROVIDER = "selected_provider"
        private const val KEY_OWM_API_KEY = "owm_api_key"
        private const val KEY_METAR_ICAO = "metar_icao"
        private const val KEY_LAST_ERROR = "last_error_message"
        private const val KEY_LAST_UPDATE_TIME = "last_update_time"

        const val PROVIDER_OPEN_METEO = "open_meteo"
        const val PROVIDER_MET_NORWAY = "met_norway"
        const val PROVIDER_METAR = "metar"
        const val PROVIDER_OWM = "owm"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = WeatherCache(context)

    /**
     * Получить текущего провайдера.
     */
    fun getSelectedProviderId(): String {
        return prefs.getString(KEY_PROVIDER, PROVIDER_OPEN_METEO) ?: PROVIDER_OPEN_METEO
    }

    /**
     * Установить активного провайдера.
     */
    fun setSelectedProvider(providerId: String) {
        prefs.edit { putString(KEY_PROVIDER, providerId) }
    }

    /**
     * Сохранить API-ключ OWM.
     */
    fun setOwmApiKey(key: String) {
        prefs.edit { putString(KEY_OWM_API_KEY, key) }
    }

    fun getOwmApiKey(): String {
        return prefs.getString(KEY_OWM_API_KEY, "") ?: ""
    }

    /**
     * Сохранить ручной ICAO-код для METAR.
     */
    fun setMetarIcao(icao: String) {
        prefs.edit { putString(KEY_METAR_ICAO, icao) }
    }

    fun getMetarIcao(): String {
        return prefs.getString(KEY_METAR_ICAO, "") ?: ""
    }

    /**
     * Получить последнее сообщение об ошибке (для отображения в UI).
     */
    fun getLastError(): String? {
        return prefs.getString(KEY_LAST_ERROR, null)
    }

    /**
     * Получить время последнего успешного обновления (ms).
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE_TIME, 0L)
    }

    /**
     * Создать экземпляр провайдера по ID.
     */
    fun createProvider(providerId: String): WeatherProvider {
        return when (providerId) {
            PROVIDER_OPEN_METEO -> OpenMeteoProvider()
            PROVIDER_MET_NORWAY -> MetNorwayProvider()
            PROVIDER_METAR -> MetarProvider()
            PROVIDER_OWM -> OpenWeatherMapProvider(getOwmApiKey())
            else -> OpenMeteoProvider()
        }
    }

    /**
     * Основной метод получения погоды.
     * Логика: кэш → сеть → сохранение.
     * @param forceRefresh игнорировать кэш (при ручном обновлении)
     * @param isBackground true если вызов из WorkManager (MET Norway запрещён)
     */
    suspend fun getWeather(
        lat: Double,
        lon: Double,
        forceRefresh: Boolean = false,
        isBackground: Boolean = false
    ): Result<WeatherData> {
        val providerId = getSelectedProviderId()

        // MET Norway запрещён в фоне по правилам от 05.11.2026
        val effectiveProviderId = if (isBackground && providerId == PROVIDER_MET_NORWAY) {
            PROVIDER_OPEN_METEO
        } else {
            providerId
        }

        // METAR: если есть ручной ICAO, используем его вместо координат
        if (effectiveProviderId == PROVIDER_METAR) {
            val icao = getMetarIcao()
            if (icao.isNotBlank()) {
                val metarProvider = MetarProvider()
                val data = metarProvider.getWeatherByIcao(icao)
                if (data != null) {
                    cache.save(effectiveProviderId, lat, lon, data)
                    saveSuccessState()
                    return Result.success(data)
                }
                saveErrorState("METAR: станция $icao недоступна")
                return Result.failure(Exception("METAR station unavailable"))
            }
            // Если ICAO пустой — используем автопоиск по координатам ниже
        }

        // Проверяем кэш (если не принудительное обновление)
        if (!forceRefresh) {
            val cached = cache.load(effectiveProviderId, lat, lon)
            if (cached != null) {
                return Result.success(cached)
            }
        }

        // Запрос к сети
        val provider = createProvider(effectiveProviderId)
        val data = provider.getWeather(lat, lon)

        if (data != null) {
            cache.save(effectiveProviderId, lat, lon, data)
            saveSuccessState()
            return Result.success(data)
        }

        // Если сеть упала, пробуем отдать устаревший кэш
        val staleCache = cache.load(
            effectiveProviderId, lat, lon,
            maxAgeMs = WeatherCache.MAX_CACHE_AGE_MS * 4 // До 2 часов
        )
        if (staleCache != null) {
            saveErrorState("Нет сети, показаны устаревшие данные")
            return Result.success(staleCache)
        }

        saveErrorState("Не удалось получить данные от ${provider.name}")
        return Result.failure(Exception("Weather fetch failed from ${provider.name}"))
    }

    /**
     * Очистить кэш (например, при смене провайдера).
     */
    suspend fun clearCache() {
        cache.clearAll()
    }

    private fun saveSuccessState() {
        prefs.edit {
            putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis())
            remove(KEY_LAST_ERROR)
        }
    }

    private fun saveErrorState(message: String) {
        prefs.edit {
            putString(KEY_LAST_ERROR, message)
        }
    }
}
