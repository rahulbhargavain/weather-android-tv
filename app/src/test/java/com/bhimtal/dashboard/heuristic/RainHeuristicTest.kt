package com.bhimtal.dashboard.heuristic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RainHeuristic -- pure functions, no Android framework, no
 * mocking, no instrumentation. Structured the same way as the rule-table
 * tests written for the Python quant modules: one data-driven table
 * exercising every rule and its boundary conditions, plus a couple of
 * targeted tests for dispatch ORDER (does the higher-priority rule win when
 * two rules could both match), which a table of independent cases can't
 * verify on its own.
 */
class RainHeuristicTest {

    private data class Case(
        val name: String,
        val input: HeuristicInput,
        val expectedCondition: SkyCondition,
        val expectedActivelyRaining: Boolean = false,
    )

    private fun input(
        popPercent: Int? = null,
        weatherCode: Int? = null,
        stationPrecipTotalMm: Double? = null,
        previousStationPrecipTotalMm: Double? = null,
        humidityPercent: Double? = null,
        stationTempC: Double? = null,
        previousStationTempC: Double? = null,
        radarEchoNearStation: Boolean? = null,
    ) = HeuristicInput(
        popPercent, weatherCode, stationPrecipTotalMm, previousStationPrecipTotalMm, humidityPercent,
        stationTempC, previousStationTempC, radarEchoNearStation,
    )

    private val cases = listOf(
        Case(
            "clear sky, no pop signal at all",
            input(popPercent = 0, weatherCode = 0, humidityPercent = 40.0),
            SkyCondition.SUNNY,
        ),
        Case(
            "station shows new precipitation overrides a clear forecast",
            input(popPercent = 0, weatherCode = 0, stationPrecipTotalMm = 2.4, previousStationPrecipTotalMm = 1.0, humidityPercent = 40.0),
            SkyCondition.RAINY,
            expectedActivelyRaining = true,
        ),
        Case(
            "station precip unchanged since last poll does not trigger ground truth",
            input(popPercent = 5, weatherCode = 0, stationPrecipTotalMm = 2.4, previousStationPrecipTotalMm = 2.4, humidityPercent = 40.0),
            SkyCondition.SUNNY,
        ),
        Case(
            "station precip that DECREASED (counter reset) does not trigger ground truth",
            input(popPercent = 5, weatherCode = 0, stationPrecipTotalMm = 0.0, previousStationPrecipTotalMm = 2.4, humidityPercent = 40.0),
            SkyCondition.SUNNY,
        ),
        Case(
            "active-precipitation weather code forces rainy even with a low pop",
            input(popPercent = 5, weatherCode = 61, humidityPercent = 40.0),
            SkyCondition.RAINY,
        ),
        Case(
            "snow weather code is precipitation, not sunny",
            input(popPercent = 5, weatherCode = 73, humidityPercent = 40.0),
            SkyCondition.RAINY,
        ),
        Case(
            "snow showers weather code is precipitation, not sunny",
            input(popPercent = 5, weatherCode = 85, humidityPercent = 40.0),
            SkyCondition.RAINY,
        ),
        Case(
            "high pop alone forces rainy despite a clear code",
            input(popPercent = 75, weatherCode = 0, humidityPercent = 40.0),
            SkyCondition.RAINY,
        ),
        Case(
            "pop exactly at the high threshold counts as rainy",
            input(popPercent = RainHeuristic.HIGH_POP_THRESHOLD, weatherCode = 0, humidityPercent = 40.0),
            SkyCondition.RAINY,
        ),
        Case(
            "borderline pop with high humidity is treated as likely rain",
            input(popPercent = 40, weatherCode = 1, humidityPercent = 92.0),
            SkyCondition.RAINY,
        ),
        Case(
            "borderline pop with ordinary humidity stays cloudy, not rainy",
            input(popPercent = 40, weatherCode = 1, humidityPercent = 50.0),
            SkyCondition.CLOUDY,
        ),
        Case(
            "fog code reads as cloudy, not sunny",
            input(popPercent = 5, weatherCode = 45, humidityPercent = 40.0),
            SkyCondition.CLOUDY,
        ),
        Case(
            "ordinary cloud code reads as cloudy",
            input(popPercent = 5, weatherCode = 2, humidityPercent = 40.0),
            SkyCondition.CLOUDY,
        ),
        Case(
            "clear code with a meaningful pop is not drawn as full sun (the bug this fixes)",
            input(popPercent = 35, weatherCode = 0, humidityPercent = 40.0),
            SkyCondition.CLOUDY,
        ),
        Case(
            "pop just below the borderline threshold with a clear code stays sunny",
            input(popPercent = RainHeuristic.BORDERLINE_POP_THRESHOLD - 1, weatherCode = 0, humidityPercent = 40.0),
            SkyCondition.SUNNY,
        ),
        Case(
            "every field null falls through to the default sunny result",
            input(),
            SkyCondition.SUNNY,
        ),
        Case(
            "station data present but the forecast fetch failed entirely still detects active rain",
            input(stationPrecipTotalMm = 5.0, previousStationPrecipTotalMm = 3.0),
            SkyCondition.RAINY,
            expectedActivelyRaining = true,
        ),
    )

