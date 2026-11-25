package com.v8td.allclear.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Единое хранилище настроек приложения.
 * ВСЕ настройки живут в одном файле "allclear_settings".
 * Раньше координаты воркер читал из ключей, которые никто не писал —
 * теперь и запись (LocationHelper), и чтение (Worker) идут через этот класс.
 */
class AppSettings(context: Context) {

    companion object {
        const val PREFS_NAME = "allclear_settings"

        // Провайдер
        const val KEY_PROVIDER = "selected_provider"
        const val KEY_OWM_API_KEY = "owm_api_key"
        const val KEY_METAR_ICAO = "metar_icao"

        // Состояние последнего обновления
        const val KEY_LAST_ERROR = "last_error_message"
        const val KEY_LAST_UPDATE_TIME = "last_update_time"
        const val KEY_LAST_SKIP_REASON = "last_skip_reason"

        // Координаты последней локации (строкой — SharedPreferences не умеет Double)
        const val KEY_LAST_LAT = "auto_location_lat"
        const val KEY_LAST_LON = "auto_location_lon"
        const val KEY_LAST_CITY = "auto_location_city"
        const val KEY_LOCATION_TIME = "auto_location_time"

        // Фоновые обновления
        const val KEY_QUIET_START = "quiet_hours_start"
        const val KEY_QUIET_END = "quiet_hours_end"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_UPDATE_INTERVAL_MIN = "update_interval_min"

        // Геолокация
        const val KEY_AUTO_LOCATION = "auto_location_enabled"
        const val KEY_SHOW_DISTRICT = "show_district"

        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7
        const val DEFAULT_INTERVAL_MIN = 60
        const val MIN_INTERVAL_MIN = 30   // чаще 30 минут не даём — экономия батареи
    }

    val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Провайдер ---

    var providerId: String
        get() = prefs.getString(KEY_PROVIDER, "open_meteo") ?: "open_meteo"
        set(value) = prefs.edit { putString(KEY_PROVIDER, value) }

    var owmApiKey: String
        get() = prefs.getString(KEY_OWM_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_OWM_API_KEY, value.trim()) }

    var metarIcao: String
        get() = prefs.getString(KEY_METAR_ICAO, "") ?: ""
        set(value) = prefs.edit { putString(KEY_METAR_ICAO, value.trim().uppercase()) }

    // --- Состояние обновления ---

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value)
        }

    var lastUpdateTime: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE_TIME, value) }

    var lastSkipReason: String?
        get() = prefs.getString(KEY_LAST_SKIP_REASON, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SKIP_REASON) else putString(KEY_LAST_SKIP_REASON, value)
        }

    // --- Координаты ---

    /** Сохранить координаты (вызывается из LocationHelper). null = очистить. */
    fun saveLocation(lat: Double?, lon: Double?, city: String?) {
        prefs.edit {
            if (lat == null || lon == null) {
                remove(KEY_LAST_LAT)
                remove(KEY_LAST_LON)
            } else {
                putString(KEY_LAST_LAT, lat.toString())
                putString(KEY_LAST_LON, lon.toString())
                putLong(KEY_LOCATION_TIME, System.currentTimeMillis())
            }
            if (city != null) putString(KEY_LAST_CITY, city)
        }
    }

    fun getLastLat(): Double? = prefs.getString(KEY_LAST_LAT, null)?.toDoubleOrNull()
    fun getLastLon(): Double? = prefs.getString(KEY_LAST_LON, null)?.toDoubleOrNull()

    var lastCity: String?
        get() = prefs.getString(KEY_LAST_CITY, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_CITY) else putString(KEY_LAST_CITY, value)
        }

    var locationTime: Long
        get() = prefs.getLong(KEY_LOCATION_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_LOCATION_TIME, value) }

    // --- Фоновые обновления ---

    var quietStart: Int
        get() = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
        set(value) = prefs.edit { putInt(KEY_QUIET_START, value.coerceIn(0, 23)) }

    var quietEnd: Int
        get() = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
        set(value) = prefs.edit { putInt(KEY_QUIET_END, value.coerceIn(0, 23)) }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    var updateIntervalMin: Int
        get() = prefs.getInt(KEY_UPDATE_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        set(value) = prefs.edit {
            putInt(KEY_UPDATE_INTERVAL_MIN, value.coerceAtLeast(MIN_INTERVAL_MIN))
        }

    // --- Геолокация ---

    var autoLocationEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOCATION, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_LOCATION, value) }

    var showDistrict: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DISTRICT, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_DISTRICT, value) }

    /** Проверка тихих часов (переход через полночь поддерживается). */
    fun isQuietHoursNow(hourOfDay: Int): Boolean {
        val start = quietStart
        val end = quietEnd
        if (start == end) return false
        return if (start > end) {
            hourOfDay >= start || hourOfDay < end
        } else {
            hourOfDay >= start && hourOfDay < end
        }
    }
}
