package com.v8td.allclear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Город в списке пользователя.
 * isAuto = true — «Текущее местоположение» (координаты обновляет LocationHelper).
 */
data class City(
    val id: String,
    var name: String,
    var region: String?,      // область/страна для мелкой подписи, может быть null
    var lat: Double,
    var lon: Double,
    val isAuto: Boolean = false
)

/**
 * Хранилище списка городов в JSON-файле cities.json.
 * Максимум 10 городов (включая авто-город). Порядок в файле = порядок страниц.
 */
class CityStore(private val context: Context) {

    companion object {
        const val MAX_CITIES = 10
        private const val FILE_NAME = "cities.json"
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    private val cities = mutableListOf<City>()
    private var loaded = false

    /** Загрузить список из файла (один раз). */
    suspend fun load(): List<City> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext cities.toList()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    cities.add(
                        City(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            region = o.optString("region").takeIf { it.isNotEmpty() },
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            isAuto = o.optBoolean("isAuto", false)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            cities.clear() // повреждённый файл начинаем заново
        }
        loaded = true
        cities.toList()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            cities.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("region", c.region ?: "")
                    put("lat", c.lat)
                    put("lon", c.lon)
                    put("isAuto", c.isAuto)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) { }
    }

    fun getAll(): List<City> = cities.toList()

    fun count(): Int = cities.size

    fun getById(id: String): City? = cities.find { it.id == id }

    fun getAutoCity(): City? = cities.find { it.isAuto }

    /**
     * Создать или обновить авто-город (первая страница).
     * Вызывается после определения координат и геокодирования.
     */
    suspend fun upsertAutoCity(name: String, region: String?, lat: Double, lon: Double): City {
        val existing = getAutoCity()
        val city = if (existing != null) {
            existing.name = name
            existing.region = region
            existing.lat = lat
            existing.lon = lon
            existing
        } else {
            City(
                id = "auto",
                name = name,
                region = region,
                lat = lat,
                lon = lon,
                isAuto = true
            ).also { cities.add(0, it) }
        }
        persist()
        return city
    }

    /** Добавить обычный город. null если лимит достигнут или дубликат рядом (<2 км). */
    suspend fun addCity(name: String, region: String?, lat: Double, lon: Double): City? {
        if (cities.size >= MAX_CITIES) return null
        val duplicate = cities.any {
            kotlin.math.abs(it.lat - lat) < 0.02 && kotlin.math.abs(it.lon - lon) < 0.02
        }
        if (duplicate) return null
        val city = City(
            id = UUID.randomUUID().toString(),
            name = name,
            region = region,
            lat = BaseWeatherProvider.roundCoord(lat),
            lon = BaseWeatherProvider.roundCoord(lon)
        )
        cities.add(city)
        persist()
        return city
    }

    /** Удалить город по id (авто-город удалить нельзя — им управляет тумблер геолокации). */
    suspend fun removeCity(id: String): Boolean {
        val city = getById(id) ?: return false
        if (city.isAuto) return false
        cities.remove(city)
        persist()
        return true
    }

    /** Убрать авто-город (тумблер автоопределения выключен). */
    suspend fun removeAutoCity() {
        cities.removeAll { it.isAuto }
        persist()
    }

    /** Переставить город (для будущего экрана управления городами). */
    suspend fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in cities.indices || toIndex !in cities.indices) return
        val c = cities.removeAt(fromIndex)
        cities.add(toIndex, c)
        persist()
    }
}
