"""
backtest_rain_heuristic.py -- Point-in-time backtest of RainHeuristic
against real recorded weather, for Bhimtal Dashboard.

WHY THIS SHAPE, NOT A NAIVE ONE:

RainHeuristic's high_pop, borderline_pop_high_humidity, and
cloud_code_or_meaningful_pop rules all depend on popPercent -- a
PROBABILITY. A probability doesn't exist retroactively: once an hour is
in the past, it either rained or it didn't. So testing the heuristic
against Open-Meteo's ERA5 reanalysis archive (which only has what
actually happened) would either have no popPercent field to test at all,
or would require deriving one after the fact -- which is exactly the
lookahead-bias trap walk_forward_validator.py was built to catch in the
quant work this pattern is borrowed from.

The fix: Open-Meteo's Historical Forecast API archives what the model
ACTUALLY SAID, at the time it said it (same fields as the live forecast
endpoint, archived since 2021) -- a genuine point-in-time datastream, not
a reconstruction. This script pulls forecast fields (popPercent,
weathercode) from that archive, and observed fields (actual
precipitation, temperature, humidity) from the separate ERA5 archive, for
the same hours, and scores the heuristic's output against what really
happened.

WHAT THIS RUN CANNOT TEST, and why -- three separate gaps, not one:

1. station_ground_truth needs YOUR PWS's own precipitation-total history.
   This script has none (no PWS data source was supplied for this run).
   Every hour has stationPrecipTotalMm=None, so this rule never fires.

2. radar_echo_near_station needs a historical RADAR archive. No free one
   exists that this script could find -- RainViewer only serves current/
   recent frames, not a queryable history. This rule can never fire in
   ANY run of this script, regardless of what data you supply.

3. rapid_cooling_narrow_dewpoint_spread is approximated, not tested as it
   actually runs. In production it compares STATION temperature between
   REFRESHES 15 minutes apart -- a local, high-frequency signal. This
   backtest can only substitute ERA5's grid-cell temperature_2m between
   HOURS, which is (a) a much coarser time resolution, so the threshold
   below is deliberately different from and not comparable to the
   production constant, and (b) a reanalysis grid value, not a genuine
   point observation -- a real, sharp, local outflow-boundary temperature
   drop at your exact station may simply not be visible in a coarser grid
   cell's hourly average. Treat this part of the backtest as a rough
   sanity check of the RULE'S SHAPE (does "sharp cooling + high humidity"
   correlate with rain at all in this climate), not a measurement of how
   the rule performs in the app.

The other three rules (model_active_precip_code, high_pop,
borderline_pop_high_humidity) plus the fog/cloud/default fallbacks are
fully and faithfully exercised.

ALSO COMPARED: the OLD behavior (condition purely from weathercode,
ignoring popPercent and humidity entirely -- what the app did before this
heuristic existed) is scored on the same data, so you can see whether the
new heuristic actually improved on the old one rather than just being
different from it.

USAGE:
    pip install requests
    python backtest_rain_heuristic.py --lat 29.35 --lon 79.55 \
        --start-date 2024-06-01 --end-date 2024-09-30

Runs entirely against Open-Meteo's free, keyless APIs -- no PWS
credentials involved anywhere in this script.
"""
from __future__ import annotations

import argparse
import math
import sys
from dataclasses import dataclass
from typing import Callable, Optional

import requests

HISTORICAL_FORECAST_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast"
ERA5_ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
RAIN_THRESHOLD_MM = 0.1  # hourly precip at/above this counts as "it rained"


# ---------------------------------------------------------------------------
# RainHeuristic -- Python port of app/src/main/java/.../heuristic/RainHeuristic.kt
# Kept in the same rule-table shape, same constants, same priority order.
# If you change the Kotlin version, mirror the change here or this backtest
# stops measuring the heuristic that's actually shipping.
# ---------------------------------------------------------------------------

ACTIVE_PRECIP_POP_FLOOR = 90
HIGH_POP_THRESHOLD = 60
BORDERLINE_POP_THRESHOLD = 30
HIGH_HUMIDITY_THRESHOLD = 90.0
DEFAULT_POP_FOR_ACTIVE_CODE = 70
RADAR_ECHO_POP_FLOOR = 70
NARROW_DEWPOINT_SPREAD_C = 3.0
# NOT the same as the production 15-minute RAPID_COOLING_THRESHOLD_C (1.5C) --
# see the module docstring's point 3. This is a distinctly larger threshold
# because it's being compared over a full HOUR, not 15 minutes, and normal
# hourly diurnal cooling can itself be 1-2C during morning/evening transitions.
HOURLY_RAPID_COOLING_THRESHOLD_C = 3.0


