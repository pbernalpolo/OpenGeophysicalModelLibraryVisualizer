# OpenGeophysicalModelLibraryVisualizer

Interactive 3D [Processing](https://processing.org/) visualizers for the geophysical models in
[OpenGeophysicalModelLibrary-java](https://github.com/pbernalpolo/OpenGeophysicalModelLibrary-java), which is included
here as a git submodule. The Earth is drawn as a globe with coastlines, and each app renders one model on top of it.

## Visualizers

All three live in [`src/apps/java/processing/`](src/apps/java/processing/) as Processing `PApplet`s (each has a
`main`). Common interaction: **drag** to rotate the globe, **mouse wheel** to zoom. The drag is *screen-space* — the
point under the cursor stays under it at every zoom level.

| App | Shows | Notable controls |
|-----|-------|------------------|
| `PlotMagneticFieldModel` | World Magnetic Model (WMM 2025) as field glyphs over a globe shell at a selectable altitude. Each glyph is a stick capped by a small pyramid. | Year slider (secular variation), altitude slider, and a radio to pick the representation: 3D vector (colored by intensity), horizontal needle, or horizontal magnitude (both colored by the vertical component). |
| `PlotGravityModel` | EGM2008 gravity anomaly and geoid undulation, as colored relief on two globes. | Camera drag / zoom; meshes regenerate on space bar. |
| `PlotElevationModel` | Copernicus GLO-30 terrain (Murcia, Toulouse) draped on the globe as a lit, hypsometrically tinted mesh. | "Fly to region" radio (zooms the camera down to a zone) and a vertical-exaggeration slider. |

## Repository layout

```
src/apps/java/processing/   the three visualizer apps
src/main/java/utils/        Coastlines (Natural Earth GeoJSON loader / globe drape)
lib/OpenGeophysicalModelLibrary-java/   the model library (git submodule)
lib/processing-4.3-linux-arm64/         bundled Processing 4.3 core (platform-specific)
lib/controlP5-2.2.6/                    bundled ControlP5 (sliders / radio buttons)
res/                        data files loaded at runtime (see res/README.md)
```

## Getting started

This repo uses a submodule, so clone recursively (or initialize it after cloning):

```bash
git clone --recurse-submodules <repo-url>
# or, in an existing clone:
git submodule update --init
```

**Dependencies** (bundled under `lib/`): Processing 4.3 `core.jar` and ControlP5 2.2.6. The bundled Processing is
`linux-arm64`; on another platform, point the classpath at your own Processing 4.x `core.jar`. The apps open an OpenGL
(`P3D`) window, so they need a display — they can't run headless. The library uses Java 9+ language features (private
interface methods); it builds and runs on JDK 17.

**Build & run.** The project is set up for Eclipse (`.classpath` / `.project`) and Maven (`pom.xml`); the simplest path
is to run an app's `main` from your IDE. From the command line (adjust the jar paths to your platform):

```bash
CP5=lib/controlP5-2.2.6/controlP5/library/controlP5.jar
PROC=lib/processing-4.3-linux-arm64/processing-4.3/core/library/core.jar
GEO=lib/OpenGeophysicalModelLibrary-java/src/main/java
NUM=lib/OpenGeophysicalModelLibrary-java/lib/OpenNumericalLibrary-java/src/main/java
mkdir -p out
javac -cp "$PROC:$CP5" -d out \
  $(find "$GEO" "$NUM" -name '*.java') \
  src/main/java/utils/Coastlines.java \
  src/apps/java/processing/PlotMagneticFieldModel.java
java -cp "out:$PROC:$CP5" processing.PlotMagneticFieldModel
```

> **Run from the repository root.** Data is looked up at `res/<package>/…` relative to the working directory, so the
> working directory must be the repo root. See [`res/README.md`](res/README.md).

## Design notes worth knowing

These are the non-obvious conventions the apps rely on (each is documented in more detail in the relevant class):

- **Globe display frame.** A geocentric unit direction `(x, y, z)` (with `x = cosLat·cosLon`, `y = cosLat·sinLon`,
  `z = sinLat`) is mapped to Processing's frame as `(y, -z, x)`. The camera is positioned by latitude/longitude of the
  view center plus an altitude (zoom).
- **Terrain on the globe.** `PlotElevationModel` places each terrain vertex at the geocentric radius
  `R·(a + N)/a + exaggeration·(R/a)·H`, where `N` is the EGM2008 geoid undulation (mean sea level above the ellipsoid,
  from the gravity library) and `H` is the DEM's orthometric elevation. Real relief is invisible at globe scale, so it
  is radially exaggerated (a slider). The Copernicus data is a **DSM** — it includes buildings and vegetation, not bare
  earth.
- **Elevation model API is in radians.** `ElevationModel.elevationAt(latitudeRadians, longitudeRadians)` and the grid
  accessors all use radians; the ESRI `.asc` header (degrees) is converted on load.
- **Coastlines** are loaded once from a Natural Earth GeoJSON and draped on the globe surface; all three apps share
  `utils.Coastlines`.

## License

The visualizer source under `src/` is licensed under **GPL-3.0-or-later** — see [`LICENSE`](LICENSE). The bundled
dependencies under `lib/` (Processing and JOGL/GlueGen, ControlP5) and the datasets under `res/` are **not** covered by
that license; they retain their own terms, listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) (the Copernicus
DEM in particular requires attribution). The `OpenGeophysicalModelLibrary-java` submodule is separately licensed (MIT).
