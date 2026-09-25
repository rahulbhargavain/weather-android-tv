package com.bhimtal.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bhimtal.dashboard.heuristic.HeuristicInput
import com.bhimtal.dashboard.heuristic.RainHeuristic
import com.bhimtal.dashboard.heuristic.SkyCondition
import org.json.JSONObject
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Bhimtal Dashboard
 *
 * A single-screen Android TV dashboard showing:
 *  - A regional radar image, from RainViewer (free, no API key needed)
 *  - Live conditions from your personal weather station (PWS API), an
 *    animated sun/cloud/rain icon, and today's rain-chance from Open-Meteo
 *    (free, no API key needed), combined by RainHeuristic (see that file)
 *    into a single displayed condition and probability.
 *  - Headlines from a list of RSS sources (edit NEWS_SOURCES below to add,
 *    remove, or reorder). Each card is D-pad focusable/clickable -- press
 *    select on a card to expand it from 4 headlines to 10.
 *
 * CONFIGURATION: the PWS station ID, PWS API key, and the latitude/
 * longitude to center the radar/forecast on are NOT in this file. They are
 * read from BuildConfig fields generated from local.properties, which is
 * gitignored -- see README.md, "Configuration" section, before building.
 */
class MainActivity : AppCompatActivity() {

    data class NewsSource(val displayName: String, val feedUrl: String)

