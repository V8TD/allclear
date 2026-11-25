package com.v8td.allclear.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.v8td.allclear.data.BaseWeatherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Геолокация без Google Play Services (системный LocationManager).
 * Обратное геокодирование — Nominatim (OSM), лимит 1 запрос/сек, User-Agent обязателен.
 *
 * Исправлено в ревизии:
 * - Координаты СОХРАНЯЮТСЯ в AppSettings — фоновый воркер теперь их видит.
 * - Сначала берём свежую последнюю локацию (мгновенно, без включения GPS),
 *   и только если её нет/старая — запрашиваем одиночное обновление.
 * - Координаты округляются до 4 знаков.
 */
class LocationHelper(private val context: Context) {

    companion object {
        private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
        private const val USER_AGENT = "AllClear/1.0 (github.com/V8TD/allclear)"
        private const val LOCATION_TIMEOUT_MS = 12_000L
        private const val LAST_KNOWN_FRESH_MS = 10 * 60 * 1000L

        // Nominatim: не чаще 1 запроса в секунду на всё приложение
        @Volatile private var lastGeocodeRequestAt = 0L
        private val geocodeLock = Any()
    }

    private val settings = AppSettings(context)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Получить координаты и сохранить их в настройки.
     * Порядок: свежая последняя локация → одиночный запрос → любая последняя локация.
     */
    suspend fun obtainAndSaveLocation(): Pair<Double, Double>? {
        val loc = getCurrentLocation() ?: getLastKnownLocation() ?: run {
            // Совсем ничего — пробуем то, что сохранено раньше
            val la = settings.getLastLat()
            val lo = settings.getLastLon()
            if (la != null && lo != null) Pair(la, lo) else null
        }
        if (loc != null) {
            val lat = BaseWeatherProvider.roundCoord(loc.first)
            val lon = BaseWeatherProvider.roundCoord(loc.second)
            settings.saveLocation(lat, lon, null)
            return Pair(lat, lon)
        }
        return null
    }

    /** Одиночное определение координат. null при отсутствии разрешений/таймауте. */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null

        // 1. Свежая последняя локация — бесплатно и мгновенно
        getLastKnownLocation()?.let { last ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val fresh = bestLastKnown(lm)
            if (fresh != null &&
                System.currentTimeMillis() - fresh.time < LAST_KNOWN_FRESH_MS) {
                return last
            }
        }

        // 2. Одиночный запрос обновления
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                            LocationManager.NETWORK_PROVIDER  // сеть быстрее и дешевле GPS
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                            LocationManager.GPS_PROVIDER
                        else -> null
                    }
                    if (provider == null) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (!cont.isCompleted) {
                                cont.resume(Pair(location.latitude, location.longitude))
                            }
                        }
                        override fun onProviderDisabled(provider: String) {
                            lm.removeUpdates(this)
                            if (!cont.isCompleted) cont.resume(null)
                        }
                        override fun onProviderEnabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    }

                    try {
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    } catch (e: Exception) {
                        if (!cont.isCompleted) cont.resume(null)
                    }
                }
            }
        }
    }

    /** Последняя известная локация (без запроса к датчикам). */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = bestLastKnown(lm) ?: return null
        return Pair(loc.latitude, loc.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (e: Exception) {
                continue
            }
        }
        return best
    }

    /**
     * Название города по координатам (Nominatim).
     * @param showDistrict добавлять район («Город, Район»)
     * Результат сохраняется в настройки.
     */
    suspend fun reverseGeocode(
        lat: Double,
        lon: Double,
        showDistrict: Boolean = settings.showDistrict
    ): String? {
        return withContext(Dispatchers.IO) {
            // Соблюдаем лимит 1 запрос/сек
            synchronized(geocodeLock) {
                val wait = 1100L - (System.currentTimeMillis() - lastGeocodeRequestAt)
                if (wait > 0) {
                    try { Thread.sleep(wait) } catch (e: InterruptedException) { }
                }
                lastGeocodeRequestAt = System.currentTimeMillis()
            }

            try {
                val url = "$NOMINATIM_URL?format=json&lat=$lat&lon=$lon" +
                    "&zoom=10&accept-language=ru,en"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()

                val body = BaseWeatherProvider.httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string()
                } ?: return@withContext null

                val address = JSONObject(body).optJSONObject("address")
                    ?: return@withContext null

                val city = listOf("city", "town", "village", "hamlet", "municipality")
                    .firstNotNullOfOrNull { key ->
                        address.optString(key).takeIf { it.isNotEmpty() }
                    } ?: return@withContext null

                val result = if (showDistrict) {
                    val district = listOf("city_district", "suburb", "neighbourhood", "borough")
                        .firstNotNullOfOrNull { key ->
                            address.optString(key).takeIf { it.isNotEmpty() }
                        }
                    if (district != null && district != city) "$city, $district" else city
                } else city

                settings.lastCity = result
                result
            } catch (e: Exception) {
                null
            }
        }
    }
}
