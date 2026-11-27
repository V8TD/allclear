package com.v8td.allclear.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v8td.allclear.R
import com.v8td.allclear.data.City
import com.v8td.allclear.data.WeatherData
import com.v8td.allclear.databinding.PageCityBinding
import com.v8td.allclear.util.WeatherFormat
import java.util.Calendar

/**
 * Страницы городов для ViewPager2.
 * Каждая страница: живая сцена + шапка + широкие карточки прогнозов и деталей.
 *
 * Исправлено: убран слушатель скролла для swipe-refresh —
 * NestedScrollView协调ирует жесты со SwipeRefreshLayout сам.
 */
class CityPagerAdapter(
    private val context: Context
) : RecyclerView.Adapter<CityPagerAdapter.PageHolder>() {

    private val cities = mutableListOf<City>()
    private val weatherMap = mutableMapOf<String, WeatherData?>()
    private val holders = mutableMapOf<Int, PageHolder>()
    private var activePosition = 0
    private var resumed = false

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

    /** Какая страница видима и активно ли приложение — управляет анимацией сцен. */
    fun setActivePage(position: Int, isResumed: Boolean) {
        activePosition = position
        resumed = isResumed
        holders.values.forEach { holder ->
            val pos = holder.bindingAdapterPosition
            holder.binding.sceneView.setRunning(pos == position && isResumed)
        }
    }

    override fun getItemCount(): Int = cities.size

    override fun getItemId(position: Int): Long = cities[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = PageCityBinding.inflate(LayoutInflater.from(context), parent, false)
        val holder = PageHolder(binding)

        binding.hourlyList.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.hourlyList.adapter = holder.hourlyAdapter

        binding.dailyList.layoutManager = LinearLayoutManager(context)
        binding.dailyList.adapter = holder.dailyAdapter

        binding.detailsGrid.layoutManager = GridLayoutManager(context, 2)
        binding.detailsGrid.adapter = holder.tilesAdapter

        return holder
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        holders[holder.bindingAdapterPosition] = holder
        holder.binding.sceneView.setRunning(
            holder.bindingAdapterPosition == activePosition && resumed
        )
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        holders.remove(holder.bindingAdapterPosition)
        holder.binding.sceneView.setRunning(false)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val city = cities[position]
        val data = weatherMap[city.id]
        holder.bind(city, data)
        holders[position] = holder
        holder.binding.sceneView.setRunning(position == activePosition && resumed)
    }

    inner class PageHolder(val binding: PageCityBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val hourlyAdapter = HourlyAdapter(context)
        val dailyAdapter = DailyAdapter(context)
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
                hourlyAdapter.submit(emptyList())
                dailyAdapter.submit(emptyList())
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

            hourlyAdapter.submit(data.hourlyForecast)
            dailyAdapter.submit(data.dailyForecast)
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