    companion object {
        private const val TAG = "BhimtalDashboard"

        private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val HEADLINES_COLLAPSED = 4
        private const val HEADLINES_EXPANDED = 10

        // RainViewer tiles: zoom levels above 7 are not available on the free tier.
        private const val RADAR_ZOOM = 6
        private const val RADAR_TILE_PX = 256
        private const val GRID_COLUMNS = 5

        // RainViewer tiles are transparent where there's no precipitation and
        // colored (non-transparent) where there is -- so "is it raining nearby"
        // reduces to "is any pixel near the station marker non-transparent
        // above a noise floor". At RADAR_ZOOM=6, each tile is ~620km across
        // RADAR_TILE_PX pixels, so a 15px sampling radius is roughly a 35km
        // radius around the station -- a "nearby storm cell" scale, not a
        // "somewhere in the region" scale.
        private const val RADAR_ECHO_SAMPLE_RADIUS_PX = 15
        private const val RADAR_ECHO_ALPHA_THRESHOLD = 40 // out of 255; filters faint rendering/compression noise

        // RainHeuristic's rapid-cooling rule is a rate calibrated to one
        // REFRESH_INTERVAL_MS. A previous reading older than this (a failed
        // fetch in between, or the app coming back from the background) would
        // stretch the window and is dropped instead of compared against.
        private const val MAX_PREVIOUS_TEMP_AGE_MS = REFRESH_INTERVAL_MS * 3 / 2

        // Edit this list to add, remove, or reorder news sources.
        private val NEWS_SOURCES = listOf(
            NewsSource("The New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml"),
            NewsSource("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
            NewsSource("Science & Environment", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml"),
            NewsSource("Business (Global)", "https://feeds.bbci.co.uk/news/business/rss.xml"),
            NewsSource("Nature", "https://www.nature.com/nature.rss")
        )
    }

    private lateinit var weatherAnimView: WeatherAnimationView
    private lateinit var tempText: TextView
    private lateinit var stationText: TextView
    private lateinit var weatherDetailText: TextView
    private lateinit var rainChanceText: TextView
    private lateinit var lastUpdatedText: TextView
    private lateinit var refreshButton: Button
    private lateinit var radarImageView: ImageView
    private lateinit var newsGridContainer: GridLayout

    // Parallel arrays, indexed the same as NEWS_SOURCES.
    private val newsBodyViews = mutableListOf<TextView>()
    private val newsHintViews = mutableListOf<TextView>()
    private val fullHeadlines = MutableList(NEWS_SOURCES.size) { listOf<String>() }
    private val expanded = BooleanArray(NEWS_SOURCES.size)

    // Tracks the station's own cumulative precipitation and temperature
    // between refreshes so RainHeuristic can detect CHANGE, not just a
    // snapshot value -- NEW rain since last time (not "some rain today",
    // which would stay true all day after the first drop), and a rapid
    // temperature DROP (not just "it's cool", which is true every night).
    // This is the state a pure function can't hold itself -- kept here, at
    // the Activity boundary, not inside RainHeuristic.
    private var previousStationPrecipTotalMm: Double? = null
    private var previousStationTempC: Double? = null
    private var previousStationTempAtMs: Long = 0L

    // Only one refresh runs at a time: the refresh button and the periodic
    // runnable would otherwise race on the previous* fields above.
    private val refreshInProgress = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        weatherAnimView = findViewById(R.id.weatherAnimView)
        tempText = findViewById(R.id.tempText)
        stationText = findViewById(R.id.stationText)
        weatherDetailText = findViewById(R.id.weatherDetailText)
        rainChanceText = findViewById(R.id.rainChanceText)
        lastUpdatedText = findViewById(R.id.lastUpdatedText)
        refreshButton = findViewById(R.id.refreshButton)
        radarImageView = findViewById(R.id.radarImageView)
        newsGridContainer = findViewById(R.id.newsGridContainer)

        buildNewsGrid()

        refreshButton.setOnClickListener { refreshAll() }
    }

    // Refresh only while the dashboard is visible; going to the background
    // (Home, screensaver, another app) stops the periodic network fetches.
    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A focus-aware background: highlights with an accent border when a card has D-pad focus. */
    private fun cardBackground(): StateListDrawable {
        val normal = GradientDrawable().apply {
            setColor(Color.parseColor("#232823"))
            cornerRadius = dp(4).toFloat()
        }
        val focused = GradientDrawable().apply {
            setColor(Color.parseColor("#35443C"))
            cornerRadius = dp(4).toFloat()
            setStroke(dp(3), Color.parseColor("#E8A33D"))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    /** Builds one D-pad-focusable, clickable card per NEWS_SOURCES entry. */
    private fun buildNewsGrid() {
        newsGridContainer.columnCount = GRID_COLUMNS
        val rowCount = (NEWS_SOURCES.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        newsGridContainer.rowCount = rowCount

        for ((index, source) in NEWS_SOURCES.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setGravity(Gravity.FILL)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener {
                    expanded[index] = !expanded[index]
                    renderCard(index)
                }
            }

            val header = TextView(this).apply {
                text = source.displayName
                setTextColor(Color.parseColor("#E8A33D"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            }

            val body = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#F2F1EC"))
                textSize = 11f
                setLineSpacing(dp(2).toFloat(), 1f)
            }

            val hint = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#6FA8DC"))
                textSize = 10f
                setPadding(0, dp(6), 0, 0)
            }

            card.addView(header)
            card.addView(body)
            card.addView(hint)
            newsGridContainer.addView(card)
            newsBodyViews.add(body)
            newsHintViews.add(hint)
        }
    }

    /** Re-renders a single card's text from fullHeadlines[index], respecting expanded[index]. */
    private fun renderCard(index: Int) {
        val all = fullHeadlines[index]
        val count = if (expanded[index]) HEADLINES_EXPANDED else HEADLINES_COLLAPSED
        newsBodyViews[index].text = formatHeadlines(all.take(count))
        newsHintViews[index].text = when {
            all.size <= HEADLINES_COLLAPSED -> ""
            expanded[index] -> "▲ Select to show less"
            else -> "▼ Select for ${minOf(all.size, HEADLINES_EXPANDED) - HEADLINES_COLLAPSED} more"
        }
    }

    /** Kicks off all network fetches on a background thread, then updates the UI. */
    private fun refreshAll() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already in progress, skipping")
            return
        }
        Thread {
            try {
                refreshAllBlocking()
            } finally {
                refreshInProgress.set(false)
            }
        }.start()
    }

    private fun refreshAllBlocking() {
        val weather = fetchWeather()
        val weatherFetchedAtMs = System.currentTimeMillis()
        val forecast = fetchForecast()
        val radarFetch = fetchRadarBitmap()
        val newsResults = NEWS_SOURCES.map { fetchRss(it.feedUrl, HEADLINES_EXPANDED) }

        val previousTempIsFresh = weatherFetchedAtMs - previousStationTempAtMs <= MAX_PREVIOUS_TEMP_AGE_MS
        val heuristicResult = RainHeuristic.evaluate(
            HeuristicInput(
                popPercent = forecast?.popPercent,
                weatherCode = forecast?.weatherCode,
                stationPrecipTotalMm = weather?.precipTotalMm,
                previousStationPrecipTotalMm = previousStationPrecipTotalMm,
                humidityPercent = weather?.humidity,
                stationTempC = weather?.tempC,
                previousStationTempC = if (previousTempIsFresh) previousStationTempC else null,
                radarEchoNearStation = radarFetch?.echoNearStation,
            ),
        )
        previousStationPrecipTotalMm = weather?.precipTotalMm ?: previousStationPrecipTotalMm
        // Unlike the precipitation total (any increase means rain fell at some
        // point), the temperature comparison is a rate, so a failed fetch
        // clears the previous reading rather than keeping an old one around.
        previousStationTempC = weather?.tempC
        previousStationTempAtMs = weatherFetchedAtMs
        Log.d(TAG, "Rain heuristic: ${heuristicResult.condition}, reasoning: ${heuristicResult.reasoning}")

        handler.post {
            if (isDestroyed) return@post
            updateWeatherUI(weather)
            weatherAnimView.setCondition(toAnimationCondition(heuristicResult.condition))
            rainChanceText.text = heuristicResult.adjustedPopPercent?.let { "Rain chance: $it%" }
                ?: "Rain chance: --"
            radarFetch?.bitmap?.let { radarImageView.setImageBitmap(it) }

            for (i in NEWS_SOURCES.indices) {
                fullHeadlines[i] = newsResults[i]
                renderCard(i)
            }

            lastUpdatedText.text = "Updated " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        }
    }

    private fun toAnimationCondition(condition: SkyCondition): WeatherAnimationView.Condition = when (condition) {
        SkyCondition.SUNNY -> WeatherAnimationView.Condition.SUNNY
        SkyCondition.CLOUDY -> WeatherAnimationView.Condition.CLOUDY
        SkyCondition.RAINY -> WeatherAnimationView.Condition.RAINY
    }

    private fun formatHeadlines(headlines: List<String>): String {
        if (headlines.isEmpty()) return "No headlines available"
        return headlines.joinToString("\n\n") { "•  $it" }
    }

    // ---------------------------------------------------------------------
    // PWS current conditions
    // ---------------------------------------------------------------------

    data class WeatherData(
        val tempC: Double?,
        val humidity: Double?,
        val windSpeedKmh: Double?,
        val windDirDeg: Double?,
        val precipTotalMm: Double?,
    )

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        val value = this.optDouble(key)
        return if (value.isNaN()) null else value
    }

