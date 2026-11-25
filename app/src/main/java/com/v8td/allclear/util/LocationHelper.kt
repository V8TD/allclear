package com.v8td.allclear.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Помощник геолокации.
 * Использует системный LocationManager (без Google Play Services).
 * Обратное геокодирование через Nominatim (OpenStreetMap).
 */
class LocationHelper(private val context: Context) {

    companion object {
        private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
        private const val USER_AGENT = "AllClear/1.0 (github.com/V8TD/allclear)"
        private const val LOCATION_TIMEOUT_MS = 10_000L
    }

    /**
     * Получить текущие координаты.
     * Возвращает null если нет разрешений или таймаут.
     */
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    
                    // Пробуем GPS сначала, потом Network
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> {
                            cont.resume(null)
                            return@suspendCancellableCoroutine
                        }
                    }

                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            cont.resume(Pair(location.latitude, location.longitude))
                        }

                        override fun onProviderDisabled(provider: String) {
                            lm.removeUpdates(this)
                            if (!cont.isCompleted) cont.resume(null)
                        }

                        override fun onProviderEnabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }

                    try {
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation {
                            lm.removeUpdates(listener)
                        }
                    } catch (e: SecurityException) {
                        cont.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Обратное геокодирование через Nominatim.
     * @param showDistrict показывать район в названии
     * @return название города или null
     */
    suspend fun reverseGeocode(
        lat: Double,
        lon: Double,
        showDistrict: Boolean = false
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$NOMINATIM_URL?format=json&lat=$lat&lon=$lon&accept-language=ru,en"
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }

                val body = response.body?.string()
                response.close()
                if (body.isNullOrEmpty()) return@withContext null

                val json = JSONObject(body)
                val address = json.getJSONObject("address")

                val city = address.optString("city")
                    .ifEmpty { address.optString("town") }
                    .ifEmpty { address.optString("village") }
                    .ifEmpty { address.optString("hamlet") }
                    .ifEmpty { null }

                if (city == null) return@withContext null

                if (showDistrict) {
                    val district = address.optString("city_district")
                        .ifEmpty { address.optString("suburb") }
                        .ifEmpty { address.optString("neighbourhood") }
                    
                    if (district.isNotEmpty()) "$city, $district" else city
                } else {
                    city
                }
            } catch (e: IOException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Получить последнюю известную локацию (без запроса GPS).
     * Быстрый вариант для старта приложения.
     */
    fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) return Pair(loc.latitude, loc.longitude)
            } catch (e: SecurityException) {
                continue
            }
        }
        return null
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
