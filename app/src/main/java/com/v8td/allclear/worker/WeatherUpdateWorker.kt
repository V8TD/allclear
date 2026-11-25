package com.v8td.allclear.worker

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v8td.allclear.data.WeatherRepository
import java.util.Calendar

/**
 * Фоновый работник обновления погоды через WorkManager.
 * Запускается периодически (~60 мин).
 * Соблюдает тихие часы и ограничение Wi-Fi.
 * MET Norway автоматически заменяется на Open-Meteo в фоне.
 */
class WeatherUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "allclear_weather_update"
        private const val PREFS_NAME = "allclear_settings"
        private const val KEY_QUIET_START = "quiet_hours_start"
        private const val KEY_QUIET_END = "quiet_hours_end"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AUTO_LOCATION_LAT = "auto_location_lat"
        private const val KEY_AUTO_LOCATION_LON = "auto_location_lon"
        private const val DEFAULT_QUIET_START = 23
        private const val DEFAULT_QUIET_END = 7
    }

    override suspend fun doWork(): Result {
        val settingsPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Проверка тихих часов
        if (isQuietHours(settingsPrefs)) {
            return Result.success() // Пропускаем обновление, но не считаем ошибкой
        }

        // Проверка Wi-Fi only
        if (isWifiOnly(settingsPrefs) && !isOnWifi()) {
            return Result.retry() // Попробуем позже
        }

        // Получаем координаты
        val lat = settingsPrefs.getFloat(KEY_AUTO_LOCATION_LAT, Float.NaN).toDouble()
        val lon = settingsPrefs.getFloat(KEY_AUTO_LOCATION_LON, Float.NaN).toDouble()

        if (lat.isNaN() || lon.isNaN()) {
            // Нет сохранённых координат — пропускаем
            return Result.success()
        }

        // Запрос погоды (isBackground = true → MET Norway заменится на Open-Meteo)
        val repository = WeatherRepository(applicationContext)
        val result = repository.getWeather(lat, lon, forceRefresh = true, isBackground = true)

        return when {
            result.isSuccess -> Result.success()
            else -> Result.retry()
        }
    }

    private fun isQuietHours(prefs: SharedPreferences): Boolean {
        val startHour = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
        val endHour = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return if (startHour > endHour) {
            // Тихие часы переходят через полночь (23:00–07:00)
            currentHour >= startHour || currentHour < endHour
        } else {
            // Тихие часы в пределах одного дня
            currentHour >= startHour && currentHour < endHour
        }
    }

    private fun isWifiOnly(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_WIFI_ONLY, false)
    }

    private fun isOnWifi(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