def dew_point_c(temp_c: float, humidity_percent: float) -> Optional[float]:
    """Magnus-Tetens approximation, mirrors RainHeuristic.kt's dewPointC()."""
    if humidity_percent <= 0.0 or humidity_percent > 100.0:
        return None
    a, b = 17.27, 237.7
    alpha = math.log(humidity_percent / 100.0) + (a * temp_c) / (b + temp_c)
    return (b * alpha) / (a - alpha)


def dew_point_spread_c(temp_c: float, humidity_percent: float) -> Optional[float]:
    dp = dew_point_c(temp_c, humidity_percent)
    return None if dp is None else temp_c - dp


@dataclass(frozen=True)
class HeuristicInput:
    pop_percent: Optional[int]
    weather_code: Optional[int]
    station_precip_total_mm: Optional[float]
    previous_station_precip_total_mm: Optional[float]
    humidity_percent: Optional[float]
    station_temp_c: Optional[float] = None
    previous_station_temp_c: Optional[float] = None
    radar_echo_near_station: Optional[bool] = None


@dataclass(frozen=True)
class HeuristicResult:
    condition: str  # "SUNNY" | "CLOUDY" | "RAINY"
    adjusted_pop_percent: Optional[int]
    is_actively_raining: bool
    reasoning: str
    rule_name: str


def _is_active_precip_code(code: Optional[int]) -> bool:
    return code is not None and (51 <= code <= 67 or 80 <= code <= 82 or 95 <= code <= 99)


def _is_fog_code(code: Optional[int]) -> bool:
    return code is not None and 45 <= code <= 48


def _is_cloud_code(code: Optional[int]) -> bool:
    return code is not None and 1 <= code <= 3


def _rule_station_ground_truth(i: HeuristicInput) -> Optional[HeuristicResult]:
    if i.station_precip_total_mm is None or i.previous_station_precip_total_mm is None:
        return None
    if i.station_precip_total_mm <= i.previous_station_precip_total_mm:
        return None
    return HeuristicResult(
        "RAINY", max(i.pop_percent or 0, ACTIVE_PRECIP_POP_FLOOR), True,
        "Station recorded new precipitation since the last refresh.", "station_ground_truth",
    )


def _rule_radar_echo_near_station(i: HeuristicInput) -> Optional[HeuristicResult]:
    if i.radar_echo_near_station is not True:
        return None
    return HeuristicResult(
        "RAINY", max(i.pop_percent or 0, RADAR_ECHO_POP_FLOOR), False,
        "Radar shows echo near the station.", "radar_echo_near_station",
    )


def _rule_model_active_precip_code(i: HeuristicInput) -> Optional[HeuristicResult]:
    if not _is_active_precip_code(i.weather_code):
        return None
    return HeuristicResult(
        "RAINY", i.pop_percent if i.pop_percent is not None else DEFAULT_POP_FOR_ACTIVE_CODE, False,
        f"Active precipitation weather code ({i.weather_code}).", "model_active_precip_code",
    )


def _rule_high_pop(i: HeuristicInput) -> Optional[HeuristicResult]:
    if (i.pop_percent if i.pop_percent is not None else -1) < HIGH_POP_THRESHOLD:
        return None
    return HeuristicResult("RAINY", i.pop_percent, False, f"High pop {i.pop_percent}%.", "high_pop")


def _rule_rapid_cooling_narrow_dewpoint_spread(i: HeuristicInput) -> Optional[HeuristicResult]:
    if i.station_temp_c is None or i.previous_station_temp_c is None or i.humidity_percent is None:
        return None
    temp_drop = i.previous_station_temp_c - i.station_temp_c
    if temp_drop < HOURLY_RAPID_COOLING_THRESHOLD_C:
        return None
    spread = dew_point_spread_c(i.station_temp_c, i.humidity_percent)
    if spread is None or spread > NARROW_DEWPOINT_SPREAD_C:
        return None
    return HeuristicResult(
        "RAINY", max(i.pop_percent or 0, HIGH_POP_THRESHOLD), False,
        f"Dropped {temp_drop:.1f}C in an hour with a {spread:.1f}C dew point spread.",
        "rapid_cooling_narrow_dewpoint_spread",
    )


def _rule_borderline_pop_high_humidity(i: HeuristicInput) -> Optional[HeuristicResult]:
    pop = i.pop_percent if i.pop_percent is not None else -1
    if not (BORDERLINE_POP_THRESHOLD <= pop < HIGH_POP_THRESHOLD):
        return None
    if (i.humidity_percent or 0.0) < HIGH_HUMIDITY_THRESHOLD:
        return None
    return HeuristicResult(
        "RAINY", i.pop_percent, False,
        f"Borderline {i.pop_percent}% + humidity {i.humidity_percent}%.", "borderline_pop_high_humidity",
    )


