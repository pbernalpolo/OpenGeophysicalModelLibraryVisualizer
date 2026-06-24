package processing;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import geophysicalModelLibrary.Egm2008;
import numericalLibrary.functions.SphericalHarmonicsEvaluator;
import utils.Coastlines;

import processing.core.PApplet;
import processing.core.PGraphics;
import processing.core.PShape;
import processing.core.PVector;
import processing.event.MouseEvent;



/**
 * Interactive 3D visualization of the EGM2008 model on two synchronized globes.
 * <p>
 * The left globe shows the gravity anomaly (in mGal) and the right globe shows the geoid undulation (in m), both computed
 * from the spherical harmonic coefficients with the even zonal harmonics of the GRS80 reference ellipsoid removed:
 * <ul>
 * <li> anomaly:    delta_g = ( GM / a^2 ) sum_{l=2}^{L} ( l - 1 ) sum_{m=0}^{l} P_l^m( cos theta ) [ dC_l^m cos( m lambda ) + S_l^m sin( m lambda ) ]
 * <li> undulation: N       = a           sum_{l=2}^{L}           sum_{m=0}^{l} P_l^m( cos theta ) [ dC_l^m cos( m lambda ) + S_l^m sin( m lambda ) ]
 * </ul>
 * where  dC_l^m  is the model coefficient minus the reference ellipsoid coefficient (only the even zonal terms differ).
 * <p>
 * Both globes share the same camera. Interaction:
 * <ul>
 * <li> Drag with the mouse to change the central latitude and longitude brought in front of the camera.
 * <li> Mouse wheel to zoom in and out.
 * <li> Space bar to regenerate both colored meshes for the current view.
 * </ul>
 * The meshes stay fixed while navigating and are regenerated only when requested. A fixed number of grid points is
 * evaluated in latitude and longitude; when regeneration is requested, the actual coordinates of those points cover the
 * currently visible spherical cap, so the resolution increases as the view zooms in. The spherical harmonic degree used
 * for the evaluation is adapted to the angular size of the grid cells (and capped at the loaded degree). Each globe is
 * rendered into its own square off-screen buffer and composited side by side.
 */
