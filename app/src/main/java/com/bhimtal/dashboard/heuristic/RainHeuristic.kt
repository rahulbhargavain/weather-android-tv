package com.bhimtal.dashboard.heuristic

import kotlin.math.ln

/**
 * RainHeuristic -- combines the Open-Meteo forecast with your PWS station's
 * own readings (and, since the second revision, RainViewer radar) to decide
 * what to actually show on screen (sky icon + displayed rain chance).
 *
 * WHY THIS EXISTS: the previous version used Open-Meteo's weather code for
 * the animation and Open-Meteo's rain-probability number for the text, but
 * never cross-checked them against each other or against the station's own
 * readings, even though the station's current precipitation total was
 * already being fetched. Two concrete gaps that fixed:
 *
 *  1. A clear/sunny weather code with a meaningful rain probability (e.g.
 *     code 0 "clear" but 45% chance of rain later this hour) still drew a
 *     full sun icon right next to text saying "Rain chance: 45%" -- visibly
 *     inconsistent. Now a probability at or above BORDERLINE_POP_THRESHOLD
 *     always shows at least a cloud, regardless of the raw code.
 *  2. The station's own precipitation reading was fetched but never used to
 *     influence the display. If the station's cumulative precipitation
 *     total has actually gone up since the last refresh, that is direct
 *     ground truth that it is raining right now at your exact location --
 *     stronger evidence than a regional forecast model, and now overrides
 *     it.
 *
 * SECOND REVISION added two more signals, from a station that only logs
 * temperature and humidity (no pressure, no dew point sensor):
 *
 *  3. RADAR ECHO NEAR THE STATION. MainActivity samples the RainViewer
 *     tiles around your station's exact pixel location (see
 *     fetchRadarEchoNearStation): a colored (non-transparent) pixel near
 *     the station means RainViewer's radar is detecting echo
 *     right over your location, independent of any forecast model. HONEST
 *     CAVEAT: radar detects precipitation aloft, which can evaporate before
 *     reaching the ground (virga) -- common in drier air, and plausible in
 *     Himalayan-foothill terrain where the radar beam may also be sampling
 *     a different altitude than ground level depending on terrain blocking.
 *     Treat this as "something is happening nearby, worth a glance outside",
 *     not "it is definitely raining at ground level".
 *
 *  4. RAPID COOLING WITH A NARROW DEW POINT SPREAD. As air cools toward its
 *     dew point, it approaches saturation -- real physics, and computable
 *     from temperature + humidity alone via the Magnus formula (see
 *     dewPointC() below), no extra sensor needed. But this alone is a WEAK
 *     signal: ordinary evening/night cooling does the same thing every
 *     single day without producing rain. What actually distinguishes an
 *     approaching convective cell (a gust front / outflow boundary, the
 *     rush of cool air a storm pushes ahead of itself) from routine diurnal
 *     cooling is the RATE: a sharp drop over one refresh cycle is a very
 *     different signal from gradual cooling over hours. So this rule keys
 *     on temperature change SINCE THE LAST REFRESH, not on the absolute
 *     temperature or humidity level. RAPID_COOLING_THRESHOLD_C is
 *     calibrated against REFRESH_INTERVAL_MS (15 minutes in MainActivity)
 *     -- if you change the refresh interval, revisit this constant, since
 *     "how much cooling in how much time" is the entire signal.
 *
 * DESIGN: an ordered list of pure rules, evaluated top to bottom, first
 * match wins -- same "rule table" shape used elsewhere for this kind of
 * decision logic, chosen for the same reason: each rule is independently
 * unit-testable and the priority order is explicit and readable as data,
 * not buried in nested if/else.
 *
 * HONEST LIMITS, stated up front so nobody mistakes this for more than it
 * is: this is a same-hour nowcasting nudge, not a forecasting model. The
 * station-ground-truth rule is genuinely reliable (a rising rain gauge
 * total is not ambiguous). The radar-echo and rapid-cooling rules are
 * reasonable, physically-motivated heuristics, but NEITHER has been
 * validated against your station's actual history yet -- see
 * backtest/backtest_rain_heuristic.py, and if you have real skill-score
 * data, the threshold constants below (and in that script) are the place
 * to tune it.
 */

enum class SkyCondition { SUNNY, CLOUDY, RAINY }

