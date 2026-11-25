package com.v8td.allclear.data

/**
 * Метаинформация о провайдере для экрана настроек.
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val description: String,
    val requiresKey: Boolean,
    val backgroundAllowed: Boolean,
    val hasForecast: Boolean,
    val attribution: String?
)

object ProviderRegistry {

    fun getAll(): List<ProviderInfo> = listOf(
        ProviderInfo(
            id = WeatherRepository.PROVIDER_OPEN_METEO,
            name = "Open-Meteo",
            description = "Основной источник. Бесплатно, без ключа.",
            requiresKey = false,
            backgroundAllowed = true,
            hasForecast = true,
            attribution = "© Open-Meteo.com (CC BY 4.0)"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_MET_NORWAY,
            name = "MET Norway",
            description = "Точный прогноз. Только при открытом приложении; " +
                "в фоне автоматически используется Open-Meteo.",
            requiresKey = false,
            backgroundAllowed = false,
            hasForecast = true,
            attribution = "© MET Norway (CC BY 4.0)"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_METAR,
            name = "METAR (авиаметео)",
            description = "Наблюдения аэродромных станций. Прогноз дополняется от Open-Meteo.",
            requiresKey = false,
            backgroundAllowed = true,
            hasForecast = false,
            attribution = "© AviationWeather.gov (NOAA)"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_OWM,
            name = "OpenWeatherMap",
            description = "Нужен бесплатный API-ключ (без карты). Прогноз на 5 дней.",
            requiresKey = true,
            backgroundAllowed = true,
            hasForecast = true,
            attribution = "© OpenWeather Ltd"
        )
    )

    fun getById(id: String): ProviderInfo? = getAll().find { it.id == id }
}