    private fun fetchWeather(): WeatherData? {
        return try {
            val url = URL(
                "https://api.weather.com/v2/pws/observations/current" +
                    "?stationId=${BuildConfig.PWS_STATION_ID}&format=json&units=m&apiKey=${BuildConfig.PWS_API_KEY}",
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(responseText)
            val observations = json.getJSONArray("observations")
            if (observations.length() == 0) return null

            val obs = observations.getJSONObject(0)
            val metric = obs.getJSONObject("metric")

            WeatherData(
                tempC = metric.optDoubleOrNull("temp"),
                humidity = obs.optDoubleOrNull("humidity"),
                windSpeedKmh = metric.optDoubleOrNull("windSpeed"),
                windDirDeg = obs.optDoubleOrNull("winddir"),
                precipTotalMm = metric.optDoubleOrNull("precipTotal"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            null
        }
    }

    private fun degreesToCompass(deg: Double): String {
        val directions = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
        val index = (((deg / 22.5) + 0.5).toInt()) % 16
        return directions[if (index < 0) index + 16 else index]
    }

    private fun updateWeatherUI(weather: WeatherData?) {
        if (weather == null) {
            tempText.text = "--°"
            weatherDetailText.text = "Weather data unavailable"
            stationText.text = "Station ${BuildConfig.PWS_STATION_ID}"
            return
        }

        tempText.text = weather.tempC?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "--°"
        stationText.text = "Station ${BuildConfig.PWS_STATION_ID}"

        val details = StringBuilder()
        weather.humidity?.let { details.append("Humidity ${it.toInt()}%") }
        weather.windSpeedKmh?.let {
            if (details.isNotEmpty()) details.append("    ")
            val dir = weather.windDirDeg?.let { d -> degreesToCompass(d) } ?: ""
            details.append("Wind ${it.toInt()} km/h $dir")
        }
        weather.precipTotalMm?.let {
            if (details.isNotEmpty()) details.append("    ")
            details.append("Rain ${String.format(Locale.getDefault(), "%.1f", it)} mm")
        }
        weatherDetailText.text = details.toString()
    }

    // ---------------------------------------------------------------------
    // Open-Meteo forecast (rain chance + condition code, feeds RainHeuristic)
    // ---------------------------------------------------------------------

    data class ForecastData(val popPercent: Int?, val weatherCode: Int?)

    private fun fetchForecast(): ForecastData? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${BuildConfig.LATITUDE}&longitude=${BuildConfig.LONGITUDE}" +
                    "&hourly=precipitation_probability,weathercode&timezone=auto&forecast_days=1" +
                    "&timeformat=unixtime",
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(responseText)
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val pops = hourly.getJSONArray("precipitation_probability")
            val codes = hourly.getJSONArray("weathercode")

            // Times come back as Unix seconds (timeformat=unixtime), so the
            // current hour is found without depending on the device's or the
            // station's time zone. Each entry covers [time, time + 1h).
            val nowSec = System.currentTimeMillis() / 1000
            val index = (0 until times.length()).firstOrNull { i ->
                val start = times.getLong(i)
                nowSec >= start && nowSec < start + 3600
            }
            if (index == null) {
                Log.w(TAG, "Forecast has no entry for the current hour")
                return null
            }

            val pop = pops.optInt(index, -1)
            val code = codes.optInt(index, -1)
            ForecastData(
                popPercent = if (pop >= 0) pop else null,
                weatherCode = if (code >= 0) code else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Forecast fetch failed", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // RainViewer radar image
    // ---------------------------------------------------------------------

    data class RadarFetchResult(val bitmap: Bitmap?, val echoNearStation: Boolean)

    /** True if any sampled pixel's alpha exceeds the noise floor -- i.e. radar shows real echo nearby. */
    private fun hasRadarEchoNear(bitmap: Bitmap, centerX: Int, centerY: Int): Boolean {
        val left = (centerX - RADAR_ECHO_SAMPLE_RADIUS_PX).coerceAtLeast(0)
        val right = (centerX + RADAR_ECHO_SAMPLE_RADIUS_PX).coerceAtMost(bitmap.width - 1)
        val top = (centerY - RADAR_ECHO_SAMPLE_RADIUS_PX).coerceAtLeast(0)
        val bottom = (centerY + RADAR_ECHO_SAMPLE_RADIUS_PX).coerceAtMost(bitmap.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                if (Color.alpha(bitmap.getPixel(x, y)) > RADAR_ECHO_ALPHA_THRESHOLD) return true
            }
        }
        return false
    }

    /** Downloads a 3x3 grid of RainViewer tiles around BuildConfig.LATITUDE/LONGITUDE and stitches them. */
    private fun fetchRadarBitmap(): RadarFetchResult? {
        return try {
            val apiUrl = URL("https://api.rainviewer.com/public/weather-maps.json")
            val apiConn = apiUrl.openConnection() as HttpURLConnection
            apiConn.connectTimeout = 10000
            apiConn.readTimeout = 10000
            val apiResponse = apiConn.inputStream.bufferedReader().use { it.readText() }
            apiConn.disconnect()

            val apiJson = JSONObject(apiResponse)
            val host = apiJson.getString("host")
            val pastFrames = apiJson.getJSONObject("radar").getJSONArray("past")
            if (pastFrames.length() == 0) return null
            val latestFrame = pastFrames.getJSONObject(pastFrames.length() - 1)
            val path = latestFrame.getString("path")

            val n = 1 shl RADAR_ZOOM
            val latRad = Math.toRadians(BuildConfig.LATITUDE)
            val xTileExact = (BuildConfig.LONGITUDE + 180.0) / 360.0 * n
            val yTileExact = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
            val centerX = xTileExact.toInt()
            val centerY = yTileExact.toInt()

            val gridSize = 3
            val stitched = Bitmap.createBitmap(
                RADAR_TILE_PX * gridSize,
                RADAR_TILE_PX * gridSize,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(stitched)

            for (dx in -1..1) {
                for (dy in -1..1) {
                    val tx = centerX + dx
                    val ty = centerY + dy
                    try {
                        val tileUrl = URL("$host$path/$RADAR_TILE_PX/$RADAR_ZOOM/$tx/$ty/2/1_1.png")
                        val tileConn = tileUrl.openConnection() as HttpURLConnection
                        tileConn.connectTimeout = 10000
                        tileConn.readTimeout = 10000
                        val bmp = BitmapFactory.decodeStream(tileConn.inputStream)
                        tileConn.disconnect()
                        if (bmp != null) {
                            canvas.drawBitmap(
                                bmp,
                                ((dx + 1) * RADAR_TILE_PX).toFloat(),
                                ((dy + 1) * RADAR_TILE_PX).toFloat(),
                                null,
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Radar tile fetch failed for ($tx,$ty)", e)
                    }
                }
            }

            // Mark the station's location on the stitched image.
            val markerX = RADAR_TILE_PX + (xTileExact - centerX) * RADAR_TILE_PX
            val markerY = RADAR_TILE_PX + (yTileExact - centerY) * RADAR_TILE_PX
            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E8A33D")
                style = Paint.Style.FILL
            }
            val markerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }

            // Sample for echo BEFORE drawing the marker dot on top -- otherwise
            // the marker's own opaque pixels would trivially count as "echo".
            val echoNearStation = hasRadarEchoNear(stitched, markerX.toInt(), markerY.toInt())

            canvas.drawCircle(markerX.toFloat(), markerY.toFloat(), 9f, markerPaint)
            canvas.drawCircle(markerX.toFloat(), markerY.toFloat(), 9f, markerOutline)

            RadarFetchResult(stitched, echoNearStation)
        } catch (e: Exception) {
            Log.e(TAG, "Radar fetch failed", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // RSS news
    // ---------------------------------------------------------------------

    /** Fetches an RSS feed and returns up to maxItems item titles. */
    private fun fetchRss(feedUrl: String, maxItems: Int): List<String> {
        return try {
            val url = URL(feedUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android TV) BhimtalDashboard/1.0",
            )

            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = conn.inputStream.use { builder.parse(it) }
            conn.disconnect()

            val itemNodes = doc.getElementsByTagName("item")
            val count = minOf(itemNodes.length, maxItems)
            val headlines = mutableListOf<String>()
            for (i in 0 until count) {
                val item = itemNodes.item(i) as Element
                val titleNodes = item.getElementsByTagName("title")
                if (titleNodes.length > 0) {
                    headlines.add(titleNodes.item(0).textContent.trim())
                }
            }
            headlines
        } catch (e: Exception) {
            Log.e(TAG, "RSS fetch failed for $feedUrl", e)
            emptyList()
        }
    }
}