/** Everything the heuristic needs, gathered from wherever it came from. */
data class HeuristicInput(
    val popPercent: Int?,
    val weatherCode: Int?,
    val stationPrecipTotalMm: Double?,
    val previousStationPrecipTotalMm: Double?,
    val humidityPercent: Double?,
    val stationTempC: Double? = null,
    val previousStationTempC: Double? = null,
    val radarEchoNearStation: Boolean? = null,
)

data class HeuristicResult(
    val condition: SkyCondition,
    /** May differ from the raw Open-Meteo popPercent when ground truth overrides it. */
    val adjustedPopPercent: Int?,
    /** True only when the station itself shows precipitation accruing right now. */
    val isActivelyRaining: Boolean,
    /** Human-readable justification -- surfaced in logs/tests, not required in the UI. */
    val reasoning: String,
)

private data class Rule(
    val name: String,
    val matches: (HeuristicInput) -> Boolean,
    val resultFor: (HeuristicInput) -> HeuristicResult,
)

object RainHeuristic {
    // Tuning constants -- see the "HONEST LIMITS" note above before changing these.
    const val ACTIVE_PRECIP_POP_FLOOR = 90
    const val HIGH_POP_THRESHOLD = 60
    const val BORDERLINE_POP_THRESHOLD = 30
    const val HIGH_HUMIDITY_THRESHOLD = 90.0
    private const val DEFAULT_POP_FOR_ACTIVE_CODE = 70
    private const val RADAR_ECHO_POP_FLOOR = 70

    // Tied to MainActivity.REFRESH_INTERVAL_MS (15 minutes) -- this is a rate,
    // not an absolute threshold. Revisit if the refresh interval changes.
    const val RAPID_COOLING_THRESHOLD_C = 1.5
    // "Near saturation": how close temperature has to be to the dew point for
    // the rapid-cooling signal to count as meaningful rather than just "it
    // got a bit cooler and a bit more humid, as every evening does".
    const val NARROW_DEWPOINT_SPREAD_C = 3.0

    /**
     * Dew point via the Magnus-Tetens approximation, accurate to roughly
     * +/-0.4C over ordinary temperature/humidity ranges -- good enough for a
     * nowcasting nudge, not a metrology-grade instrument. Returns null for
     * humidity <= 0 (ln undefined) or > 100 (not a physically valid reading).
     */
    fun dewPointC(tempC: Double, humidityPercent: Double): Double? {
        if (humidityPercent <= 0.0 || humidityPercent > 100.0) return null
        val a = 17.27
        val b = 237.7
        val alpha = ln(humidityPercent / 100.0) + (a * tempC) / (b + tempC)
        return (b * alpha) / (a - alpha)
    }

    /** How far current temperature is above its dew point -- narrower means closer to saturation. */
    fun dewPointSpreadC(tempC: Double, humidityPercent: Double): Double? =
        dewPointC(tempC, humidityPercent)?.let { tempC - it }

    // WMO codes: 51-67 drizzle/rain, 71-77 snow, 80-82 rain showers,
    // 85-86 snow showers, 95-99 thunderstorm. Snow counts as precipitation
    // too -- there's no snow icon, so it's drawn as RAINY rather than
    // falling through to SUNNY.
    private fun isActivePrecipCode(code: Int?) =
        code != null && (code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99)

    private fun isFogCode(code: Int?) = code != null && code in 45..48
    private fun isCloudCode(code: Int?) = code != null && code in 1..3

