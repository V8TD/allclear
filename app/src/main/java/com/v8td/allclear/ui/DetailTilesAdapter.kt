package com.v8td.allclear.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v8td.allclear.R
import com.v8td.allclear.data.WeatherData
import com.v8td.allclear.databinding.ItemDetailTileBinding
import com.v8td.allclear.util.WeatherFormat

/**
 * Плитки карточки «Подробности» (сетка 2 колонки).
 * Порядок пока фиксированный; перетаскивание и скрытие — Этап 7.
 * Нет данных — прочерк.
 */
class DetailTilesAdapter(private val context: Context) :
    RecyclerView.Adapter<DetailTilesAdapter.Holder>() {

    private data class Tile(val label: String, val value: String, val icon: Int)

    private val tiles = mutableListOf<Tile>()

    fun submit(data: WeatherData?) {
        tiles.clear()
        if (data != null) {
            tiles.add(Tile(
                context.getString(R.string.wind),
                WeatherFormat.windCompact(context, data.windSpeed, data.windDirection) +
                    (data.windGust?.let {
                        " · " + context.getString(R.string.wind_gusts) + " " +
                            WeatherFormat.wind(context, it)
                    } ?: ""),
                R.drawable.ic_weather_fog
            ))
            tiles.add(Tile(
                context.getString(R.string.humidity),
                WeatherFormat.percent(data.humidity),
                R.drawable.ic_weather_rain
            ))
            tiles.add(Tile(
                context.getString(R.string.pressure),
                WeatherFormat.pressure(context, data.pressure),
                R.drawable.ic_weather_cloud
            ))
            tiles.add(Tile(
                context.getString(R.string.precipitation),
                WeatherFormat.percent(data.precipitationProb),
                R.drawable.ic_weather_rain
            ))
            tiles.add(Tile(
                context.getString(R.string.uv_index),
                WeatherFormat.uv(context, data.uvIndex),
                R.drawable.ic_weather_sun
            ))
            tiles.add(Tile(
                context.getString(R.string.visibility),
                WeatherFormat.visibility(context, data.visibility),
                R.drawable.ic_weather_fog
            ))
            tiles.add(Tile(
                context.getString(R.string.dew_point),
                WeatherFormat.dewPoint(context, data.dewPoint),
                R.drawable.ic_weather_rain
            ))
            tiles.add(Tile(
                context.getString(R.string.feels_like),
                WeatherFormat.temp(context, data.feelsLike),
                R.drawable.ic_weather_sun
            ))
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemDetailTileBinding.inflate(LayoutInflater.from(context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = tiles[position]
        holder.binding.tileLabel.text = tile.label
        holder.binding.tileValue.text = tile.value
        holder.binding.tileIcon.setImageResource(tile.icon)
    }

    class Holder(val binding: ItemDetailTileBinding) :
        RecyclerView.ViewHolder(binding.root)
}
