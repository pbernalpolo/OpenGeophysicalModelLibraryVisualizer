package processing;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import controlP5.ControlP5;
import controlP5.RadioButton;
import controlP5.Slider;
import geophysicalModelLibrary.gravity.Egm2008;
import geophysicalModelLibrary.terrain.EsriAsciiGridElevationModel;
import numericalLibrary.types.Vector3;
import utils.Coastlines;

import processing.core.PApplet;
import processing.core.PShape;
import processing.core.PVector;
import processing.event.MouseEvent;



/**
 * Interactive 3D visualization of digital elevation model (DEM) regions draped on the globe.
 * <p>
 * The Earth is drawn as a globe with coastlines, like the magnetic and gravity visualizers. The downloaded Copernicus
 * GLO-30 regions are rendered as lit, hypsometrically tinted terrain meshes sitting at their true geographic location:
 * each cell is placed at the geocentric radius that corresponds to its height above the ellipsoid, obtained as the
 * EGM2008 geoid undulation (the mean-sea-level offset, {@code N}) plus the DEM's orthometric elevation ({@code H}). The
 * terrain relief is radially exaggerated (a slider) so it is visible at globe scale, and a radio button flies the camera
 * down to each region.
 * <p>
 * Interaction:
 * <ul>
 * <li> Drag with the mouse to rotate the globe (away from the controls).
 * <li> Mouse wheel to zoom in and out.
 * </ul>
 */