    private val rules: List<Rule> = listOf(
        Rule(
            name = "station_ground_truth",
            matches = {
                it.stationPrecipTotalMm != null && it.previousStationPrecipTotalMm != null &&
                    it.stationPrecipTotalMm > it.previousStationPrecipTotalMm
            },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = maxOf(it.popPercent ?: 0, ACTIVE_PRECIP_POP_FLOOR),
                    isActivelyRaining = true,
                    reasoning = "Station recorded new precipitation since the last refresh -- " +
                        "ground truth overrides the forecast model.",
                )
            },
        ),
        Rule(
            name = "radar_echo_near_station",
            // Placed above the forecast-model rules: radar seeing echo right over
            // your location is a more direct, local signal than a regional model's
            // hourly code -- but below station_ground_truth, since a rain gauge
            // that's already collecting water is more certain than a radar echo
            // that might be virga (evaporating before it reaches the ground).
            matches = { it.radarEchoNearStation == true },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = maxOf(it.popPercent ?: 0, RADAR_ECHO_POP_FLOOR),
                    isActivelyRaining = false,
                    reasoning = "RainViewer radar shows echo near your station's marked location -- " +
                        "may be virga (evaporating before reaching the ground), so treat as " +
                        "'something is happening nearby', not certain ground-level rain.",
                )
            },
        ),
        Rule(
            name = "model_active_precip_code",
            matches = { isActivePrecipCode(it.weatherCode) },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = it.popPercent ?: DEFAULT_POP_FOR_ACTIVE_CODE,
                    isActivelyRaining = false,
                    reasoning = "Forecast model's weather code (${it.weatherCode}) indicates " +
                        "active precipitation this hour.",
                )
            },
        ),
        Rule(
            name = "high_pop",
            matches = { (it.popPercent ?: -1) >= HIGH_POP_THRESHOLD },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = it.popPercent,
                    isActivelyRaining = false,
                    reasoning = "Precipitation probability ${it.popPercent}% is high enough to " +
                        "show rain regardless of the hourly code.",
                )
            },
        ),
        Rule(
            name = "rapid_cooling_narrow_dewpoint_spread",
            // Below the model-driven rules on purpose: this rule exists to catch
            // what the model HASN'T caught yet -- a local, station-observed
            // outflow-boundary signature the regional forecast may not reflect
            // for another refresh cycle or two. If the model already agrees
            // (high_pop, active precip code), those rules fire first regardless.
            matches = {
                val tempDrop = if (it.previousStationTempC != null && it.stationTempC != null) {
                    it.previousStationTempC - it.stationTempC
                } else {
                    null
                }
                val spread = it.stationTempC?.let { t -> it.humidityPercent?.let { h -> dewPointSpreadC(t, h) } }
                tempDrop != null && tempDrop >= RAPID_COOLING_THRESHOLD_C &&
                    spread != null && spread <= NARROW_DEWPOINT_SPREAD_C
            },
            resultFor = {
                val tempDrop = it.previousStationTempC!! - it.stationTempC!!
                val spread = dewPointSpreadC(it.stationTempC, it.humidityPercent!!)
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = maxOf(it.popPercent ?: 0, HIGH_POP_THRESHOLD),
                    isActivelyRaining = false,
                    reasoning = "Temperature dropped %.1fC since the last refresh with only a %.1fC dew ".format(tempDrop, spread) +
                        "point spread -- resembles a gust front/outflow boundary ahead of a convective " +
                        "cell, not routine evening cooling. Unvalidated heuristic -- see backtest script.",
                )
            },
        ),
        Rule(
            name = "borderline_pop_high_humidity",
            matches = {
                val pop = it.popPercent ?: -1
                pop in BORDERLINE_POP_THRESHOLD until HIGH_POP_THRESHOLD &&
                    (it.humidityPercent ?: 0.0) >= HIGH_HUMIDITY_THRESHOLD
            },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.RAINY,
                    adjustedPopPercent = it.popPercent,
                    isActivelyRaining = false,
                    reasoning = "Borderline ${it.popPercent}% chance combined with " +
                        "${it.humidityPercent}% humidity is treated as likely rain.",
                )
            },
        ),
        Rule(
            name = "fog_code",
            matches = { isFogCode(it.weatherCode) },
            resultFor = {
                HeuristicResult(
                    condition = SkyCondition.CLOUDY,
                    adjustedPopPercent = it.popPercent,
                    isActivelyRaining = false,
                    reasoning = "Fog/mist code (${it.weatherCode}).",
                )
            },
        ),
        Rule(
            name = "cloud_code_or_meaningful_pop",
            matches = { isCloudCode(it.weatherCode) || (it.popPercent ?: -1) >= BORDERLINE_POP_THRESHOLD },
            resultFor = {
                val reason = if (isCloudCode(it.weatherCode)) {
                    "Cloudy weather code (${it.weatherCode})."
                } else {
                    "Clear/unknown code but ${it.popPercent}% rain chance is too high to show full sun."
                }
                HeuristicResult(
                    condition = SkyCondition.CLOUDY,
                    adjustedPopPercent = it.popPercent,
                    isActivelyRaining = false,
                    reasoning = reason,
                )
            },
        ),
    )

    private fun defaultResult(input: HeuristicInput) = HeuristicResult(
        condition = SkyCondition.SUNNY,
        adjustedPopPercent = input.popPercent,
        isActivelyRaining = false,
        reasoning = "No rain signal from the station, radar, the model's weather code, or its probability.",
    )

    /** Pure entry point: same input always produces the same output. */
    fun evaluate(input: HeuristicInput): HeuristicResult =
        rules.firstOrNull { it.matches(input) }?.resultFor?.invoke(input) ?: defaultResult(input)
}
