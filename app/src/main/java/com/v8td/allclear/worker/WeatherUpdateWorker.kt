package com.v8td.allclear.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v8td.allclear.data.WeatherRepository
import com.v8td.allclear.util.AppSettings
import java.util.Calendar

/**
 * Фоновое обновление погоды (WorkManager, период ~60 мин).
 * Соблюдает тихие часы и режим «только Wi-Fi».
 * MET Norway в фоне репозиторий сам заменяет на Open-Meteo.
 *
 * Исправлено в ревизии:
 * - Координаты читаются из AppSettings, куда их пишет LocationHelper (разрыв закрыт).
 * - При «только Wi-Fi» и мобильной сети — пропуск (success), а не retry:
 *   retry заставлял бы систему долбить сеть вне расписания.
 */
class WeatherUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "allclear_weather_update"
    }

    override suspend fun doWork(): Result {
        val settings = AppSettings(applicationContext)

        // Тихие часы — пропускаем до следующего планового запуска
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (settings.isQuietHoursNow(hour)) {
            settings.lastSkipReason = "Тихие часы"
            return Result.success()
        }

        // Только Wi-Fi
        if (settings.wifiOnly && !isOnWifi()) {
            settings.lastSkipReason = "Ожидание Wi-Fi"
            return Result.success()
        }

        // Координаты из настроек (их сохраняет LocationHelper)
        val lat = settings.getLastLat()
        val lon = settings.getLastLon()
        if (lat == null || lon == null) {
            settings.lastSkipReason = "Координаты ещё не определены"
            return Result.success()
        }

        val repository = WeatherRepository(applicationContext)
        val result = repository.getWeather(
            lat, lon,
            forceRefresh = true,
            isBackground = true
        )

        return if (result.isSuccess) {
            settings.lastSkipReason = null
            Result.success()
        } else {
            // Сеть подвела — WorkManager повторит с нарастающей паузой
            Result.retry()
        }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            false
        }
    }
}
