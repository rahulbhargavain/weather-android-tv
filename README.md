# Weather Dashboard for Android TV

A single-screen Android TV dashboard showing live weather, a regional radar, and headlines from RSS sources.
It fetches data directly from your Personal Weather Station (PWS) and Open-Meteo, combining them into a unified forecast using a custom heuristic.

## Features
- **PWS Integration**: Shows live temp, humidity, and rainfall from your station.
- **Radar Image**: Stitches 3x3 tiles from RainViewer to show regional radar.
- **Smart Rain Heuristic**: Combines your station's current trend, Open-Meteo's forecast, and nearby radar echo into a single "is it raining / will it rain" prediction.
- **News Grid**: Displays top headlines from configurable RSS sources.

## Setup

1. Clone the repository and open it in Android Studio.
2. Copy `local.properties.example` to `local.properties`.
3. Add your details to `local.properties`:
   ```properties
   PWS_STATION_ID=YOUR_STATION_ID
   PWS_API_KEY=YOUR_API_KEY
   LATITUDE=YOUR_LAT
   LONGITUDE=YOUR_LON
   ```
   *Note: `local.properties` is ignored by Git. Never commit your API keys.*
4. Build the APK via **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Sideload the `app-debug.apk` onto your Android TV box (via USB, Downloader app, or ADB).

## Configuration

To customize news sources, edit the `NEWS_SOURCES` list in [`MainActivity.kt`](app/src/main/java/com/bhimtal/dashboard/MainActivity.kt).

## Tests & Backtesting

- Run unit tests with `./gradlew testDebugUnitTest` to test the heuristic logic.
- A standalone backtesting script is available in the `backtest/` directory to evaluate the `RainHeuristic` rules against historical weather data.

## Satellite Data & Future Predictors

`satellite/fetch_gsmap.py` — pull GSMaP rain-rate estimates for your exact coordinates. Setup is `pip install earthengine-api` then `earthengine authenticate` (one-time browser login). Before your first real run, spend five minutes cross-checking `GSMAP_COLLECTION_ID` and `BAND_NAME` against the current Earth Engine catalog page — I wrote those from search results, not a live catalog check, since this sandbox has no path to Earth Engine. It fails loudly if either is stale, not silently.

Once you've pulled some real GSMaP rows, the natural next move is feeding them into `backtest_rain_heuristic.py` as a ground-truth cross-check alongside ERA5, or eventually wiring a distilled version into `RainHeuristic` the same way `radar_echo_near_station` was added — happy to help with either when you've got data in hand.

### Satellite data: what's actually usable here

* **JAXA — GSMaP (Global Satellite Mapping of Precipitation).** This is the most directly relevant one. It's JAXA's own product from the GPM (Global Precipitation Measurement) mission — a joint NASA-JAXA effort where JAXA specifically built the dual-frequency precipitation radar. GSMaP delivers global rainfall rate at 0.1° (~11km) resolution, hourly, and the standard near-real-time product has roughly a 4-hour latency, using microwave radiometers plus infrared/Himawari data. It genuinely covers the Global South — the "Global" in the name is real. Access is via JAXA Global Rainfall Watch, free with registration (121 countries' worth of registered users as of a few years ago), and it's also hosted on Google Earth Engine, which is the more practical route if you want to query it programmatically rather than deal with raw institutional file formats.
* **NASA/JAXA joint — GPM IMERG.** The other major product from the same GPM constellation, similarly ~30min/0.1°, also free (NASA Earthdata login) and also on Earth Engine. Functionally similar value to GSMaP for your purposes — either one, not really both.
* **ESA-adjacent — EUMETSAT Meteosat, not ESA itself.** Worth a correction here: ESA builds and launches the Meteosat satellites, but EUMETSAT (a separate European intergovernmental org) owns and operates them and sets the data policy — the distinction matters if you're citing a source. The genuinely useful one for you: EUMETSAT repositioned a Meteosat satellite (currently Meteosat-9) to 45.5°E specifically for Indian Ocean Data Coverage (IODC) — that's a good viewing angle for Bhimtal (~79.5°E), meaningfully better geometry than Japan's Himawari-8/9 at 140.7°E would give you, since Himawari's view of the Global South is a much more oblique, near-edge-of-disk angle. Since 2019, EUMETSAT's policy made hourly imagery free with no licensing at all, and full-resolution 15-minute imagery free (registration required) after a 3-hour delay. This gives cloud-top imagery, not a direct rain-rate product like GSMaP — useful for tracking cloud development, not as directly actionable as a precipitation estimate.

### Is this too sophisticated/data-heavy?

Partially — but not for the reason you'd think. The access policies are genuinely fine (free registration, not paywalled, unlike many regional agencies in the Global South). The real barrier is the data format: these come as HDF5/NetCDF/GeoTIFF grids needing actual geospatial libraries (rasterio, xarray, GDAL) — a real step up from Open-Meteo's plain JSON and RainViewer's plain PNG tiles, and not something you'd want to do in Kotlin on an Android TV box at all. 

The sane architecture would be: pull and process GSMaP/IMERG data in something like the Python backtest environment (or a small scheduled job), reduce it to one distilled number or boolean for your station's coordinates, and only ever ship that tiny value to the app — same "pure computation, thin impure shell" split the app already uses elsewhere, just with the heavy lifting happening server-side/offline instead of on-device.

**Practical recommendation if you want to pursue this:** GSMaP via Earth Engine is the best next satellite source to add — it's the only one of these that gives you an actual rain-rate estimate (not just cloud imagery) with genuinely workable latency (4hr NRT).

### Other predictive signals worth exploring

**Optical flow radar extrapolation:** Instead of neural networks, you can track a precipitation cell's motion between two consecutive radar frames using optical flow, then project it forward. The app already fetches a RainViewer frame every refresh; persisting the *previous* frame would allow estimating motion ("a cell 20km northwest is moving toward you at roughly this speed"). It's a proven, classical technique that's a realistic next step without needing machine learning infrastructure.

## License

MIT - see `LICENSE`.
