package com.v8td.allclear.data

/**
 * Информация о провайдере для отображения в настройках.
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val description: String,
    val requiresKey: Boolean,
    val backgroundAllowed: Boolean,
    val attribution: String?
)

/**
 * Список всех доступных провайдеров с метаинформацией.
 */
object ProviderRegistry {

    fun getAll(): List<ProviderInfo> = listOf(
        ProviderInfo(
            id = WeatherRepository.PROVIDER_OPEN_METEO,
            name = "Open-Meteo",
            description = "Основной бесплатный источник. Без ключа, без ограничений.",
            requiresKey = false,
            backgroundAllowed = true,
            attribution = "© Open-Meteo.com (CC BY 4.0)"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_MET_NORWAY,
            name = "MET Norway (Yr.no)",
            description = "Точные данные. Только при открытом приложении (правила API).",
            requiresKey = false,
            backgroundAllowed = false,
            attribution = "© MET Norway (CC BY 4.0)"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_METAR,
            name = "AviationWeather METAR",
            description = "Данные с аэродромных станций. Только текущая погода, без прогноза.",
            requiresKey = false,
            backgroundAllowed = true,
            attribution = "© AviationWeather.gov"
        ),
        ProviderInfo(
            id = WeatherRepository.PROVIDER_OWM,
            name = "OpenWeatherMap",
            description = "Требуется бесплатный API-ключ. Прогноз на 5 дней.",
            requiresKey = true,
            backgroundAllowed = true,
            attribution = "© OpenWeatherMap"
        )
    )

    fun getById(id: String): ProviderInfo? = getAll().find { it.id == id }
}
