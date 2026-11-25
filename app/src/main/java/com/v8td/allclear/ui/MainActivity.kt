package com.v8td.allclear.ui

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.v8td.allclear.R
import com.v8td.allclear.data.City
import com.v8td.allclear.data.CityStore
import com.v8td.allclear.data.WeatherData
import com.v8td.allclear.data.WeatherRepository
import com.v8td.allclear.databinding.ActivityMainBinding
import com.v8td.allclear.util.AppSettings
import com.v8td.allclear.util.LocationHelper
import com.v8td.allclear.worker.WorkScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Главный экран: страницы городов (свайп), потянуть-обновить, живая сцена.
 * Первый запуск: разрешение геолокации → координаты → город → погода.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cityStore: CityStore
    private lateinit var repository: WeatherRepository
    private lateinit var locationHelper: LocationHelper
    private lateinit var settings: AppSettings
    private lateinit var adapter: CityPagerAdapter

    private var resumed = false
    private val loadJobs = mutableMapOf<String, Job>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locateAndLoad()
        } else {
            showHint(getString(R.string.location_permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cityStore = CityStore(this)
        repository = WeatherRepository(this)
        locationHelper = LocationHelper(this)
        settings = AppSettings(this)

        adapter = CityPagerAdapter(
            context = this,
            onScrollStateChanged = { atTop ->
                // Потянуть-обновить работает только когда страница в самом верху
                binding.swipeRefresh.isEnabled = atTop
            }
        )
        binding.cityPager.adapter = adapter
        binding.cityPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setActivePage(position, resumed)
                updateIndicator(position)
                loadWeatherForPage(position)
                // Предзагрузка соседней страницы — свайп будет мгновенным
                loadWeatherForPage(position + 1)
                loadWeatherForPage(position - 1)
            }
        })

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent_blue)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.card_background)
        )
        binding.swipeRefresh.setOnRefreshListener {
            refreshCurrent(force = true)
        }

        binding.btnRefresh.setOnClickListener { refreshCurrent(force = true) }
        binding.btnMenu.setOnClickListener { view ->
            PopupMenu(this, view).apply {
                menu.add(0, 1, 0, R.string.refresh)
                menu.add(0, 2, 1, R.string.settings)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { refreshCurrent(force = true); true }
                        2 -> {
                            Toast.makeText(this@MainActivity, R.string.settings, Toast.LENGTH_SHORT).show()
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        // Фоновые обновления по расписанию
        WorkScheduler.schedule(this)

        lifecycleScope.launch {
            val cities = cityStore.load()
            if (cities.isNotEmpty()) {
                showCities(cities)
                loadWeatherForPage(binding.cityPager.currentItem)
            } else if (settings.autoLocationEnabled) {
                if (locationHelper.hasPermission()) {
                    locateAndLoad()
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            } else {
                showHint(getString(R.string.location_unavailable))
            }
        }
    }

    /** Определение координат, названия города и первая загрузка. */
    private fun locateAndLoad() {
        showHint(getString(R.string.loading))
        lifecycleScope.launch {
            val coords = locationHelper.obtainAndSaveLocation()
            if (coords == null) {
                showHint(getString(R.string.location_unavailable))
                return@launch
            }
            val cityName = locationHelper.reverseGeocode(coords.first, coords.second)
                ?: getString(R.string.current_location)
            cityStore.upsertAutoCity(cityName, null, coords.first, coords.second)
            showCities(cityStore.getAll())
            loadWeatherForPage(0)
        }
    }

    private fun showCities(cities: List<City>) {
        binding.emptyHint.visibility = View.GONE
        adapter.submitCities(cities)
        buildIndicator(cities.size)
        adapter.setActivePage(binding.cityPager.currentItem, resumed)
    }

    private fun showHint(text: String) {
        binding.emptyHint.text = text
        binding.emptyHint.visibility = View.VISIBLE
    }

    /** Загрузка погоды для страницы (кэш отдаётся мгновенно, сеть при устаревании). */
    private fun loadWeatherForPage(position: Int, force: Boolean = false) {
        val city = adapter.getCity(position) ?: return
        if (loadJobs[city.id]?.isActive == true && !force) return
        loadJobs[city.id] = lifecycleScope.launch {
            val result = repository.getWeather(city.lat, city.lon, forceRefresh = force)
            val data: WeatherData? = result.getOrNull()
            adapter.updateWeather(city.id, data)
            if (position == binding.cityPager.currentItem) {
                binding.swipeRefresh.isRefreshing = false
                if (data == null) {
                    Toast.makeText(this@MainActivity, R.string.network_error, Toast.LENGTH_SHORT).show()
                } else if (repository.getLastError() != null) {
                    Toast.makeText(this@MainActivity, R.string.stale_data_notice, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshCurrent(force: Boolean) {
        binding.swipeRefresh.isRefreshing = true
        val position = binding.cityPager.currentItem
        if (adapter.getCity(position) == null) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        loadWeatherForPage(position, force)
    }

    // --- Индикатор точек ---

    private fun buildIndicator(count: Int) {
        binding.pageIndicator.removeAllViews()
        if (count <= 1) return
        val size = (6 * resources.displayMetrics.density).toInt()
        val margin = (3 * resources.displayMetrics.density).toInt()
        repeat(count) {
            val dot = View(this)
            val params = android.widget.LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            dot.layoutParams = params
            dot.setBackgroundResource(R.drawable.bg_tile)
            binding.pageIndicator.addView(dot)
        }
        updateIndicator(binding.cityPager.currentItem)
    }

    private fun updateIndicator(active: Int) {
        for (i in 0 until binding.pageIndicator.childCount) {
            binding.pageIndicator.getChildAt(i).alpha = if (i == active) 1f else 0.35f
        }
    }

    // --- Жизненный цикл: анимация сцены только на видимой странице ---

    override fun onResume() {
        super.onResume()
        resumed = true
        adapter.setActivePage(binding.cityPager.currentItem, true)
        // Данные могли обновиться воркером, пока приложение спало
        loadWeatherForPage(binding.cityPager.currentItem)
    }

    override fun onPause() {
        resumed = false
        adapter.setActivePage(binding.cityPager.currentItem, false)
        super.onPause()
    }
}