public class PlotElevationModel
	extends PApplet
	implements Coastlines.RadiusProvider
{
	////////////////////////////////////////////////////////////////
	/// CONSTANTS
	////////////////////////////////////////////////////////////////

	/**
	 * Radius of the globe in world units.
	 */
	private static final float GLOBE_RADIUS = 300.0f;

	/**
	 * Perspective vertical field of view in degrees.
	 */
	private static final float CAMERA_FOV_DEGREES = 45.0f;

	/**
	 * Small radial offset that keeps coastline strokes above the globe surface without visibly floating.
	 */
	private static final float COASTLINE_SURFACE_OFFSET = 0.8f;

	/**
	 * Maximum spherical harmonic degree loaded from EGM2008 for the geoid undulation.
	 */
	private static final int EGM_LOAD_DEGREE = 360;

	/**
	 * Fallback Earth radius if the geoid model fails to load. [m]
	 */
	private static final double FALLBACK_EARTH_RADIUS = 6371000.0;

	/**
	 * Largest number of mesh samples per side; larger grids are decimated to keep each mesh light.
	 */
	private static final int MAX_MESH_SAMPLES_PER_SIDE = 300;

	/**
	 * Default and maximum radial relief exaggeration (the slider range starts at 1).
	 */
	private static final float DEFAULT_VERTICAL_EXAGGERATION = 10.0f;
	private static final float MAX_VERTICAL_EXAGGERATION = 100.0f;

	/**
	 * Names of the selectable regions, in radio-button order.
	 */
	private static final String[] REGION_NAMES = { "Murcia" , "Toulouse" };

	/**
	 * Raster sub-directory of each region, in the same order as {@link #REGION_NAMES}.
	 */
	private static final String[] REGION_DIRECTORIES = { "rasters_COP30_murcia" , "rasters_COP30_toulouse" };

	/**
	 * Directory holding the region rasters, relative to the working directory.
	 */
	private static final String RASTER_DIRECTORY = "res/terrain/";

	/**
	 * File name of the ESRI ASCII Grid inside each region directory.
	 */
	private static final String RASTER_FILE_NAME = "output_hh.asc";

	/**
	 * Location of the EGM2008 {@code .gfc} file, relative to the working directory.
	 */
	private static final String GFC_PATH = "res/gravity/EGM2008.gfc";



	////////////////////////////////////////////////////////////////
	/// STATE
	////////////////////////////////////////////////////////////////

	/**
	 * EGM2008 model used to compute the geoid undulation (mean sea level above the ellipsoid).
	 */
	private Egm2008 geoidModel;

	/**
	 * Reference (ellipsoid) Earth radius the relief heights are scaled against. [m]
	 */
	private double earthRadius = FALLBACK_EARTH_RADIUS;

	/**
	 * Loaded elevation model of each region (null if it failed to load).
	 */
	private final EsriAsciiGridElevationModel[] regionModels = new EsriAsciiGridElevationModel[ REGION_NAMES.length ];

	/**
	 * Geographic center, geoid undulation, framing camera altitude, and elevation range of each region.
	 */
	private final double[] regionCenterLatitude = new double[ REGION_NAMES.length ];
	private final double[] regionCenterLongitude = new double[ REGION_NAMES.length ];
	private final double[] regionUndulation = new double[ REGION_NAMES.length ];
	private final float[] regionFrameAltitude = new float[ REGION_NAMES.length ];
	private final float[] regionMinElevation = new float[ REGION_NAMES.length ];
	private final float[] regionMaxElevation = new float[ REGION_NAMES.length ];

	/**
	 * Retained terrain mesh of each region, rebuilt when the exaggeration changes.
	 */
	private final PShape[] regionMeshes = new PShape[ REGION_NAMES.length ];

	/**
	 * Coastline line strips at the globe surface.
	 */
	private Coastlines coastlines = Coastlines.empty( "coastlines: not loaded" );

	/**
	 * ControlP5 instance and its controls.
	 */
	private ControlP5 controls;
	private RadioButton regionRadio;
	private Slider exaggerationSlider;

	/**
	 * Region the camera was last flown to, to detect radio changes.
	 */
	private int selectedRegion = -1;

	/**
	 * Vertical exaggeration for which the meshes were built, to detect slider changes.
	 */
	private double builtExaggeration = Double.NaN;

	/**
	 * Latitude and longitude (in degrees) of the point brought in front of the camera.
	 */
	private double centerLatitude = 40.0;
	private double centerLongitude = 0.0;

	/**
	 * Camera altitude above the globe surface, in world units (zoom).
	 */
	private float cameraAltitude = GLOBE_RADIUS;

	/**
	 * Message shown if the geoid model could not be loaded.
	 */
	private String geoidLoadError;



	////////////////////////////////////////////////////////////////
	/// MAIN
	////////////////////////////////////////////////////////////////

	public static void main( String[] args )
	{
		PApplet.main( "processing.PlotElevationModel" );
	}



	////////////////////////////////////////////////////////////////
	/// PROCESSING LIFECYCLE
	////////////////////////////////////////////////////////////////

	public void settings()
	{
		System.setProperty( "jogl.disable.opengles" , "false" );
		System.setProperty( "jogamp.gluegen.UseTempJarCache" , "false" );
		size( 1100 , 850 , P3D );
		smooth( 8 );
	}


	public void setup()
	{
		textSize( 13 );
		loadGeoidModel();
		for( int i=0; i<REGION_NAMES.length; i++ ) {
			loadRegion( i );
		}
		this.coastlines = Coastlines.loadNaturalEarth110m( GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET );

		this.controls = new ControlP5( this );
		this.controls.setAutoDraw( false );
		this.regionRadio = this.controls.addRadioButton( "fly to region" )
				.setPosition( this.width - 160 , 24 ).setSize( 14 , 14 ).setSpacingRow( 6 );
		for( int i=0; i<REGION_NAMES.length; i++ ) {
			this.regionRadio.addItem( REGION_NAMES[i] , i );
		}
		this.regionRadio.activate( 0 );
		this.exaggerationSlider = this.controls.addSlider( "vertical exaggeration" )
				.setPosition( 20 , this.height - 32 ).setSize( 320 , 18 )
				.setRange( 1.0f , MAX_VERTICAL_EXAGGERATION ).setValue( DEFAULT_VERTICAL_EXAGGERATION );
	}


	public void draw()
	{
		background( 8 );

		int region = selectedValue( this.regionRadio , 0 );
		if( region != this.selectedRegion ) {
			flyToRegion( region );
			this.selectedRegion = region;
		}

		double exaggeration = this.exaggerationSlider.getValue();
		if( exaggeration != this.builtExaggeration ) {
			for( int i=0; i<REGION_NAMES.length; i++ ) {
				this.regionMeshes[i] = buildRegionMesh( i , (float) exaggeration );
			}
			this.builtExaggeration = exaggeration;
		}

		applyProjectionAndCamera();

		// Globe and terrain are lit by an ambient term plus an oblique directional "sun" that brings out the relief.
		ambientLight( 110 , 110 , 116 );
		float lightNorm = (float) Math.sqrt( 0.25f * 0.25f + 0.85f * 0.85f + 0.45f * 0.45f );
		directionalLight( 185 , 183 , 172 , 0.25f / lightNorm , 0.85f / lightNorm , 0.45f / lightNorm );
		noStroke();
		fill( 45 );
		sphere( GLOBE_RADIUS );
		for( PShape mesh : this.regionMeshes ) {
			if( mesh != null ) {
				shape( mesh );
			}
		}

		// Coastlines are drawn unlit with their own color.
		noLights();
		this.coastlines.updateGeometry( this );
		this.coastlines.draw( this );

		drawHeadsUpDisplay( headsUpText( region , exaggeration ) );
	}



	////////////////////////////////////////////////////////////////
	/// INTERACTION
	////////////////////////////////////////////////////////////////

	public void mouseDragged()
	{
		if( this.controls.isMouseOver() ) {
			return;
		}
		// Screen-space drag: one pixel maps to the surface arc one pixel covers at the current zoom, so the drag feel
		// stays constant whether the whole globe or a single region fills the view.
		double worldPerPixel = 2.0 * this.cameraAltitude * Math.tan( radians( CAMERA_FOV_DEGREES * 0.5f ) ) / this.height;
		double degreesPerPixel = Math.min( Math.toDegrees( worldPerPixel / GLOBE_RADIUS ) , 0.5 );
		this.centerLatitude += ( mouseY - pmouseY ) * degreesPerPixel;
		this.centerLongitude -= ( mouseX - pmouseX ) * degreesPerPixel / Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.1 );
		this.centerLatitude = Math.max( -89.0 , Math.min( 89.0 , this.centerLatitude ) );
	}


	public void mouseWheel( MouseEvent event )
	{
		if( this.controls.isMouseOver() ) {
			return;
		}
		float factor = ( event.getCount() > 0 ) ? 1.2f : 1.0f / 1.2f;
		this.cameraAltitude = constrain( this.cameraAltitude * factor , GLOBE_RADIUS * 0.0008f , GLOBE_RADIUS * 40.0f );
	}



	////////////////////////////////////////////////////////////////
	/// TERRAIN MESH
	////////////////////////////////////////////////////////////////

	/**
	 * Builds the terrain mesh of a region, draped on the globe at the given vertical exaggeration.
	 * <p>
	 * Each sampled cell is placed at the geocentric radius corresponding to its height above the ellipsoid:
	 * the region's geoid undulation {@code N} (from EGM2008, the mean-sea-level offset) plus its orthometric elevation
	 * {@code H} (from the DEM), with {@code H} radially exaggerated so the relief is visible at globe scale. Per-vertex
	 * normals (from the neighbor positions) give the directional light something to shade, and each vertex is tinted by
	 * its elevation.
	 *
	 * @param regionIndex	index into {@link #REGION_NAMES}.
	 * @param exaggeration	radial relief exaggeration factor.
	 * @return	terrain mesh shape, or {@code null} if the region failed to load.
	 */
	private PShape buildRegionMesh( int regionIndex , float exaggeration )
	{
		EsriAsciiGridElevationModel model = this.regionModels[ regionIndex ];
		if( model == null ) {
			return null;
		}

		int stride = Math.max( 1 , (int) Math.ceil(
				Math.max( model.rowCount() , model.columnCount() ) / (double) MAX_MESH_SAMPLES_PER_SIDE ) );
		int sampledRows = ( model.rowCount() - 1 ) / stride + 1;
		int sampledColumns = ( model.columnCount() - 1 ) / stride + 1;

		// Radius at mean sea level (the geoid), constant over the small region, and the meters-to-units relief scale.
		float baseRadius = (float) ( GLOBE_RADIUS * ( this.earthRadius + this.regionUndulation[ regionIndex ] ) / this.earthRadius );
		float reliefScale = (float) ( exaggeration * GLOBE_RADIUS / this.earthRadius );

		int count = sampledRows * sampledColumns;
		float[] elevation = new float[ count ];
		boolean[] valid = new boolean[ count ];
		float[] positionX = new float[ count ];
		float[] positionY = new float[ count ];
		float[] positionZ = new float[ count ];
		float lowest = Float.POSITIVE_INFINITY;
		float highest = Float.NEGATIVE_INFINITY;

		for( int i=0; i<sampledRows; i++ ) {
			int row = Math.min( i * stride , model.rowCount() - 1 );
			double latitudeRad = model.latitudeOfRow( row );
			double cosLatitude = Math.cos( latitudeRad );
			double sinLatitude = Math.sin( latitudeRad );
			for( int j=0; j<sampledColumns; j++ ) {
				int column = Math.min( j * stride , model.columnCount() - 1 );
				double longitudeRad = model.longitudeOfColumn( column );
				double geoX = cosLatitude * Math.cos( longitudeRad );
				double geoY = cosLatitude * Math.sin( longitudeRad );
				double geoZ = sinLatitude;

				double height = model.elevation( row , column );
				int index = i * sampledColumns + j;
				float radius;
				if( Double.isNaN( height ) ) {
					valid[ index ] = false;
					radius = baseRadius;
				} else {
					valid[ index ] = true;
					elevation[ index ] = (float) height;
					lowest = Math.min( lowest , (float) height );
					highest = Math.max( highest , (float) height );
					radius = baseRadius + reliefScale * (float) height;
				}
				// Geocentric direction mapped into the display frame: geo ( x , y , z ) -> display ( y , -z , x ).
				positionX[ index ] = radius * (float) geoY;
				positionY[ index ] = radius * (float) -geoZ;
				positionZ[ index ] = radius * (float) geoX;
			}
		}
		this.regionMinElevation[ regionIndex ] = lowest;
		this.regionMaxElevation[ regionIndex ] = highest;
		float elevationSpan = ( highest > lowest ) ? ( highest - lowest ) : 1.0f;

		PShape mesh = createShape();
		mesh.beginShape( TRIANGLES );
		mesh.noStroke();
		for( int i=0; i<sampledRows-1; i++ ) {
			for( int j=0; j<sampledColumns-1; j++ ) {
				int a = i * sampledColumns + j;
				int b = i * sampledColumns + ( j + 1 );
				int c = ( i + 1 ) * sampledColumns + ( j + 1 );
				int d = ( i + 1 ) * sampledColumns + j;
				if( !valid[a]  ||  !valid[b]  ||  !valid[c]  ||  !valid[d] ) {
					continue;
				}
				addMeshVertex( mesh , a , i , j , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
				addMeshVertex( mesh , b , i , j + 1 , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
				addMeshVertex( mesh , c , i + 1 , j + 1 , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
				addMeshVertex( mesh , a , i , j , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
				addMeshVertex( mesh , c , i + 1 , j + 1 , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
				addMeshVertex( mesh , d , i + 1 , j , positionX , positionY , positionZ , sampledRows , sampledColumns , elevation , lowest , elevationSpan );
			}
		}
		mesh.endShape();
		return mesh;
	}


	/**
	 * Appends one mesh vertex with its outward surface normal (from the neighbor positions) and hypsometric color.
	 *
	 * @param mesh			mesh being built.
	 * @param index			vertex index into the sampled arrays.
	 * @param i				sampled row of the vertex.
	 * @param j				sampled column of the vertex.
	 * @param positionX		sampled display-frame x positions.
	 * @param positionY		sampled display-frame y positions.
	 * @param positionZ		sampled display-frame z positions.
	 * @param sampledRows		number of sampled rows.
	 * @param sampledColumns	number of sampled columns.
	 * @param elevation		sampled elevations. [m]
	 * @param lowest		minimum sampled elevation. [m]
	 * @param elevationSpan	elevation range used to normalize the color. [m]
	 */
	private void addMeshVertex( PShape mesh , int index , int i , int j ,
			float[] positionX , float[] positionY , float[] positionZ ,
			int sampledRows , int sampledColumns , float[] elevation , float lowest , float elevationSpan )
	{
		int west = i * sampledColumns + Math.max( j - 1 , 0 );
		int east = i * sampledColumns + Math.min( j + 1 , sampledColumns - 1 );
		int north = Math.max( i - 1 , 0 ) * sampledColumns + j;
		int south = Math.min( i + 1 , sampledRows - 1 ) * sampledColumns + j;

		// Tangent vectors west-east and south-north, then their cross product, oriented outward.
		float lonX = positionX[east] - positionX[west];
		float lonY = positionY[east] - positionY[west];
		float lonZ = positionZ[east] - positionZ[west];
		float latX = positionX[north] - positionX[south];
		float latY = positionY[north] - positionY[south];
		float latZ = positionZ[north] - positionZ[south];
		float normalX = lonY * latZ - lonZ * latY;
		float normalY = lonZ * latX - lonX * latZ;
		float normalZ = lonX * latY - lonY * latX;
		if( normalX * positionX[index] + normalY * positionY[index] + normalZ * positionZ[index] < 0.0f ) {
			normalX = -normalX;
			normalY = -normalY;
			normalZ = -normalZ;
		}
		float normalNorm = (float) Math.sqrt( normalX * normalX + normalY * normalY + normalZ * normalZ );
		if( normalNorm < 1.0e-9f ) {
			normalX = positionX[index];
			normalY = positionY[index];
			normalZ = positionZ[index];
			normalNorm = (float) Math.sqrt( normalX * normalX + normalY * normalY + normalZ * normalZ );
		}

		float t = ( elevation[index] - lowest ) / elevationSpan;
		mesh.normal( normalX / normalNorm , normalY / normalNorm , normalZ / normalNorm );
		mesh.fill( elevationColor( t ) );
		mesh.vertex( positionX[index] , positionY[index] , positionZ[index] );
	}


	/**
	 * Maps a normalized elevation to a hypsometric tint: greens in the lowlands, tan and brown on the slopes, white on
	 * the highest ground.
	 *
	 * @param t		normalized elevation in [0,1] (clamped).
	 * @return	packed color.
	 */
	private int elevationColor( float t )
	{
		t = constrain( t , 0.0f , 1.0f );
		if( t < 0.35f ) {
			return lerpColor( color( 56 , 110 , 62 ) , color( 120 , 160 , 72 ) , t / 0.35f );
		} else if( t < 0.60f ) {
			return lerpColor( color( 120 , 160 , 72 ) , color( 184 , 152 , 96 ) , ( t - 0.35f ) / 0.25f );
		} else if( t < 0.80f ) {
			return lerpColor( color( 184 , 152 , 96 ) , color( 132 , 96 , 72 ) , ( t - 0.60f ) / 0.20f );
		} else {
			return lerpColor( color( 132 , 96 , 72 ) , color( 236 , 236 , 236 ) , ( t - 0.80f ) / 0.20f );
		}
	}



	////////////////////////////////////////////////////////////////
	/// RENDERING HELPERS
	////////////////////////////////////////////////////////////////

	/**
	 * Sets the perspective projection and positions the camera so the view center faces it.
	 */
	private void applyProjectionAndCamera()
	{
		float distance = GLOBE_RADIUS + this.cameraAltitude;
		float near = Math.max( GLOBE_RADIUS * 0.001f , this.cameraAltitude * 0.2f );
		perspective( radians( CAMERA_FOV_DEGREES ) , (float) this.width / this.height , near , GLOBE_RADIUS * 100.0f );

		PVector direction = surfaceDirection( this.centerLatitude , this.centerLongitude );
		PVector eye = PVector.mult( direction , distance );

		// Processing's P3D frame has +Y downward, so camera() uses a screen-down vector.
		PVector north = localNorthDirection( this.centerLatitude , this.centerLongitude );
		PVector screenDown = PVector.mult( north , -1.0f );
		screenDown.sub( PVector.mult( direction , screenDown.dot( direction ) ) );
		if( screenDown.magSq() < 1.0e-6 ) {
			screenDown = new PVector( 0 , 1 , 0 );
		}
		screenDown.normalize();

		camera( eye.x , eye.y , eye.z , 0 , 0 , 0 , screenDown.x , screenDown.y , screenDown.z );
	}


	/**
	 * Points the camera at a region's center and zooms in to frame it.
	 *
	 * @param regionIndex	index into {@link #REGION_NAMES}.
	 */
	private void flyToRegion( int regionIndex )
	{
		if( this.regionModels[ regionIndex ] == null ) {
			return;
		}
		this.centerLatitude = this.regionCenterLatitude[ regionIndex ];
		this.centerLongitude = this.regionCenterLongitude[ regionIndex ];
		this.cameraAltitude = this.regionFrameAltitude[ regionIndex ];
	}


	/**
	 * Draws the heads-up text and the controls in screen space.
	 *
	 * @param info	text to show at the top-left.
	 */
	private void drawHeadsUpDisplay( String info )
	{
		hint( DISABLE_DEPTH_TEST );
		camera();
		perspective();
		noLights();
		fill( 230 );
		textAlign( LEFT , TOP );
		text( info , 12 , 12 );
		this.controls.draw();
		hint( ENABLE_DEPTH_TEST );
	}


	/**
	 * Returns the heads-up text describing the current view.
	 *
	 * @param region		region currently selected.
	 * @param exaggeration	current vertical exaggeration.
	 * @return	heads-up text.
	 */
	private String headsUpText( int region , double exaggeration )
	{
		if( this.regionModels[ region ] == null ) {
			return REGION_NAMES[ region ] + ": raster not found.";
		}
		String warning = ( this.geoidLoadError != null )
				? "WARNING: " + this.geoidLoadError + " - continuing with geoid undulation N = 0\n"
				: "";
		return warning + String.format(
				"%s  (Copernicus GLO-30 DSM on the globe; hypsometric tint)%n" +
				"drag: rotate   wheel: zoom%n" +
				"geoid undulation N: %.1f m   elevation: %.0f to %.0f m%n" +
				"radial exaggeration: %.0fx" ,
				REGION_NAMES[ region ] ,
				this.regionUndulation[ region ] ,
				this.regionMinElevation[ region ] , this.regionMaxElevation[ region ] ,
				exaggeration );
	}


	/**
	 * Returns the outward unit direction of the globe surface at a point, mapped into Processing's display frame.
	 *
	 * @param latitudeDegrees	latitude. [deg]
	 * @param longitudeDegrees	longitude. [deg]
	 * @return	outward unit direction.
	 */
	private PVector surfaceDirection( double latitudeDegrees , double longitudeDegrees )
	{
		double latitudeRad = Math.toRadians( latitudeDegrees );
		double longitudeRad = Math.toRadians( longitudeDegrees );
		double cosLatitude = Math.cos( latitudeRad );
		double geoX = cosLatitude * Math.cos( longitudeRad );
		double geoY = cosLatitude * Math.sin( longitudeRad );
		double geoZ = Math.sin( latitudeRad );
		return new PVector( (float) geoY , (float) -geoZ , (float) geoX );
	}


	/**
	 * Returns local geodetic north at a point, mapped into Processing's display frame.
	 *
	 * @param latitudeDegrees	latitude. [deg]
	 * @param longitudeDegrees	longitude. [deg]
	 * @return	unit tangent direction pointing north.
	 */
	private PVector localNorthDirection( double latitudeDegrees , double longitudeDegrees )
	{
		double latitudeRad = Math.toRadians( latitudeDegrees );
		double longitudeRad = Math.toRadians( longitudeDegrees );
		double sinLatitude = Math.sin( latitudeRad );
		double cosLatitude = Math.cos( latitudeRad );
		double geoX = -sinLatitude * Math.cos( longitudeRad );
		double geoY = -sinLatitude * Math.sin( longitudeRad );
		double geoZ = cosLatitude;
		return new PVector( (float) geoY , (float) -geoZ , (float) geoX );
	}


	/**
	 * Returns the value of the active item of a radio button, or a fallback if none is active.
	 *
	 * @param radio			radio button to read.
	 * @param fallbackValue	value to return when no item is active.
	 * @return	active item value, or the fallback.
	 */
	private static int selectedValue( RadioButton radio , int fallbackValue )
	{
		float value = radio.getValue();
		return ( value < 0.0f ) ? fallbackValue : (int) value;
	}



	////////////////////////////////////////////////////////////////
	/// COASTLINE RADIUS PROVIDER
	////////////////////////////////////////////////////////////////

	/**
	 * {@inheritDoc}
	 * <p>
	 * The coastlines sit on the globe surface (no relief), so the radius is constant.
	 */
	@Override
	public float radiusAt( double latitudeDegrees , double longitudeDegrees )
	{
		return GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET;
	}



	////////////////////////////////////////////////////////////////
	/// MODEL LOADING
	////////////////////////////////////////////////////////////////

	/**
	 * Loads EGM2008 for the geoid undulation, recording an error and falling back to a spherical Earth if it is missing.
	 */
	private void loadGeoidModel()
	{
		if( !Files.exists( Paths.get( GFC_PATH ) ) ) {
			this.geoidLoadError = "EGM2008.gfc not found at " + GFC_PATH;
			return;
		}
		try {
			this.geoidModel = Egm2008.fromFilePathAndMaximumDegree( GFC_PATH , EGM_LOAD_DEGREE );
			this.earthRadius = this.geoidModel.referenceRadius();
		} catch( IOException e ) {
			this.geoidLoadError = e.getMessage();
		}
	}


	/**
	 * Loads a region's raster and computes its center, geoid undulation, framing altitude, leaving its model null on error.
	 *
	 * @param regionIndex	index into {@link #REGION_NAMES}.
	 */
	private void loadRegion( int regionIndex )
	{
		String path = locateRegionPath( regionIndex );
		if( path == null ) {
			return;
		}
		EsriAsciiGridElevationModel model;
		try {
			model = EsriAsciiGridElevationModel.fromFilePath( path );
		} catch( IOException e ) {
			return;
		}
		this.regionModels[ regionIndex ] = model;

		double westRad = model.longitudeOfColumn( 0 );
		double eastRad = model.longitudeOfColumn( model.columnCount() - 1 );
		double northRad = model.latitudeOfRow( 0 );
		double southRad = model.latitudeOfRow( model.rowCount() - 1 );
		double centerLatitudeRadians = 0.5 * ( northRad + southRad );
		double centerLongitudeRadians = 0.5 * ( westRad + eastRad );
		this.regionCenterLatitude[ regionIndex ] = Math.toDegrees( centerLatitudeRadians );
		this.regionCenterLongitude[ regionIndex ] = Math.toDegrees( centerLongitudeRadians );
		this.regionUndulation[ regionIndex ] = geoidUndulationAt( this.regionCenterLatitude[ regionIndex ] , this.regionCenterLongitude[ regionIndex ] );

		// Frame the region: its larger angular span as a globe arc, backed off to fit the field of view.
		double cosLat = Math.cos( centerLatitudeRadians );
		double spanLatRad = northRad - southRad;
		double spanLonRad = ( eastRad - westRad ) * cosLat;
		float displaySpan = (float) ( GLOBE_RADIUS * Math.max( spanLatRad , spanLonRad ) );
		float frameAltitude = 1.6f * ( displaySpan * 0.5f ) / (float) Math.tan( radians( CAMERA_FOV_DEGREES * 0.5f ) );
		this.regionFrameAltitude[ regionIndex ] = Math.max( frameAltitude , GLOBE_RADIUS * 0.0008f );
	}


	/**
	 * Returns the geoid undulation at a point on the ellipsoid surface, or 0 if the geoid model is unavailable.
	 *
	 * @param latitudeDegrees	latitude. [deg]
	 * @param longitudeDegrees	longitude. [deg]
	 * @return	geoid undulation. [m]
	 */
	private double geoidUndulationAt( double latitudeDegrees , double longitudeDegrees )
	{
		if( this.geoidModel == null ) {
			return 0.0;
		}
		double latitudeRad = Math.toRadians( latitudeDegrees );
		double longitudeRad = Math.toRadians( longitudeDegrees );
		double cosLatitude = Math.cos( latitudeRad );
		double geoX = cosLatitude * Math.cos( longitudeRad );
		double geoY = cosLatitude * Math.sin( longitudeRad );
		double geoZ = Math.sin( latitudeRad );
		this.geoidModel.setPosition( Vector3.fromComponents( this.earthRadius * geoX , this.earthRadius * geoY , this.earthRadius * geoZ ) );
		return this.geoidModel.getGeoidUndulation();
	}


	/**
	 * Returns the path to a region's raster, searching the candidate base directories, or {@code null} if not found.
	 *
	 * @param regionIndex	index into {@link #REGION_DIRECTORIES}.
	 * @return	path to the {@code .asc} file, or {@code null}.
	 */
	private static String locateRegionPath( int regionIndex )
	{
		String path = RASTER_DIRECTORY + REGION_DIRECTORIES[ regionIndex ] + "/" + RASTER_FILE_NAME;
		return Files.exists( Paths.get( path ) ) ? path : null;
	}

}