    @Test
    fun `evaluate matches the expected condition and active-rain flag for every case`() {
        val failures = cases.mapNotNull { case ->
            val result = RainHeuristic.evaluate(case.input)
            val ok = result.condition == case.expectedCondition &&
                result.isActivelyRaining == case.expectedActivelyRaining
            if (ok) {
                null
            } else {
                "${case.name}: expected ${case.expectedCondition}/${case.expectedActivelyRaining}, " +
                    "got ${result.condition}/${result.isActivelyRaining} (${result.reasoning})"
            }
        }
        assertEquals("All heuristic cases should match:\n${failures.joinToString("\n")}", emptyList<String>(), failures)
    }

    @Test
    fun `station ground truth wins even when a rainy weather code would also match`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 61, stationPrecipTotalMm = 2.4, previousStationPrecipTotalMm = 1.0, humidityPercent = 40.0),
        )
        assertTrue("Should be flagged as actively raining from the station reading", result.isActivelyRaining)
        assertTrue(
            "Reasoning should cite the station, not the weather code, since ground truth has priority",
            result.reasoning.contains("Station recorded"),
        )
    }

    @Test
    fun `high pop wins over the borderline-humidity rule when both could match`() {
        // pop=65 is >= HIGH_POP_THRESHOLD (60), so it should hit high_pop, not the
        // borderline rule below it in priority -- even though humidity here would
        // also satisfy the borderline rule's condition if it were reached.
        val result = RainHeuristic.evaluate(
            input(popPercent = 65, weatherCode = 1, humidityPercent = 95.0),
        )
        assertEquals(SkyCondition.RAINY, result.condition)
        assertTrue(result.reasoning.contains("high enough to"))
    }

    @Test
    fun `ground truth pop floor never lowers an already-higher forecast pop`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 95, weatherCode = 0, stationPrecipTotalMm = 1.0, previousStationPrecipTotalMm = 0.5),
        )
        assertEquals(95, result.adjustedPopPercent)
    }

    @Test
    fun `ground truth pop floor raises a low forecast pop to the floor value`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, stationPrecipTotalMm = 1.0, previousStationPrecipTotalMm = 0.5),
        )
        assertEquals(RainHeuristic.ACTIVE_PRECIP_POP_FLOOR, result.adjustedPopPercent)
    }

    // --- radar_echo_near_station ------------------------------------------

    @Test
    fun `radar echo near station forces rainy even with a clear code and low pop`() {
        val result = RainHeuristic.evaluate(input(popPercent = 5, weatherCode = 0, humidityPercent = 40.0, radarEchoNearStation = true))
        assertEquals(SkyCondition.RAINY, result.condition)
        assertTrue(result.reasoning.contains("RainViewer radar"))
    }

    @Test
    fun `radar echo explicitly false does not force rainy on its own`() {
        val result = RainHeuristic.evaluate(input(popPercent = 5, weatherCode = 0, humidityPercent = 40.0, radarEchoNearStation = false))
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `radar echo null (fetch failed) does not force rainy -- absence of data is not evidence of rain`() {
        val result = RainHeuristic.evaluate(input(popPercent = 5, weatherCode = 0, humidityPercent = 40.0, radarEchoNearStation = null))
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `station ground truth still wins over radar echo when both are present`() {
        val result = RainHeuristic.evaluate(
            input(stationPrecipTotalMm = 2.0, previousStationPrecipTotalMm = 1.0, radarEchoNearStation = true),
        )
        assertTrue("Rain gauge already collecting water beats a radar echo that might be virga", result.isActivelyRaining)
    }

    @Test
    fun `radar echo beats model active-precip code -- local radar outranks a regional hourly code`() {
        val result = RainHeuristic.evaluate(input(weatherCode = 61, radarEchoNearStation = true, popPercent = 5))
        assertTrue(result.reasoning.contains("RainViewer radar"))
    }

    // --- rapid_cooling_narrow_dewpoint_spread ------------------------------

    @Test
    fun `sharp temperature drop with high humidity (narrow dew point spread) triggers rainy`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, humidityPercent = 95.0, stationTempC = 20.0, previousStationTempC = 22.0),
        )
        assertEquals(SkyCondition.RAINY, result.condition)
        assertTrue(result.reasoning.contains("gust front"))
    }

    @Test
    fun `sharp temperature drop with LOW humidity (wide dew point spread) does not trigger -- not near saturation`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, humidityPercent = 30.0, stationTempC = 20.0, previousStationTempC = 22.0),
        )
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `gradual temperature drop below the rate threshold does not trigger, even with high humidity -- this is the false-positive guard against ordinary evening cooling`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, humidityPercent = 95.0, stationTempC = 21.5, previousStationTempC = 22.0),
        )
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `rising temperature never triggers the cooling rule`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, humidityPercent = 95.0, stationTempC = 23.0, previousStationTempC = 22.0),
        )
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `missing previous temperature (first refresh ever) does not trigger the cooling rule`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 5, weatherCode = 0, humidityPercent = 95.0, stationTempC = 20.0, previousStationTempC = null),
        )
        assertEquals(SkyCondition.SUNNY, result.condition)
    }

    @Test
    fun `high_pop wins over the rapid-cooling rule -- model agreement takes precedence over the station-only signal`() {
        val result = RainHeuristic.evaluate(
            input(popPercent = 65, weatherCode = 0, humidityPercent = 95.0, stationTempC = 20.0, previousStationTempC = 22.0),
        )
        assertTrue(result.reasoning.contains("high enough to"))
    }

    @Test
    fun `temperature drop exactly at the rate threshold triggers (boundary is inclusive)`() {
        val result = RainHeuristic.evaluate(
            input(
                popPercent = 5, weatherCode = 0, humidityPercent = 95.0,
                stationTempC = 20.0,
                previousStationTempC = 20.0 + RainHeuristic.RAPID_COOLING_THRESHOLD_C,
            ),
        )
        assertEquals(SkyCondition.RAINY, result.condition)
    }

    // --- dewPointC / dewPointSpreadC ---------------------------------------

    @Test
    fun `dewPointC matches a known reference value -- 20C at 50pct RH is about 9,3C`() {
        val dewPoint = RainHeuristic.dewPointC(20.0, 50.0)
        assertTrue(dewPoint != null && kotlin.math.abs(dewPoint - 9.3) < 0.5)
    }

    @Test
    fun `dewPointC at 100pct humidity equals the air temperature -- saturated air`() {
        val dewPoint = RainHeuristic.dewPointC(15.0, 100.0)
        assertTrue(dewPoint != null && kotlin.math.abs(dewPoint - 15.0) < 0.01)
    }

    @Test
    fun `dewPointC returns null for zero or negative humidity -- undefined, not zero`() {
        assertEquals(null, RainHeuristic.dewPointC(20.0, 0.0))
        assertEquals(null, RainHeuristic.dewPointC(20.0, -5.0))
    }

    @Test
    fun `dewPointC returns null for humidity over 100pct -- not a physically valid reading`() {
        assertEquals(null, RainHeuristic.dewPointC(20.0, 150.0))
    }

    @Test
    fun `dewPointSpreadC is temperature minus dew point, and shrinks as humidity rises`() {
        val wideSpread = RainHeuristic.dewPointSpreadC(20.0, 40.0)
        val narrowSpread = RainHeuristic.dewPointSpreadC(20.0, 90.0)
        assertTrue(wideSpread != null && narrowSpread != null && narrowSpread < wideSpread)
    }
}