def _rule_fog_code(i: HeuristicInput) -> Optional[HeuristicResult]:
    if not _is_fog_code(i.weather_code):
        return None
    return HeuristicResult("CLOUDY", i.pop_percent, False, f"Fog code ({i.weather_code}).", "fog_code")


def _rule_cloud_code_or_meaningful_pop(i: HeuristicInput) -> Optional[HeuristicResult]:
    pop = i.pop_percent if i.pop_percent is not None else -1
    if not (_is_cloud_code(i.weather_code) or pop >= BORDERLINE_POP_THRESHOLD):
        return None
    reason = f"Cloud code ({i.weather_code})." if _is_cloud_code(i.weather_code) else f"Meaningful pop {i.pop_percent}%."
    return HeuristicResult("CLOUDY", i.pop_percent, False, reason, "cloud_code_or_meaningful_pop")


_RULES: list[Callable[[HeuristicInput], Optional[HeuristicResult]]] = [
    _rule_station_ground_truth,
    _rule_radar_echo_near_station,
    _rule_model_active_precip_code,
    _rule_high_pop,
    _rule_rapid_cooling_narrow_dewpoint_spread,
    _rule_borderline_pop_high_humidity,
    _rule_fog_code,
    _rule_cloud_code_or_meaningful_pop,
]


def evaluate(i: HeuristicInput) -> HeuristicResult:
    for rule in _RULES:
        result = rule(i)
        if result is not None:
            return result
    return HeuristicResult("SUNNY", i.pop_percent, False, "No rain signal.", "default_sunny")


def old_condition_from_weather_code_only(code: Optional[int]) -> str:
    """The PREVIOUS app version's logic -- condition from weathercode alone,
    ignoring popPercent and humidity entirely. Scored here for comparison."""
    if code is None:
        return "SUNNY"
    if code == 0:
        return "SUNNY"
    if 1 <= code <= 3 or 45 <= code <= 48:
        return "CLOUDY"
    if 51 <= code <= 67 or 80 <= code <= 82 or 95 <= code <= 99:
        return "RAINY"
    return "CLOUDY"


# ---------------------------------------------------------------------------
# Data fetching
# ---------------------------------------------------------------------------

def fetch_hourly(url: str, lat: float, lon: float, start_date: str, end_date: str, hourly_vars: str) -> dict:
    resp = requests.get(
        url,
        params={
            "latitude": lat, "longitude": lon,
            "start_date": start_date, "end_date": end_date,
            "hourly": hourly_vars, "timezone": "auto",
        },
        timeout=30,
    )
    if resp.status_code == 404 and "historical-forecast" in url:
        print(
            "NOTE: historical-forecast-api.open-meteo.com returned 404. This script was written "
            "without live network access to verify the exact subdomain -- if Open-Meteo has since "
            "renamed or restructured this endpoint, check https://open-meteo.com/en/docs/historical-forecast-api "
            "for the current URL and update HISTORICAL_FORECAST_URL above.",
            file=sys.stderr,
        )
    resp.raise_for_status()
    return resp.json()["hourly"]


def fetch_point_in_time_forecast(lat: float, lon: float, start_date: str, end_date: str) -> dict:
    """What the model actually said, at the time it said it."""
    return fetch_hourly(
        HISTORICAL_FORECAST_URL, lat, lon, start_date, end_date,
        "precipitation_probability,weathercode,relative_humidity_2m",
    )


def fetch_ground_truth(lat: float, lon: float, start_date: str, end_date: str) -> dict:
    """What actually happened, from ERA5 reanalysis. temperature_2m and
    relative_humidity_2m are included so the rapid-cooling rule has
    something to approximate station temperature/humidity with -- see the
    module docstring's point 3 for why this is an approximation, not a
    faithful test of that rule."""
    return fetch_hourly(ERA5_ARCHIVE_URL, lat, lon, start_date, end_date, "precipitation,temperature_2m,relative_humidity_2m")


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

def confusion_counts(predictions: list[bool], actuals: list[bool]) -> dict:
    tp = sum(p and a for p, a in zip(predictions, actuals))
    fp = sum(p and not a for p, a in zip(predictions, actuals))
    fn = sum((not p) and a for p, a in zip(predictions, actuals))
    tn = sum((not p) and (not a) for p, a in zip(predictions, actuals))
    n = len(predictions)
    accuracy = (tp + tn) / n if n else float("nan")
    precision = tp / (tp + fp) if (tp + fp) else float("nan")
    recall = tp / (tp + fn) if (tp + fn) else float("nan")
    f1 = 2 * precision * recall / (precision + recall) if precision and recall and (precision + recall) else float("nan")
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "n": n, "accuracy": accuracy, "precision": precision, "recall": recall, "f1": f1}


