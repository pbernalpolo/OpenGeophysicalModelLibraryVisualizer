# Third-party notices

This repository's own source code (under `src/`) is licensed under **GPL-3.0-or-later** (see [`LICENSE`](LICENSE)).
For convenience the repository also bundles third-party software (under `lib/`) and datasets (under `res/`) that are
**not** covered by that license — each is the property of its authors and is redistributed here under its own terms,
listed below. The included `OpenGeophysicalModelLibrary-java` submodule is separately licensed (MIT).

## Bundled software (`lib/`)

### Processing core
- File: `lib/processing-4.3-linux-arm64/processing-4.3/core/library/core.jar` (Processing 4.3).
- License: **GNU LGPL v2.1**. (The Processing *application* is GPL; the bundled `core` library is LGPL.)
- Copyright: The Processing Foundation; Ben Fry and Casey Reas.
- Source: <https://github.com/processing/processing4>.

### JOGL and GlueGen
- Files: `jogl-all.jar`, `gluegen-rt.jar` and their native libraries, bundled with Processing.
- License: **BSD (2-clause / New BSD)**.
- Copyright: JogAmp Community.
- Source: <https://jogamp.org/>.

### ControlP5
- File: `lib/controlP5-2.2.6/controlP5/library/controlP5.jar` (ControlP5 2.2.6).
- License: **GNU LGPL**.
- Copyright: Andreas Schlegel.
- Source: <https://github.com/sojamo/controlp5> — <http://www.sojamo.de/libraries/controlP5/>.

> The bundled `core.jar` and `controlP5.jar` are unmodified upstream binaries; their LGPL source is available at the
> links above. Under the LGPL they may be used by code under another license (here, GPL-3.0-or-later) and relinked with
> a modified version of the library.

## Bundled datasets (`res/`)

See [`res/README.md`](res/README.md) for full details. Summary of terms:

### World Magnetic Model 2025 — `res/magnetic/WMM2025COF/`
- Producer: NOAA NCEI and the British Geological Survey.
- Terms: produced by public agencies and free to use (work of the U.S. Government, public domain).
- Source: <https://www.ncei.noaa.gov/products/world-magnetic-model>.

### EGM2008 — `res/gravity/EGM2008.gfc`
- Producer: U.S. National Geospatial-Intelligence Agency (NGA).
- Terms: publicly released and free to use.
- Source / distribution: ICGEM, <https://icgem.gfz-potsdam.de/>.

### Copernicus GLO-30 DEM — `res/terrain/rasters_COP30_*/`
- Producer: Copernicus DEM, derived from TanDEM-X; provided under COPERNICUS by the European Union and ESA.
- Terms: free to use **with attribution**. Downloaded via OpenTopography.
- Source: <https://opentopography.org/> ; <https://spacedata.copernicus.eu/>.
- **Required attribution** (reproduce when redistributing):

  > © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union
  > and ESA; all rights reserved.

- **Dataset citation:**

  > European Space Agency (2024). Copernicus Global Digital Elevation Model. Distributed by OpenTopography.
  > <https://doi.org/10.5069/G9028PQB>. Accessed 2026-06-28.

### Natural Earth coastlines — `res/ne_110m_coastline.geojson`
- Producer: Natural Earth.
- Terms: public domain.
- Source: <https://www.naturalearthdata.com/> (via
  <https://github.com/nvkelso/natural-earth-vector>).

---

*Licenses are summarized here for orientation; the authoritative terms are those of each project/dataset at the links
above. This file is informational and is not legal advice.*
