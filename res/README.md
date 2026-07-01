# Data files (`res/`)

Datasets loaded at runtime by the visualizer apps (and by the library's tests when run from here). Each dataset's terms
belong to its provider — see the sources below.

## Layout convention

Data is organized **by the package that loads it**, in a folder named after that package:

```
res/
  magnetic/WMM2025COF/        loaded by geophysicalModelLibrary.magnetic  (WMM)
  gravity/EGM2008.gfc         loaded by geophysicalModelLibrary.gravity   (EGM2008)
  gravity/EGM96.gfc           loaded by geophysicalModelLibrary.gravity   (EGM96)
  terrain/rasters_COP30_*/    loaded by geophysicalModelLibrary.terrain   (Copernicus DEM)
  ne_110m_coastline.geojson   loaded by utils.Coastlines (a general visualizer asset, not package data)
```

Apps and tests look for each file at a **single relative path** `res/<package>/…` resolved from the **working
directory** — there is no fallback path. This works in both setups:

- **This visualizer repo** commits the data and is run from its root, so `res/<package>/…` is found.
- **A standalone checkout of the library** has its own `res/` at its root, where the (untracked) data is placed; its
  tests find `res/<package>/…` the same way, and skip gracefully (`assumeTrue`) if a file is absent.

So if you add data, mirror this `res/<package>/…` structure and run from the repo root.

## Datasets

### `magnetic/WMM2025COF/` — World Magnetic Model 2025
- **Used by:** `PlotMagneticFieldModel` and `WorldMagneticModelTest` (loads `WMM.COF`; the test validates against
  `WMM2025_TestValues.txt`).
- **Source:** NOAA NCEI / British Geological Survey — <https://www.ncei.noaa.gov/products/world-magnetic-model>.
- Contains `WMM.COF` (coefficients loaded), plus `WMM2025.COF`, `WMM2025_TestValues.txt`, and `README-WMM-COEFS.txt`
  as distributed.

### `gravity/EGM2008.gfc` — Earth Gravitational Model 2008
- **Used by:** `PlotGravityModel` (gravity anomaly + geoid undulation) and `PlotElevationModel` (geoid undulation `N`,
  i.e. mean sea level, for placing terrain on the globe); also `Egm2008Test` / `SphericalHarmonicGravityModelTest`.
- **Format:** ICGEM `.gfc` spherical-harmonic coefficients — <https://icgem.gfz-potsdam.de/>.
- **Large (~240 MB).** It is committed here for convenience; expect a heavy clone. In a standalone library checkout it
  is normally left untracked and the tests skip when it is missing.

### `gravity/EGM96.gfc` — Earth Gravitational Model 1996
- **Used by:** `Egm96` (degree/order 360). It is the older geoid that many GPS receivers still use to report height
  above mean sea level, so it is handy when reconciling GPS altitudes with a model geoid; `Egm96Test` validates it.
- **Format:** ICGEM `.gfc` spherical-harmonic coefficients (~5.6 MB) — <https://icgem.gfz-potsdam.de/>.
- **Producer:** NASA GSFC and NIMA (Lemoine et al., 1998, NASA/TP-1998-206861).

### `terrain/rasters_COP30_<region>/` — Copernicus GLO-30 elevation
- **Used by:** `PlotElevationModel` (regions: `murcia`, `toulouse`).
- **Format:** ESRI ASCII Grid (`output_hh.asc`) with a `.prj` sidecar (WKT, WGS84 geographic). Chosen over the GeoTIFF
  (`.tif`, needs a decoder) and ERDAS IMAGINE (`.img`, proprietary) exports because it is plain-text, self-describing,
  and parses in pure Java with no extra dependency.
- This is a **DSM** (Digital Surface Model): heights include vegetation and buildings, not bare earth. Heights are
  **orthometric** (above the EGM2008 geoid / mean sea level).
- **Source:** Copernicus DEM (GLO-30), derived from TanDEM-X (© DLR / ESA), downloaded from OpenTopography —
  <https://opentopography.org>. Use of the Copernicus DEM requires attribution per its license.

### `ne_110m_coastline.geojson` — Natural Earth coastlines
- **Used by:** `utils.Coastlines`, drawn by all three globe apps.
- **Source:** Natural Earth 1:110m (public domain) —
  <https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_coastline.geojson>.

## Licensing

The repository's own license (GPL-3.0-or-later, for the code under `src/`) does not cover these third-party datasets.
WMM and EGM2008 are produced by public agencies and are free to use; the Copernicus DEM requires attribution; Natural
Earth is public domain. Check each provider's terms before redistributing — see
[`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md).
