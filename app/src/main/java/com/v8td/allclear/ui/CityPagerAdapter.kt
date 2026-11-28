package com.v8td.allclear.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v8td.allclear.R
import com.v8td.allclear.data.City
import com.v8td.allclear.data.HourlyForecast
import com.v8td.allclear.data.WeatherData
import com.v8td.allclear.databinding.PageCityBinding
import com.v8td.allclear.util.WeatherFormat
import java.util.Calendar
import kotlin.math.abs

/**
 * Страницы городов для ViewPager2.
 * Стабильные ID — страницы не пересоздаются при обновлении данных.
 * Прогнозы — горизонтальные ленты WM8; в почасовой ленте между часами
 * вставляются колонки-маркеры восхода и заката (как в WM8).
 * Дни недели — короткие (Пн, Вт…), без многоточий.
 */
class CityPagerAdapter(
    private val context: Context
) : RecyclerView.Adapter<CityPagerAdapter.PageHolder>() {

    private val cities = mutableListOf<City>()
    private val weatherMap = mutableMapOf<String, WeatherData?>()
    private val holders = mutableMapOf<Int, PageHolder>()
    private var activePosition = 0
    private var resumed = false

    init {
        setHasStableIds(true)
    }

    fun submitCities(list: List<City>) {
        cities.clear()
        cities.addAll(list)
        notifyDataSetChanged()
    }

    fun getCity(position: Int): City? = cities.getOrNull(position)

    fun updateWeather(cityId: String, data: WeatherData?) {
        weatherMap[cityId] = data
        val index = cities.indexOfFirst { it.id == cityId }
        if (index >= 0) notifyItemChanged(index)
    }

    /** Может ли текущая страница прокручиваться вверх (для потягивателя). */
    fun canCurrentPageScrollUp(): Boolean {
        val holder = holders[activePosition] ?: return false
        return holder.binding.scrollContent.canScrollVertically(-1)
    }

    /** Какая страница видима и активно ли приложение — сцена и живые иконки. */
    fun setActivePage(position: Int, isResumed: Boolean) {
        activePosition = position
        resumed = isResumed
        holders.values.forEach { holder ->
            val pos = holder.bindingAdapterPosition
            val live = pos == position && isResumed
            holder.binding.sceneView.setRunning(live)
            holder.binding.hourlyStrip.setAnimating(live)
        }
    }

    override fun getItemCount(): Int = cities.size

    override fun getItemId(position: Int): Long = cities[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = PageCityBinding.inflate(LayoutInflater.from(context), parent, false)
        val holder = PageHolder(binding)
        binding.detailsGrid.layoutManager = GridLayoutManager(context, 2)
        binding.detailsGrid.adapter = holder.tilesAdapter
        return holder
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        holders[holder.bindingAdapterPosition] = holder
        val live = holder.bindingAdapterPosition == activePosition && resumed
        holder.binding.sceneView.setRunning(live)
        holder.binding.hourlyStrip.setAnimating(live)
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        holders.remove(holder.bindingAdapterPosition)
        holder.binding.sceneView.setRunning(false)
        holder.binding.hourlyStrip.setAnimating(false)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val city = cities[position]
        val data = weatherMap[city.id]
        holder.bind(city, data)
        holders[position] = holder
        val live = position == activePosition && resumed
        holder.binding.sceneView.setRunning(live)
        holder.binding.hourlyStrip.setAnimating(live)
    }

    /** Часы + маркеры восхода/заката в хронологическом порядке. */
    private fun buildHourlyColumns(data: WeatherData): List<HourlyColumn> {
        val hours = data.hourlyForecast
        if (hours.isEmpty()) return emptyList()
        val start = hours.first().time
        val end = hours.last().time

        val points = ArrayList<Triple<Long, Int, HourlyForecast?>>()
        for (h in hours) points.add(Triple(h.time, 0, h))
        if (data.sunrise in start..end) points.add(Triple(data.sunrise, 1, null))
        if (data.sunset in start..end && data.sunset > data.sunrise) {
            points.add(Triple(data.sunset, 2, null))
        }
        points.sortBy { it.first }

        val result = ArrayList<HourlyColumn>(points.size)
        var lastCode = hours.first().conditionCode
        for ((idx, p) in points.withIndex()) {
            val (t, marker, h) = p
            val temp = h?.temp ?: interpolatedTemp(points, idx)
            val code = h?.conditionCode ?: lastCode
            lastCode = code
            result.add(
                HourlyColumn(
                    time = if (idx == 0 && marker == 0) context.getString(R.string.now)
                        else WeatherFormat.time(t),
                    date = WeatherFormat.shortDate(t),
                    prob = h?.precipProb?.takeIf { it >= 0 },
                    temp = temp,
                    iconRes = if (h != null) WeatherIcons.iconFor(code, h.isDay) else 0,
                    code = code,
                    marker = marker
                )
            )
        }
        return result
    }

    /** Температура маркера — линейно между соседними часами. */
    private fun interpolatedTemp(
        points: List<Triple<Long, Int, HourlyForecast?>>,
        idx: Int
    ): Double {
        val t = points[idx].first
        var before: HourlyForecast? = null
        var after: HourlyForecast? = null
        for (i in idx - 1 downTo 0) {
            val h = points[i].third
            if (h != null) { before = h; break }
        }
        for (i in idx + 1 until points.size) {
            val h = points[i].third
            if (h != null) { after = h; break }
        }
        if (before == null && after == null) return 0.0
        if (before == null) return after!!.temp
        if (after == null) return before.temp
        val span = (after.time - before.time).toDouble()
        if (span <= 0.0) return before.temp
        val k = (t - before.time) / span
        return before.temp + (after.temp - before.temp) * k
    }

    inner class PageHolder(val binding: PageCityBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val tilesAdapter = DetailTilesAdapter(context)

        fun bind(city: City, data: WeatherData?) {
            val b = binding
            b.textCity.text = city.name
            if (city.region != null) {
                b.textRegion.visibility = View.VISIBLE
                b.textRegion.text = city.region
            } else {
                b.textRegion.visibility = View.GONE
            }

            if (data == null) {
                b.textBigTemp.text = WeatherFormat.DASH
                b.textCondition.text = context.getString(R.string.no_data)
                b.textFeelsLike.text = ""
                b.textUpdated.text = context.getString(R.string.loading)
                b.miniWind.text = WeatherFormat.DASH
                b.miniHumidity.text = WeatherFormat.DASH
                b.miniPressure.text = WeatherFormat.DASH
                b.miniFeels.text = WeatherFormat.DASH
                b.sceneView.setWeather(3200, true, 0.0, 0)
                b.hourlyStrip.setData(emptyList())
                b.dailyStrip.setData(emptyList())
                b.sunArcView.setSunTimes(0L, 0L)
                tilesAdapter.submit(null)
                bindAstronomy(null)
                b.textAsOf.text = ""
                return
            }

            val day = isDayNow(data)
            b.textBigTemp.text = WeatherFormat.temp(context, data.temperature)
            b.textCondition.text = data.description
            b.textFeelsLike.text = context.getString(R.string.feels_like) + " " +
                WeatherFormat.temp(context, data.feelsLike)
            b.textUpdated.text = WeatherFormat.updatedAgo(
                context,
                if (data.observationTime > 0) data.observationTime else System.currentTimeMillis()
            )
            b.miniWind.text = WeatherFormat.windCompact(context, data.windSpeed, data.windDirection)
            b.miniHumidity.text = WeatherFormat.percent(data.humidity)
            b.miniPressure.text = WeatherFormat.pressure(context, data.pressure)
            b.miniFeels.text = WeatherFormat.temp(context, data.feelsLike)
            b.sceneView.setWeather(data.conditionCode, day, data.windSpeed, data.windDirection)

            b.hourlyStrip.setData(buildHourlyColumns(data))
            b.dailyStrip.setData(data.dailyForecast.map { d ->
                DailyColumn(
                    day = WeatherFormat.dayLabel(context, d.date),
                    date = WeatherFormat.shortDate(d.date),
                    desc = WeatherIcons.conditionName(context, d.conditionCode),
                    iconRes = WeatherIcons.iconFor(d.conditionCode, true),
                    max = d.tempMax,
                    min = d.tempMin,
                    prob = if (d.precipProb >= 0) d.precipProb else null
                )
            })

            tilesAdapter.submit(data)
            bindAstronomy(data)
            val asOfTime = if (data.observationTime > 0) data.observationTime
                else System.currentTimeMillis()
            b.textAsOf.text = context.getString(
                R.string.as_of,
                WeatherFormat.shortDate(asOfTime) + ", " + WeatherFormat.time(asOfTime)
            )
        }

        private fun bindAstronomy(data: WeatherData?) {
            val b = binding
            if (data == null) {
                b.textSunrise.text = WeatherFormat.DASH
                b.textSunset.text = WeatherFormat.DASH
                b.textDayLength.text = ""
                b.textMoonPhase.text = WeatherFormat.moonPhaseName(context)
                b.moonView.setPhase(WeatherFormat.moonIllumination(), waxing = true)
                b.sunArcView.setSunTimes(0L, 0L)
                return
            }
            b.textSunrise.text = context.getString(R.string.sunrise) + " " +
                WeatherFormat.time(data.sunrise)
            b.textSunset.text = context.getString(R.string.sunset) + " " +
                WeatherFormat.time(data.sunset)
            b.textDayLength.text = context.getString(R.string.day_length) + ": " +
                WeatherFormat.dayLength(context, data.sunrise, data.sunset)
            b.textMoonPhase.text = WeatherFormat.moonPhaseName(context)
            val phaseIndex = WeatherFormat.moonPhaseIndex()
            b.moonView.setPhase(WeatherFormat.moonIllumination(), waxing = phaseIndex in 1..3)
            b.sunArcView.setSunTimes(data.sunrise, data.sunset)
        }

        /** День сейчас: по восходу/закату, иначе по почасовому флагу, иначе по часам. */
        private fun isDayNow(data: WeatherData): Boolean {
            val now = System.currentTimeMillis()
            if (data.sunrise > 0 && data.sunset > data.sunrise) {
                return now in data.sunrise..data.sunset
            }
            data.hourlyForecast.firstOrNull()?.let { return it.isDay }
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour in 7..20
        }
    }
}