def brier_score(probabilities: list[float], actuals: list[bool]) -> Optional[float]:
    pairs = [(p, a) for p, a in zip(probabilities, actuals) if p is not None]
    if not pairs:
        return None
    return sum((p / 100.0 - (1.0 if a else 0.0)) ** 2 for p, a in pairs) / len(pairs)


def run(lat: float, lon: float, start_date: str, end_date: str) -> None:
    print(f"Fetching point-in-time forecast history for ({lat}, {lon}), {start_date} to {end_date}...")
    forecast = fetch_point_in_time_forecast(lat, lon, start_date, end_date)
    print("Fetching ERA5 ground truth for the same window...")
    truth = fetch_ground_truth(lat, lon, start_date, end_date)

    times = forecast["time"]
    pops = forecast["precipitation_probability"]
    codes = forecast["weathercode"]
    humidities = forecast["relative_humidity_2m"]
    truth_times = truth["time"]
    precips = truth["precipitation"]
    truth_temps = truth["temperature_2m"]
    # ERA5's own temperature as an approximate stand-in for station temperature
    # (see module docstring point 3) -- keyed by time so it aligns even if the
    # forecast and truth series don't start at exactly the same hour.
    truth_by_time = dict(zip(truth_times, precips))
    temp_by_time = dict(zip(truth_times, truth_temps))
    sorted_truth_times = sorted(temp_by_time.keys())
    previous_temp_by_time = {
        sorted_truth_times[idx]: temp_by_time[sorted_truth_times[idx - 1]]
        for idx in range(1, len(sorted_truth_times))
    }

    new_predictions, new_probs, old_predictions, actuals, rule_fires = [], [], [], [], {}
    matched = 0
    for t, pop, code, hum in zip(times, pops, codes, humidities):
        actual_precip = truth_by_time.get(t)
        if actual_precip is None:
            continue
        matched += 1
        actual_rained = actual_precip >= RAIN_THRESHOLD_MM

        station_temp = temp_by_time.get(t)
        previous_station_temp = previous_temp_by_time.get(t)
        result = evaluate(
            HeuristicInput(
                pop, code, None, None, hum,
                station_temp_c=station_temp, previous_station_temp_c=previous_station_temp,
                radar_echo_near_station=None,  # never available -- see module docstring point 2
            )
        )
        new_predictions.append(result.condition == "RAINY")
        new_probs.append(result.adjusted_pop_percent)
        rule_fires[result.rule_name] = rule_fires.get(result.rule_name, 0) + 1

        old_predictions.append(old_condition_from_weather_code_only(code) == "RAINY")
        actuals.append(actual_rained)

    if matched == 0:
        print("No overlapping hours between forecast and ground truth -- check the date range and try again.")
        sys.exit(1)

    print(f"\n{matched} hours matched between forecast archive and ERA5 ground truth.")
    print(f"Hours with actual rain (>= {RAIN_THRESHOLD_MM} mm): {sum(actuals)} ({100 * sum(actuals) / matched:.1f}%)\n")

    print("=== RainHeuristic (new) ===")
    new_stats = confusion_counts(new_predictions, actuals)
    for k, v in new_stats.items():
        print(f"  {k}: {v:.3f}" if isinstance(v, float) else f"  {k}: {v}")
    bs = brier_score(new_probs, actuals)
    print(f"  brier_score (lower is better, 0=perfect, 0.25=coin flip baseline): {bs:.4f}" if bs is not None else "  brier_score: n/a")

    print("\n=== Old logic (weathercode only, no pop/humidity) ===")
    old_stats = confusion_counts(old_predictions, actuals)
    for k, v in old_stats.items():
        print(f"  {k}: {v:.3f}" if isinstance(v, float) else f"  {k}: {v}")

    print("\n=== Rule fire counts ===")
    for name, count in sorted(rule_fires.items(), key=lambda kv: -kv[1]):
        print(f"  {name}: {count} ({100 * count / matched:.1f}%)")
    print(
        "  NOTE: station_ground_truth and radar_echo_near_station will show 0 here -- neither "
        "has real data in this run (see module docstring points 1 and 2). "
        "rapid_cooling_narrow_dewpoint_spread fired using ERA5's hourly grid temperature as an "
        "approximation of station temperature, not your actual station readings (point 3)."
    )

    print(
        f"\nAccuracy delta (new - old): {new_stats['accuracy'] - old_stats['accuracy']:+.3f}  "
        f"(positive = the heuristic improved on the old weathercode-only logic)"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lat", type=float, required=True, help="Latitude, decimal degrees")
    parser.add_argument("--lon", type=float, required=True, help="Longitude, decimal degrees")
    parser.add_argument("--start-date", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end-date", required=True, help="YYYY-MM-DD")
    args = parser.parse_args()
    run(args.lat, args.lon, args.start_date, args.end_date)
