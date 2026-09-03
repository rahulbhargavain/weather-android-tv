"""
fetch_gsmap.py -- pulls JAXA GSMaP satellite precipitation-rate estimates
for one point (your station's coordinates) from Google Earth Engine, and
writes them to a CSV.

WHY EARTH ENGINE, NOT JAXA'S OWN PORTAL DIRECTLY: GSMaP's native files are
HDF5 grids needing GDAL/rasterio-class geospatial tooling -- a real step
up from Open-Meteo's plain JSON and RainViewer's plain PNG tiles, and not
something worth doing on an Android TV device at all. Earth Engine
already ingests and serves the same GSMaP product server-side, so this
script does one small point query per timestamp instead of downloading
and parsing global raster grids. This is the "heavy lifting happens
offline, the app only ever sees one distilled number" architecture
described in the README's satellite section.

CANNOT RUN IN A SANDBOXED/HEADLESS ENVIRONMENT: Earth Engine
authentication is interactive OAuth (opens a real browser window), so
this genuinely has to run on your own machine, not here. One-time setup:

    pip install earthengine-api
    earthengine authenticate      # opens a browser, one-time
    python fetch_gsmap.py --lat <yours> --lon <yours> \
        --start-date 2024-06-01 --end-date 2024-06-07

If your Earth Engine account requires a Cloud project (most do now),
pass it with --ee-project your-project-id.

VERIFY BEFORE RELYING ON THIS: GSMAP_COLLECTION_ID and BAND_NAME below
were taken from Earth Engine catalog search results without live access
to confirm them against the current catalog (this script was written in
a sandbox with no network path to Earth Engine or its docs). Cross-check
both against https://developers.google.com/earth-engine/datasets/catalog
(search "GSMaP") before your first real run -- collection IDs and band
names occasionally change between dataset versions, and a stale ID fails
loudly (ee.ImageCollection raises), not silently, so this is a one-time
five-minute check, not a recurring risk.

OUTPUT: a CSV with columns time, gsmap_rain_rate_mm_per_hr -- one row per
GSMaP frame overlapping [start_date, end_date). Point this at
walk_forward_validator.chronological_split() or
backtest_rain_heuristic.py's ERA5 comparison once you have real rows to
work with -- see the README's "Testing the hypothesis" section for how
the pieces are meant to compose.
"""
from __future__ import annotations

import argparse
import csv
import sys

try:
    import ee
except ImportError:
    print("Missing dependency. Run: pip install earthengine-api", file=sys.stderr)
    sys.exit(1)

# See "VERIFY BEFORE RELYING ON THIS" in the module docstring.
GSMAP_COLLECTION_ID = "JAXA/GPM_L3/GSMaP_v7/operational"
BAND_NAME = "hourlyPrecipRate"
POINT_SAMPLE_SCALE_M = 11000  # GSMaP's native ~0.1 degree grid is roughly 11km


def fetch_point_series(lat: float, lon: float, start_date: str, end_date: str) -> list[dict]:
    """One server-side point query per GSMaP frame in the date range --
    Earth Engine does the raster work, this only ever receives small
    per-timestamp scalars back, not raw grids."""
    point = ee.Geometry.Point([lon, lat])
    collection = (
        ee.ImageCollection(GSMAP_COLLECTION_ID)
        .filterDate(start_date, end_date)
        .filterBounds(point)
    )

    def extract(image):
        value = image.select(BAND_NAME).reduceRegion(
            reducer=ee.Reducer.first(), geometry=point, scale=POINT_SAMPLE_SCALE_M,
        ).get(BAND_NAME)
        return ee.Feature(None, {
            "time": image.date().format("YYYY-MM-dd'T'HH:mm"),
            "gsmap_rain_rate_mm_per_hr": value,
        })

    result = collection.map(extract).getInfo()
    return [feature["properties"] for feature in result["features"]]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lat", type=float, required=True, help="Latitude, decimal degrees")
    parser.add_argument("--lon", type=float, required=True, help="Longitude, decimal degrees")
    parser.add_argument("--start-date", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end-date", required=True, help="YYYY-MM-DD, exclusive")
    parser.add_argument("--out", default="gsmap_point_series.csv", help="Output CSV path")
    parser.add_argument("--ee-project", default=None, help="Your Earth Engine cloud project ID, if your account needs one")
    args = parser.parse_args()

    if args.ee_project:
        ee.Initialize(project=args.ee_project)
    else:
        ee.Initialize()

    print(f"Querying {GSMAP_COLLECTION_ID} for ({args.lat}, {args.lon}), {args.start_date} to {args.end_date}...")
    rows = fetch_point_series(args.lat, args.lon, args.start_date, args.end_date)

    if not rows:
        print(
            "No frames returned. Likely causes: GSMAP_COLLECTION_ID is stale (see module "
            "docstring), or the date range predates the collection's coverage.",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["time", "gsmap_rain_rate_mm_per_hr"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows to {args.out}")


if __name__ == "__main__":
    main()