public class PlotGravityModel
	extends PApplet
	implements Coastlines.RadiusProvider
{
	////////////////////////////////////////////////////////////////
	/// CONSTANTS
	////////////////////////////////////////////////////////////////

	/**
	 * Maximum spherical harmonic degree loaded from the model. Higher values show finer detail when zoomed in,
	 * at the cost of a longer load time and slower mesh regeneration.
	 */
	private static final int LOAD_DEGREE = 360;

	/**
	 * Number of grid points evaluated along latitude and along longitude. This count is fixed; the coordinates change with the view.
	 */
	private static final int GRID_N = 128;

	/**
	 * Radius of the globe in world units.
	 */
	private static final float GLOBE_RADIUS = 300.0f;

	/**
	 * Perspective vertical field of view in degrees.
	 */
	private static final double CAMERA_FOV_DEGREES = 50.0;

	/**
	 * When the visible vertical half-span reaches this value (zoomed out far enough), the whole sphere is meshed instead of
	 * only the visible cap, so the globe can then be rotated without regenerating.
	 */
	private static final double GLOBAL_MODE_HALF_SPAN_DEGREES = 60.0;

	/**
	 * Stable symmetric color scale for the gravity anomaly. Values outside this interval saturate.
	 */
	private static final double COLOR_LIMIT_MILLIGAL = 250.0;

	/**
	 * Anomaly magnitude that reaches the full radial relief exaggeration.
	 */
	private static final double RELIEF_LIMIT_MILLIGAL = 250.0;

	/**
	 * Stable symmetric color scale for the geoid undulation. Values outside this interval saturate.
	 */
	private static final double COLOR_LIMIT_METERS = 100.0;

	/**
	 * Geoid undulation magnitude that reaches the full radial relief exaggeration.
	 */
	private static final double RELIEF_LIMIT_METERS = 100.0;

	/**
	 * Maximum signed radial relief, in world units, used to make small details easier to see.
	 */
	private static final float RELIEF_EXAGGERATION = 36.0f;

	/**
	 * Slightly reduced gray backing sphere, kept below the most negative exaggerated mesh vertex.
	 */
	private static final float GLOBE_BODY_RADIUS = GLOBE_RADIUS - RELIEF_EXAGGERATION - 2.0f;

	/**
	 * Small radial offset that keeps coastline strokes above the colored mesh without visibly floating.
	 */
	private static final float COASTLINE_SURFACE_OFFSET = 0.8f;

	/**
	 * Ambient light color (per channel, 0..255) used by the GPU lighting. Slightly cool, to tint the shadowed side.
	 */
	private static final float AMBIENT_LIGHT_R = 64.0f;
	private static final float AMBIENT_LIGHT_G = 68.0f;
	private static final float AMBIENT_LIGHT_B = 80.0f;

	/**
	 * Key (directional) light color (per channel, 0..255) used by the GPU lighting. Slightly warm.
	 */
	private static final float KEY_LIGHT_R = 216.0f;
	private static final float KEY_LIGHT_G = 210.0f;
	private static final float KEY_LIGHT_B = 198.0f;

	/**
	 * Forward (toward-camera) component of the mouse-controlled light. Smaller values make the light graze more for a given
	 * mouse offset, exaggerating relief; at the panel center the light is head-on and the relief looks flat.
	 */
	private static final float MOUSE_LIGHT_DEPTH = 0.1f;

	/**
	 * Candidate locations of the EGM2008 {@code .gfc} file, relative to the working directory.
	 */
	private static final String[] GFC_CANDIDATE_PATHS = {
			"res/EGM2008.gfc",
			"lib/OpenGeophysicalModelLibrary-java/res/EGM2008.gfc",
	};



	////////////////////////////////////////////////////////////////
	/// MODEL AND EVALUATION STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Loaded gravity model.
	 */
	private Egm2008 model;

	/**
	 * Maximum degree actually available in {@link #model}.
	 */
	private int modelMaxDegree;

	/**
	 * Evaluator of the orthonormal spherical harmonics. Its dirty-flag caching lets a whole latitude row reuse one Legendre pass.
	 */
	private SphericalHarmonicsEvaluator harmonics;

	/**
	 * Factor converting the orthonormal spherical harmonic value of order  m  into the geodetic fully normalized one:
	 * {@code (-1)^m sqrt( 4 pi ( 2 - delta_{m0} ) )}.
	 */
	private double[] geodeticFactor;

	/**
	 * Even zonal coefficients of the GRS80 reference ellipsoid, indexed by degree (only degrees 2, 4, 6, 8 are non-zero).
	 */
	private double[] ellipsoidZonal;

	/**
	 * Reused output of {@link #accumulateFieldSums(double)}: index 0 is the undulation sum, index 1 is the anomaly sum.
	 */
	private final double[] fieldSums = new double[ 2 ];

	/**
	 * Flag indicating whether the model finished loading.
	 */
	private boolean modelLoaded;

	/**
	 * Error message shown when the model could not be loaded.
	 */
	private String loadError;



	////////////////////////////////////////////////////////////////
	/// VIEW STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Latitude and longitude (in degrees) of the point brought in front of the camera.
	 */
	private double centerLatitude = 20.0;
	private double centerLongitude = 0.0;

	/**
	 * Camera altitude above the globe surface, in world units. The camera distance to the center is {@code GLOBE_RADIUS + altitude}.
	 */
	private float altitude = GLOBE_RADIUS;

	/**
	 * Flag requesting a mesh regeneration on the next frame.
	 */
	private boolean meshDirty = true;

	/**
	 * Whether the mouse position controls the light direction (toggled with the 'l' key). When active, moving the mouse
	 * sweeps the light so grazing illumination reveals relief detail. The light is applied by the GPU every frame, so
	 * this is free to change.
	 */
	private boolean mouseControlsLight;



	////////////////////////////////////////////////////////////////
	/// MESH STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Gravity anomaly field (left globe).
	 */
	private final ScalarField anomalyField = new ScalarField( "gravity anomaly (mGal)" , "mGal" , COLOR_LIMIT_MILLIGAL , RELIEF_LIMIT_MILLIGAL );

	/**
	 * Geoid undulation field (right globe).
	 */
	private final ScalarField undulationField = new ScalarField( "geoid undulation (m)" , "m" , COLOR_LIMIT_METERS , RELIEF_LIMIT_METERS );

	/**
	 * Field whose relief the coastlines are currently draped onto. Set before each {@link Coastlines#updateGeometry(Coastlines.RadiusProvider)} call.
	 */
	private ScalarField coastlineField;

	/**
	 * Whether the fields hold a valid mesh.
	 */
	private boolean meshReady;

	/**
	 * Degree used to evaluate the current mesh.
	 */
	private int viewDegree;

	/**
	 * Whether the current mesh covers the whole sphere (zoomed out) rather than only the visible cap.
	 */
	private boolean meshGlobal;

	/**
	 * View parameters used to produce the current mesh (shared by both fields). The center longitude is kept so the
	 * coastline lookup can wrap longitudes to the mesh window.
	 */
	private double meshCenterLongitude;
	private double meshLatMin;
	private double meshLatMax;
	private double meshLonMin;
	private double meshLonMax;
	private double meshCellLatDegrees;
	private double meshCellLonDegrees;



	////////////////////////////////////////////////////////////////
	/// RENDERING STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Off-screen square buffers, one per globe.
	 */
	private PGraphics viewLeft;
	private PGraphics viewRight;



	////////////////////////////////////////////////////////////////
	/// COASTLINE STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Coastline line strips converted to display-space globe coordinates.
	 */
	private Coastlines coastlines = Coastlines.empty( "coastlines: not loaded" );



	////////////////////////////////////////////////////////////////
	/// MAIN
	////////////////////////////////////////////////////////////////

	public static void main( String[] args )
	{
		PApplet.main( "processing.PlotGravityModel" );
	}



	////////////////////////////////////////////////////////////////
	/// PROCESSING LIFECYCLE
	////////////////////////////////////////////////////////////////

	// method for setting the size of the window
	public void settings()
	{
		System.setProperty( "jogl.disable.opengles" , "false" );
		System.setProperty( "jogamp.gluegen.UseTempJarCache" , "false" );
		// Twice as wide as tall: a square panel per globe.
		size( 2000 , 1000 , P3D );
		smooth( 8 );
	}


	// identical use to setup in Processing IDE except for size()
	public void setup()
	{
		textSize( 13 );
		loadModel();
		this.coastlines = Coastlines.loadNaturalEarth110m( GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET );
		// One square off-screen buffer per globe.
		this.viewLeft = createGraphics( height , height , P3D );
		this.viewRight = createGraphics( height , height , P3D );
	}


	// identical use to draw in Processing IDE
	public void draw()
	{
		background( 0 );

		if( !this.modelLoaded ) {
			drawOverlay( ( this.loadError != null ) ? this.loadError : "Loading EGM2008 ..." );
			return;
		}

		if( this.meshDirty ) {
			regenerateMesh();
			this.meshDirty = false;
		}

		renderGlobe( this.viewLeft , this.anomalyField );
		renderGlobe( this.viewRight , this.undulationField );

		image( this.viewLeft , 0 , 0 );
		image( this.viewRight , height , 0 );

		drawOverlay( hudText() );
	}



	////////////////////////////////////////////////////////////////
	/// INTERACTION
	////////////////////////////////////////////////////////////////

	public void mouseDragged()
	{
		// Sensitivity scales with the visible span so the drag feels consistent at every zoom level.
		// The panels are square, so both axes use the panel side (height) as the reference length.
		double verticalSensitivity = visibleVerticalHalfSpanDegrees() / ( height * 0.5 );
		double horizontalSensitivity = visibleHorizontalHalfSpanDegrees() / ( height * 0.5 );
		this.centerLatitude += ( mouseY - pmouseY ) * verticalSensitivity;
		this.centerLongitude -= ( mouseX - pmouseX ) * horizontalSensitivity / Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.05 );
		this.centerLatitude = constrainLatitude( this.centerLatitude );
		// The camera follows during the drag; the meshes are regenerated only when the space bar is pressed.
	}


	public void mouseWheel( MouseEvent event )
	{
		float factor = ( event.getCount() > 0 ) ? 1.2f : 1.0f / 1.2f;
		this.altitude = constrain( this.altitude * factor , GLOBE_RADIUS * 0.002f , GLOBE_RADIUS * 60.0f );
	}


	public void keyPressed()
	{
		if( this.key == ' ' ) {
			this.meshDirty = true;
		} else if( this.key == 'l'  ||  this.key == 'L' ) {
			// The light direction is read every frame in renderGlobe, so toggling is all that is needed.
			this.mouseControlsLight = !this.mouseControlsLight;
		}
	}



	////////////////////////////////////////////////////////////////
	/// MESH GENERATION
	////////////////////////////////////////////////////////////////

	/**
	 * Regenerates both colored meshes for the current view.
	 * <p>
	 * When zoomed out far enough the whole sphere is meshed (so it can be rotated without regenerating); otherwise only
	 * the visible spherical cap is meshed, at a resolution that increases with the zoom.
	 */
	private void regenerateMesh()
	{
		this.meshGlobal = visibleVerticalHalfSpanDegrees() >= GLOBAL_MODE_HALF_SPAN_DEGREES;

		double latMin;
		double latMax;
		double lonMin;
		double lonMax;
		if( this.meshGlobal ) {
			// Whole sphere: rotating then requires no regeneration.
			latMin = -90.0;
			latMax = 90.0;
			lonMin = -180.0;
			lonMax = 180.0;
		} else {
			double halfLatSpan = visibleVerticalHalfSpanDegrees();
			double cosLat = Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.05 );
			double halfLonSpan = Math.min( 180.0 , visibleHorizontalHalfSpanDegrees() / cosLat );
			latMin = constrainLatitude( this.centerLatitude - halfLatSpan );
			latMax = constrainLatitude( this.centerLatitude + halfLatSpan );
			lonMin = this.centerLongitude - halfLonSpan;
			lonMax = this.centerLongitude + halfLonSpan;
		}

		double cellLatDegrees = ( latMax - latMin ) / ( GRID_N - 1 );
		double cellLonDegrees = ( lonMax - lonMin ) / ( GRID_N - 1 );
		this.meshLatMin = latMin;
		this.meshLatMax = latMax;
		this.meshLonMin = lonMin;
		this.meshLonMax = lonMax;
		this.meshCellLatDegrees = cellLatDegrees;
		this.meshCellLonDegrees = cellLonDegrees;
		this.meshCenterLongitude = ( lonMin + lonMax ) * 0.5;

		// Adapt the evaluation degree to the angular size of the grid cells (Nyquist), capped at the loaded degree.
		this.viewDegree = (int) Math.round( 180.0 / Math.max( cellLatDegrees , 1.0e-6 ) );
		this.viewDegree = Math.max( 2 , Math.min( this.viewDegree , this.modelMaxDegree ) );

		double gravitationalParameter = this.model.gravitationalParameter();
		double referenceRadius = this.model.referenceRadius();
		double anomalyScaleToMilligal = gravitationalParameter / ( referenceRadius * referenceRadius ) * 1.0e5;

		this.anomalyField.resetRange();
		this.undulationField.resetRange();

		for( int i=0; i<GRID_N; i++ ) {
			double latitude = latMin + i * cellLatDegrees;
			double latitudeRad = Math.toRadians( latitude );
			double cosLatitude = Math.cos( latitudeRad );
			double sinLatitude = Math.sin( latitudeRad );
			// Set the colatitude once per row ( cos( colatitude ) = sin( latitude ) ): the evaluator then reuses this
			// Legendre pass while the longitude varies along the row.
			this.harmonics.setCosTheta( sinLatitude );
			for( int j=0; j<GRID_N; j++ ) {
				double longitude = lonMin + j * cellLonDegrees;
				accumulateFieldSums( longitude );
				double anomalyValue = this.fieldSums[1] * anomalyScaleToMilligal;
				double undulationValue = this.fieldSums[0] * referenceRadius;

				double longitudeRad = Math.toRadians( longitude );
				double geoX = cosLatitude * Math.cos( longitudeRad );
				double geoY = cosLatitude * Math.sin( longitudeRad );
				double geoZ = sinLatitude;

				fillVertex( this.anomalyField , i , j , geoX , geoY , geoZ , anomalyValue );
				fillVertex( this.undulationField , i , j , geoX , geoY , geoZ , undulationValue );
			}
		}

		// Colors and geometry are light-independent; the shapes are built once here and lit by the GPU every frame.
		this.anomalyField.shape = buildMeshShape( this.anomalyField );
		this.undulationField.shape = buildMeshShape( this.undulationField );

		this.meshReady = true;
	}


	/**
	 * Accumulates the undulation and anomaly harmonic sums (without their final scales) at the given longitude, using the
	 * {@link SphericalHarmonicsEvaluator} for the latitude (colatitude) set on the current row.
	 * <p>
	 * Going through the evaluator is the cross-check the library is meant to provide; its dirty-flag caching keeps the
	 * Legendre pass shared across the row, so only the inexpensive azimuth part is recomputed here.
	 * <p>
	 * The result is written to {@link #fieldSums}: index 0 is the undulation sum, index 1 is the anomaly sum.
	 * Both share the same inner term; the anomaly weights each degree by  ( l - 1 ) .
	 *
	 * @param longitudeDegrees	longitude in [deg].
	 */
	private void accumulateFieldSums( double longitudeDegrees )
	{
		double longitudeRad = Math.toRadians( longitudeDegrees );
		this.harmonics.setCosPhiAndSinPhi( Math.cos( longitudeRad ) , Math.sin( longitudeRad ) );
		this.harmonics.evaluate();

		double undulationSum = 0.0;
		double anomalySum = 0.0;
		for( int l=2; l<=this.viewDegree; l++ ) {
			double degreeWeight = l - 1;
			for( int m=0; m<=l; m++ ) {
				double cBar = this.model.normalizedC( l , m );
				if(  m == 0  &&  l <= 8  &&  ( l & 1 ) == 0  ) {
					cBar -= this.ellipsoidZonal[l];
				}
				double sBar = this.model.normalizedS( l , m );
				// Geodetic combination  P_l^m ( C cos m lambda + S sin m lambda )  from the orthonormal harmonic parts.
				double term = this.geodeticFactor[m] * ( cBar * this.harmonics.getSphericalHarmonicsRealPart( l , m )
						+ sBar * this.harmonics.getSphericalHarmonicsImaginaryPart( l , m ) );
				undulationSum += term;
				anomalySum += degreeWeight * term;
			}
		}
		this.fieldSums[0] = undulationSum;
		this.fieldSums[1] = anomalySum;
	}


	/**
	 * Stores one grid vertex of a field, applying the field's signed radial relief exaggeration.
	 *
	 * @param field	field to fill.
	 * @param i		latitude index.
	 * @param j		longitude index.
	 * @param geoX	geocentric unit direction x ( cos lat cos lon ).
	 * @param geoY	geocentric unit direction y ( cos lat sin lon ).
	 * @param geoZ	geocentric unit direction z ( sin lat ).
	 * @param value	field value at this grid point.
	 */
	private void fillVertex( ScalarField field , int i , int j , double geoX , double geoY , double geoZ , double value )
	{
		field.value[i][j] = value;
		field.updateRange( value );
		// Pure scientific color: the shading is applied by the GPU light, not baked in, so the light can move for free.
		field.color[i][j] = colorForValue( value , field.colorLimit );
		float radius = radiusForValue( value , field.reliefLimit );
		// Map the geocentric frame to Processing's display frame: +X east, +Y south, +Z toward lon/lat 0/0.
		field.x[i][j] = (float) ( radius * geoY );
		field.y[i][j] = (float) ( -radius * geoZ );
		field.z[i][j] = (float) ( radius * geoX );
		field.radius[i][j] = radius;
	}



	////////////////////////////////////////////////////////////////
	/// RENDERING
	////////////////////////////////////////////////////////////////

	/**
	 * Renders one globe (backing sphere, colored mesh, coastlines) into an off-screen buffer using the shared camera.
	 *
	 * @param g		off-screen buffer.
	 * @param field	field to render.
	 */
	private void renderGlobe( PGraphics g , ScalarField field )
	{
		g.beginDraw();
		g.background( 0 );
		g.sphereDetail( 32 );
		applyProjectionAndCamera( g );

		// Light the scene with the current (fixed or mouse-controlled) direction. The GPU shades the static mesh every
		// frame, so moving the light costs nothing on the CPU. directionalLight() wants the direction the light travels,
		// which is opposite to the surface-to-light direction returned here.
		PVector light = currentLightDirection();
		g.ambientLight( AMBIENT_LIGHT_R , AMBIENT_LIGHT_G , AMBIENT_LIGHT_B );
		g.directionalLight( KEY_LIGHT_R , KEY_LIGHT_G , KEY_LIGHT_B , -light.x , -light.y , -light.z );

		// Backing sphere, shown behind the exaggerated colored cap.
		g.noStroke();
		g.fill( 40 );
		g.sphere( GLOBE_BODY_RADIUS );

		// Colored mesh (a retained shape built at regeneration): pure scientific colors lit by the GPU directional light.
		if( field.shape != null ) {
			g.shape( field.shape );
		}

		// Coastlines draped onto this field's relief (drawn unlit).
		g.noLights();
		this.coastlineField = field;
		this.coastlines.updateGeometry( this );
		this.coastlines.draw( g );

		g.endDraw();
	}


	/**
	 * Sets the perspective projection and positions the camera so the view center faces it.
	 *
	 * @param g	buffer whose camera and projection are set.
	 */
	private void applyProjectionAndCamera( PGraphics g )
	{
		float distance = GLOBE_RADIUS + this.altitude;
		g.perspective( radians( (float) CAMERA_FOV_DEGREES ) , (float) g.width / g.height , GLOBE_RADIUS * 0.005f , GLOBE_RADIUS * 100.0f );

		PVector direction = surfaceDirection( this.centerLatitude , this.centerLongitude );
		PVector eye = PVector.mult( direction , distance );

		// Processing's P3D frame has +Y downward, so camera() uses a screen-down vector here.
		PVector north = localNorthDirection( this.centerLatitude , this.centerLongitude );
		PVector screenDown = PVector.mult( north , -1.0f );
		screenDown.sub( PVector.mult( direction , screenDown.dot( direction ) ) );
		if( screenDown.magSq() < 1.0e-6 ) {
			screenDown = new PVector( 0 , 1 , 0 );
		}
		screenDown.normalize();

		g.camera( eye.x , eye.y , eye.z , 0 , 0 , 0 , screenDown.x , screenDown.y , screenDown.z );
	}


	/**
	 * Builds a retained shape for a field's colored mesh, as a grid of quads with per-vertex colors. The shape is built once
	 * per regeneration and then drawn every frame, so navigating does not re-send the geometry.
	 *
	 * @param field	field to build the shape for.
	 * @return	mesh shape ready to be drawn with {@link PGraphics#shape(PShape)}.
	 */
	private PShape buildMeshShape( ScalarField field )
	{
		PShape shape = createShape();
		shape.beginShape( QUADS );
		shape.noStroke();
		for( int i=0; i<GRID_N-1; i++ ) {
			for( int j=0; j<GRID_N-1; j++ ) {
				addShapeVertex( shape , field , i , j );
				addShapeVertex( shape , field , i , j+1 );
				addShapeVertex( shape , field , i+1 , j+1 );
				addShapeVertex( shape , field , i+1 , j );
			}
		}
		shape.endShape();
		return shape;
	}


	/**
	 * Adds one vertex to a mesh shape, with its outward normal (for GPU lighting) and pure scientific color (the material).
	 *
	 * @param shape	shape being built.
	 * @param field	field being drawn.
	 * @param i		latitude index.
	 * @param j		longitude index.
	 */
	private void addShapeVertex( PShape shape , ScalarField field , int i , int j )
	{
		PVector normal = meshNormal( field , i , j );
		shape.normal( normal.x , normal.y , normal.z );
		shape.fill( field.color[i][j] );
		shape.vertex( field.x[i][j] , field.y[i][j] , field.z[i][j] );
	}


	/**
	 * Draws the heads-up display (instructions, view info, panel titles, and per-panel color bars) in screen space.
	 *
	 * @param info	text to show.
	 */
	private void drawOverlay( String info )
	{
		hint( DISABLE_DEPTH_TEST );
		camera();
		perspective();
		noLights();

		// Divider between the two panels.
		stroke( 70 );
		strokeWeight( 1 );
		line( height , 0 , height , height );

		// Controls and view info (top-left).
		fill( 230 );
		textAlign( LEFT , TOP );
		text( info , 12 , 12 );

		if( this.meshReady ) {
			// Panel titles (top-center of each half).
			textAlign( CENTER , TOP );
			text( this.anomalyField.title , height * 0.5f , 12 );
			text( this.undulationField.title , height * 1.5f , 12 );
			textAlign( LEFT , TOP );

			// Per-panel color bars near the right edge of each half.
			drawColorBar( height - 56 , this.anomalyField );
			drawColorBar( 2 * height - 56 , this.undulationField );
		}

		hint( ENABLE_DEPTH_TEST );
	}


	/**
	 * Draws a vertical color bar with a field's fixed color scale.
	 *
	 * @param barX	left edge of the bar, in pixels.
	 * @param field	field whose scale is drawn.
	 */
	private void drawColorBar( float barX , ScalarField field )
	{
		int barY = 60;
		int barWidth = 18;
		int barHeight = 300;
		noStroke();
		for( int k=0; k<barHeight; k++ ) {
			float t = 1.0f - (float) k / ( barHeight - 1 );
			fill( rainbowColor( t ) );
			rect( barX , barY + k , barWidth , 1 );
		}
		fill( 230 );
		textAlign( RIGHT , CENTER );
		text( String.format( "+%.0f %s" , field.colorLimit , field.unit ) , barX - 4 , barY );
		text( "0" , barX - 4 , barY + barHeight * 0.5f );
		text( String.format( "-%.0f %s" , field.colorLimit , field.unit ) , barX - 4 , barY + barHeight );
		textAlign( LEFT , TOP );
	}



	////////////////////////////////////////////////////////////////
	/// HELPERS
	////////////////////////////////////////////////////////////////

	/**
	 * Returns the vertical half-angle (in degrees) of the globe patch framed by the perspective projection.
	 *
	 * @return	vertical visible patch half-angle, in degrees.
	 */
	private double visibleVerticalHalfSpanDegrees()
	{
		return visibleHalfSpanForRayAngle( Math.toRadians( CAMERA_FOV_DEGREES * 0.5 ) );
	}


	/**
	 * Returns the horizontal half-angle (in degrees) of the globe patch framed by the perspective projection.
	 * <p>
	 * The panels are square, so the horizontal field of view equals the vertical one.
	 *
	 * @return	horizontal visible patch half-angle, in degrees.
	 */
	private double visibleHorizontalHalfSpanDegrees()
	{
		double verticalHalfAngle = Math.toRadians( CAMERA_FOV_DEGREES * 0.5 );
		double horizontalHalfAngle = Math.atan( Math.tan( verticalHalfAngle ) * 1.0 );
		return visibleHalfSpanForRayAngle( horizontalHalfAngle );
	}


	/**
	 * Intersects an off-center camera ray with the globe and returns the corresponding central angle from the view center.
	 *
	 * @param rayAngle	camera ray angle away from the center ray, in [rad].
	 * @return	spherical surface half-span in [deg].
	 */
	private double visibleHalfSpanForRayAngle( double rayAngle )
	{
		double distance = GLOBE_RADIUS + this.altitude;
		double apparentRadius = Math.asin( Math.max( -1.0 , Math.min( 1.0 , GLOBE_RADIUS / distance ) ) );
		if( rayAngle >= apparentRadius ) {
			double horizonSpan = Math.toDegrees( Math.acos( Math.max( -1.0 , Math.min( 1.0 , GLOBE_RADIUS / distance ) ) ) );
			return Math.min( horizonSpan , 89.9 );
		}

		double sinRay = Math.sin( rayAngle );
		double cosRay = Math.cos( rayAngle );
		double discriminant = GLOBE_RADIUS * GLOBE_RADIUS - distance * distance * sinRay * sinRay;
		double rayLength = distance * cosRay - Math.sqrt( Math.max( 0.0 , discriminant ) );
		double surfaceAcross = rayLength * sinRay;
		double surfaceForward = distance - rayLength * cosRay;
		double halfSpan = Math.toDegrees( Math.atan2( surfaceAcross , surfaceForward ) );
		return Math.min( halfSpan , 89.9 );
	}


	/**
	 * Returns the outward unit direction of the globe surface at the given latitude and longitude, mapped into
	 * Processing's left-handed display frame: +X east at Greenwich, +Y south, and +Z toward lon/lat 0/0.
	 *
	 * @param latitudeDegrees	latitude in [deg].
	 * @param longitudeDegrees	longitude in [deg].
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
		return new PVector(
				(float) geoY ,
				(float) -geoZ ,
				(float) geoX );
	}


	/**
	 * Returns local geodetic north at a lon/lat point, mapped into Processing's display frame.
	 *
	 * @param latitudeDegrees	latitude in [deg].
	 * @param longitudeDegrees	longitude in [deg].
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
		return new PVector(
				(float) geoY ,
				(float) -geoZ ,
				(float) geoX );
	}


	/**
	 * Maps a value to the fixed rainbow color scale of half-amplitude {@code colorLimit}.
	 *
	 * @param value			field value.
	 * @param colorLimit	value mapped to the top of the scale.
	 * @return	packed color.
	 */
	private int colorForValue( double value , double colorLimit )
	{
		double t = 0.5 + 0.5 * value / colorLimit;
		return rainbowColor( (float) t );
	}


	/**
	 * Returns a key-light direction tied to the current view: from screen-right, slightly above, and slightly forward.
	 *
	 * @return	unit light direction in display coordinates.
	 */
	private PVector rightSideLightDirection()
	{
		PVector view = surfaceDirection( this.centerLatitude , this.centerLongitude );
		PVector north = localNorthDirection( this.centerLatitude , this.centerLongitude );
		PVector screenDown = PVector.mult( north , -1.0f );
		screenDown.sub( PVector.mult( view , screenDown.dot( view ) ) );
		if( screenDown.magSq() < 1.0e-6 ) {
			screenDown = new PVector( 0 , 1 , 0 );
		}
		screenDown.normalize();

		PVector screenRight = cross( screenDown , view );
		if( screenRight.magSq() < 1.0e-6 ) {
			screenRight = new PVector( 1 , 0 , 0 );
		}
		screenRight.normalize();

		PVector light = new PVector( 0 , 0 , 0 );
		light.add( PVector.mult( screenRight , 0.65f ) );
		light.add( PVector.mult( screenDown , -0.0f ) );
		light.add( PVector.mult( view , -0.35f ) );
		light.normalize();
		return light;
	}


	/**
	 * Returns the light direction currently in effect: the mouse-controlled direction when that mode is on, otherwise the fixed key light.
	 *
	 * @return	unit light direction in display coordinates.
	 */
	private PVector currentLightDirection()
	{
		return this.mouseControlsLight ? mouseLightDirection() : rightSideLightDirection();
	}


	/**
	 * Returns a light direction driven by the mouse position (used while the mouse-light mode is on).
	 * <p>
	 * The mouse offset from the center of the panel it hovers maps to screen-right and screen-down components, plus a
	 * fixed forward component toward the camera. Hovering near the center lights the globe head-on (flat); moving outward
	 * makes the light graze the relief, revealing detail.
	 *
	 * @return	unit light direction in display coordinates.
	 */
	private PVector mouseLightDirection()
	{
		PVector view = surfaceDirection( this.centerLatitude , this.centerLongitude );
		PVector north = localNorthDirection( this.centerLatitude , this.centerLongitude );
		PVector screenDown = PVector.mult( north , -1.0f );
		screenDown.sub( PVector.mult( view , screenDown.dot( view ) ) );
		if( screenDown.magSq() < 1.0e-6 ) {
			screenDown = new PVector( 0 , 1 , 0 );
		}
		screenDown.normalize();

		PVector screenRight = cross( screenDown , view );
		if( screenRight.magSq() < 1.0e-6 ) {
			screenRight = new PVector( 1 , 0 , 0 );
		}
		screenRight.normalize();

		// Mouse offset from the center of the panel it is over, in [-1,1].
		float halfPanel = height * 0.5f;
		float panelCenterX = ( mouseX < height ) ? halfPanel : height + halfPanel;
		float ndcX = constrain( ( mouseX - panelCenterX ) / halfPanel , -1.0f , 1.0f );
		float ndcY = constrain( ( mouseY - halfPanel ) / halfPanel , -1.0f , 1.0f );

		PVector light = new PVector( 0 , 0 , 0 );
		light.add( PVector.mult( screenRight , ndcX ) );
		light.add( PVector.mult( screenDown , ndcY ) );
		light.add( PVector.mult( view , MOUSE_LIGHT_DEPTH ) );
		light.normalize();
		return light;
	}


	/**
	 * Estimates the outward normal of an exaggerated mesh vertex from neighboring mesh coordinates.
	 *
	 * @param field	field whose mesh is used.
	 * @param i	latitude index.
	 * @param j	longitude index.
	 * @return	unit normal vector.
	 */
	private PVector meshNormal( ScalarField field , int i , int j )
	{
		int im = Math.max( 0 , i - 1 );
		int ip = Math.min( GRID_N - 1 , i + 1 );
		int jm = Math.max( 0 , j - 1 );
		int jp = Math.min( GRID_N - 1 , j + 1 );

		PVector dLatitude = new PVector(
				field.x[ip][j] - field.x[im][j] ,
				field.y[ip][j] - field.y[im][j] ,
				field.z[ip][j] - field.z[im][j] );
		PVector dLongitude = new PVector(
				field.x[i][jp] - field.x[i][jm] ,
				field.y[i][jp] - field.y[i][jm] ,
				field.z[i][jp] - field.z[i][jm] );

		PVector normal = cross( dLatitude , dLongitude );
		PVector outward = new PVector( field.x[i][j] , field.y[i][j] , field.z[i][j] );
		if( normal.magSq() < 1.0e-6 ) {
			normal = outward;
		}
		if( normal.dot( outward ) < 0.0f ) {
			normal.mult( -1.0f );
		}
		normal.normalize();
		return normal;
	}


	/**
	 * Returns the vector cross product.
	 */
	private static PVector cross( PVector a , PVector b )
	{
		return new PVector(
				a.y * b.z - a.z * b.y ,
				a.z * b.x - a.x * b.z ,
				a.x * b.y - a.y * b.x );
	}


	/**
	 * Returns the display radius for a value, with a square-root response to make small details visible.
	 *
	 * @param value			field value.
	 * @param reliefLimit	value mapped to the full relief exaggeration.
	 * @return	exaggerated display radius.
	 */
	private static float radiusForValue( double value , double reliefLimit )
	{
		double normalized = Math.max( -1.0 , Math.min( 1.0 , value / reliefLimit ) );
		double shaped = Math.copySign( Math.sqrt( Math.abs( normalized ) ) , normalized );
		return (float) ( GLOBE_RADIUS + RELIEF_EXAGGERATION * shaped );
	}


	/**
	 * Returns the current mesh radius at a coastline lon/lat point, plus a tiny drawing offset.
	 * <p>
	 * Uses the relief of {@link #coastlineField}, the field whose globe is currently being rendered.
	 *
	 * @param latitudeDegrees	latitude in [deg].
	 * @param longitudeDegrees	longitude in [deg].
	 * @return	coastline display radius.
	 */
	@Override
	public float radiusAt( double latitudeDegrees , double longitudeDegrees )
	{
		if( this.coastlineField == null || this.meshCellLatDegrees <= 0.0 || this.meshCellLonDegrees <= 0.0
				|| latitudeDegrees < this.meshLatMin || latitudeDegrees > this.meshLatMax ) {
			return GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET;
		}

		double longitude = wrapLongitudeNear( longitudeDegrees , this.meshCenterLongitude );
		if( longitude < this.meshLonMin || longitude > this.meshLonMax ) {
			return GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET;
		}

		double row = ( latitudeDegrees - this.meshLatMin ) / this.meshCellLatDegrees;
		double column = ( longitude - this.meshLonMin ) / this.meshCellLonDegrees;
		int i0 = Math.max( 0 , Math.min( GRID_N - 2 , (int) Math.floor( row ) ) );
		int j0 = Math.max( 0 , Math.min( GRID_N - 2 , (int) Math.floor( column ) ) );
		double u = Math.max( 0.0 , Math.min( 1.0 , row - i0 ) );
		double v = Math.max( 0.0 , Math.min( 1.0 , column - j0 ) );

		float[][] radius = this.coastlineField.radius;
		double r00 = radius[i0][j0];
		double r01 = radius[i0][j0+1];
		double r10 = radius[i0+1][j0];
		double r11 = radius[i0+1][j0+1];
		double r0 = r00 * ( 1.0 - v ) + r01 * v;
		double r1 = r10 * ( 1.0 - v ) + r11 * v;
		return (float) ( r0 * ( 1.0 - u ) + r1 * u + COASTLINE_SURFACE_OFFSET );
	}


	/**
	 * Constrains a latitude to the open interval that avoids the poles.
	 *
	 * @param latitudeDegrees	latitude in [deg].
	 * @return	constrained latitude in [deg].
	 */
	private static double constrainLatitude( double latitudeDegrees )
	{
		return Math.max( -89.0 , Math.min( 89.0 , latitudeDegrees ) );
	}


	/**
	 * Maps a normalized value to a blue-to-red rainbow color.
	 *
	 * @param t		value in [0,1].
	 * @return	packed color.
	 */
	private int rainbowColor( float t )
	{
		t = constrain( t , 0.0f , 1.0f );
		if( t < 0.25f ) {
			return color( 0 , map( t , 0.0f , 0.25f , 0 , 255 ) , 255 );
		} else if( t < 0.5f ) {
			return color( 0 , 255 , map( t , 0.25f , 0.5f , 255 , 0 ) );
		} else if( t < 0.75f ) {
			return color( map( t , 0.5f , 0.75f , 0 , 255 ) , 255 , 0 );
		} else {
			return color( 255 , map( t , 0.75f , 1.0f , 255 , 0 ) , 0 );
		}
	}


	/**
	 * Returns the heads-up display text describing the current view.
	 *
	 * @return	heads-up display text.
	 */
	private String hudText()
	{
		return String.format(
				"EGM2008  (left: gravity anomaly , right: geoid undulation)%n" +
				"drag: latitude/longitude   wheel: zoom   space: regenerate   l: mouse light (%s)%n" +
				"view center: %.1f deg lat , %.1f deg lon   half-span: %.2f deg lat , %.2f deg lon%n" +
				"mesh: %s   degree: %d / %d%n" +
				"anomaly: %.0f to %.0f mGal   undulation: %.1f to %.1f m%n" +
				"%s" ,
				( this.mouseControlsLight ? "on" : "off" ) ,
				this.centerLatitude , wrapLongitude( this.centerLongitude ) ,
				visibleVerticalHalfSpanDegrees() , visibleHorizontalHalfSpanDegrees() ,
				( this.meshGlobal ? "whole globe" : "visible cap" ) , this.viewDegree , this.modelMaxDegree ,
				this.anomalyField.valueMin , this.anomalyField.valueMax ,
				this.undulationField.valueMin , this.undulationField.valueMax ,
				this.coastlines.status() );
	}


	/**
	 * Wraps a longitude into the interval [-180, 180] for display.
	 *
	 * @param longitudeDegrees	longitude in [deg].
	 * @return	wrapped longitude in [deg].
	 */
	private static double wrapLongitude( double longitudeDegrees )
	{
		return ( ( longitudeDegrees + 180.0 ) % 360.0 + 360.0 ) % 360.0 - 180.0;
	}


	/**
	 * Wraps a longitude to the equivalent value nearest to a reference longitude.
	 *
	 * @param longitudeDegrees			longitude to wrap in [deg].
	 * @param referenceLongitudeDegrees	reference longitude in [deg].
	 * @return	equivalent longitude nearest to the reference.
	 */
	private static double wrapLongitudeNear( double longitudeDegrees , double referenceLongitudeDegrees )
	{
		double wrapped = longitudeDegrees;
		while( wrapped - referenceLongitudeDegrees > 180.0 ) {
			wrapped -= 360.0;
		}
		while( wrapped - referenceLongitudeDegrees < -180.0 ) {
			wrapped += 360.0;
		}
		return wrapped;
	}



	////////////////////////////////////////////////////////////////
	/// MODEL LOADING
	////////////////////////////////////////////////////////////////

	/**
	 * Loads the model and prepares the evaluation machinery.
	 */
	private void loadModel()
	{
		String path = locateGfcPath();
		if( path == null ) {
			this.loadError = "EGM2008.gfc not found in " + String.join( " or " , GFC_CANDIDATE_PATHS );
			return;
		}
		try {
			this.model = Egm2008.fromFilePathAndMaximumDegree( path , LOAD_DEGREE );
		} catch( IOException e ) {
			this.loadError = "Could not load EGM2008.gfc: " + e.getMessage();
			return;
		}
		this.modelMaxDegree = this.model.maximumDegree();
		this.harmonics = new SphericalHarmonicsEvaluator( Math.max( 1 , this.modelMaxDegree ) );

		this.geodeticFactor = new double[ this.modelMaxDegree + 1 ];
		for( int m=0; m<this.geodeticFactor.length; m++ ) {
			double sign = ( m % 2 == 0 ) ? 1.0 : -1.0;
			double normalization = ( m == 0 ) ? 1.0 : 2.0;
			this.geodeticFactor[m] = sign * Math.sqrt( 4.0 * Math.PI * normalization );
		}

		this.ellipsoidZonal = referenceEllipsoidZonalCoefficients();
		this.modelLoaded = true;
	}


	/**
	 * Returns the path to the EGM2008 {@code .gfc} file, or {@code null} if it cannot be found.
	 *
	 * @return	path to the EGM2008 {@code .gfc} file, or {@code null} if it cannot be found.
	 */
	private static String locateGfcPath()
	{
		for( String candidatePath : GFC_CANDIDATE_PATHS ) {
			if( Files.exists( Paths.get( candidatePath ) ) ) {
				return candidatePath;
			}
		}
		return null;
	}


	/**
	 * Returns the fully normalized even zonal harmonic coefficients of the GRS80 reference ellipsoid, indexed by degree.
	 * <p>
	 * They are obtained from the closed-form expression of the even zonal harmonics  J_{2n}  of an equipotential ellipsoid
	 * (Heiskanen and Moritz, "Physical Geodesy", eq. 2-92), and converted to the geodetic fully normalized convention with
	 * {@code C_{l,0} = -J_l / sqrt( 2 l + 1 )}.
	 *
	 * @return	array indexed by degree; only degrees 2, 4, 6, 8 are non-zero.
	 */
	private static double[] referenceEllipsoidZonalCoefficients()
	{
		double firstEccentricitySquared = 0.00669438002290;   // GRS80
		double dynamicFormFactor = 0.00108263;                 // GRS80  J2
		double[] coefficients = new double[ 9 ];
		for( int n=1; n<=4; n++ ) {
			int degree = 2 * n;
			double sign = ( n % 2 == 0 ) ? -1.0 : 1.0;   // (-1)^{n+1}
			double eccentricityPower = Math.pow( firstEccentricitySquared , n );
			double j = sign * ( 3.0 * eccentricityPower ) / ( ( 2.0 * n + 1.0 ) * ( 2.0 * n + 3.0 ) )
					* ( 1.0 - n + 5.0 * n * dynamicFormFactor / firstEccentricitySquared );
			coefficients[ degree ] = -j / Math.sqrt( 2.0 * degree + 1.0 );
		}
		return coefficients;
	}



	////////////////////////////////////////////////////////////////
	/// NESTED TYPES
	////////////////////////////////////////////////////////////////

	/**
	 * One scalar field sampled on the view grid: its values, exaggerated globe geometry, per-vertex colors, and display metadata.
	 */
	private static final class ScalarField
	{
		/**
		 * Panel title.
		 */
		private final String title;

		/**
		 * Value unit, shown on the color bar.
		 */
		private final String unit;

		/**
		 * Value mapped to the extreme of the color scale.
		 */
		private final double colorLimit;

		/**
		 * Value mapped to the full radial relief exaggeration.
		 */
		private final double reliefLimit;

		/**
		 * Field value at each grid point.
		 */
		private final double[][] value = new double[GRID_N][GRID_N];

		/**
		 * Display coordinates of each grid point on the exaggerated globe.
		 */
		private final float[][] x = new float[GRID_N][GRID_N];
		private final float[][] y = new float[GRID_N][GRID_N];
		private final float[][] z = new float[GRID_N][GRID_N];

		/**
		 * Display radius of each grid point.
		 */
		private final float[][] radius = new float[GRID_N][GRID_N];

		/**
		 * Color of each grid point.
		 */
		private final int[][] color = new int[GRID_N][GRID_N];

		/**
		 * Retained shape of the colored mesh, rebuilt on each regeneration and drawn every frame.
		 */
		private PShape shape;

		/**
		 * Minimum and maximum value of the current mesh.
		 */
		private double valueMin;
		private double valueMax;


		private ScalarField( String title , String unit , double colorLimit , double reliefLimit )
		{
			this.title = title;
			this.unit = unit;
			this.colorLimit = colorLimit;
			this.reliefLimit = reliefLimit;
		}


		private void resetRange()
		{
			this.valueMin = Double.POSITIVE_INFINITY;
			this.valueMax = Double.NEGATIVE_INFINITY;
		}


		private void updateRange( double value )
		{
			if( value < this.valueMin ) {
				this.valueMin = value;
			}
			if( value > this.valueMax ) {
				this.valueMax = value;
			}
		}
	}

}
