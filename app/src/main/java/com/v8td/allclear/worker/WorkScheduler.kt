package com.v8td.allclear.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.v8td.allclear.util.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Планирование фонового обновления.
 * Случайная начальная задержка (0–20 мин) и гибкое окно 10 мин —
 * запросы разных устройств не бьют в API одновременно ровно в начале часа
 * (требование MET Norway о равномерной нагрузке, плюс просто вежливость).
 * Вызывать при старте приложения и при изменении настроек обновления.
 */
object WorkScheduler {

    /** Поставить/обновить периодическое задание по текущим настройкам. */
    fun schedule(context: Context) {
        val settings = AppSettings(context)
        val intervalMin = settings.updateIntervalMin.toLong()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<WeatherUpdateWorker>(
            intervalMin, TimeUnit.MINUTES,   // период
            10, TimeUnit.MINUTES             // гибкое окно в конце периода
        )
            .setConstraints(constraints)
            .setInitialDelay((0..20).random().toLong(), TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeatherUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // при смене интервала задание пересоздастся
            request
        )
    }

    /** Отменить фоновые обновления (если пользователь выключит их в настройках). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WeatherUpdateWorker.WORK_NAME)
    }
}
