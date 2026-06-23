package processing;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import geophysicalModelLibrary.Egm2008;
import numericalLibrary.functions.PreNormalizedAssociatedLegendrePolynomialEvaluator;
import utils.Coastlines;

import processing.core.PApplet;
import processing.core.PVector;
import processing.event.MouseEvent;



/**
 * Interactive 3D visualization of the EGM2008 gravity anomaly draped on a globe.
 * <p>
 * The gravity anomaly is computed from the spherical harmonic coefficients using the classical spherical
 * approximation, with the even zonal harmonics of the GRS80 reference ellipsoid removed:
 * <p>
 *   delta_g( theta , lambda ) = ( GM / a^2 ) sum_{l=2}^{L} ( l - 1 ) sum_{m=0}^{l} P_l^m( cos theta ) [ dC_l^m cos( m lambda ) + S_l^m sin( m lambda ) ]
 * <p>
 * where  dC_l^m  is the model coefficient minus the reference ellipsoid coefficient (only the even zonal terms differ).
 * <p>
 * Interaction:
 * <ul>
 * <li> Drag with the mouse (press-drag-release) to change the central latitude and longitude brought in front of the camera.
 * <li> Mouse wheel to zoom in and out.
 * <li> Space bar to regenerate the colored mesh for the current view.
 * </ul>
 * The colored mesh stays fixed while navigating and is regenerated only when requested. A fixed number of grid points is
 * evaluated in latitude and longitude; when regeneration is requested, the actual coordinates of those points cover the
 * currently visible spherical cap, so the resolution increases as the view zooms in. The spherical harmonic degree used
 * for the evaluation is adapted to the angular size of the grid cells (and capped at the loaded degree), keeping
 * zoomed-out views fast and zoomed-in views detailed.
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
	private static final int GRID_N = 256;

	/**
	 * Radius of the globe in world units.
	 */
	private static final float GLOBE_RADIUS = 300.0f;

	/**
	 * Perspective vertical field of view in degrees.
	 */
	private static final double CAMERA_FOV_DEGREES = 50.0;

	/**
	 * Stable symmetric color scale. Values outside this interval saturate, but colors no longer depend on the current view.
	 */
	private static final double COLOR_LIMIT_MILLIGAL = 250.0;

	/**
	 * Anomaly magnitude that reaches the full radial relief exaggeration.
	 */
	private static final double RELIEF_LIMIT_MILLIGAL = 250.0;

	/**
	 * Maximum signed radial relief, in world units, used to make small anomaly details easier to see.
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
	 * Minimum lighting level for the color-shaded mesh.
	 */
	private static final float MESH_LIGHT_AMBIENT = 0.30f;

	/**
	 * Diffuse lighting contribution for the color-shaded mesh.
	 */
	private static final float MESH_LIGHT_DIFFUSE = 0.82f;

	/**
	 * Cool extra shadow tint applied where the right-hand key light grazes the exaggerated relief.
	 */
	private static final float MESH_SHADOW_BLUE = 0.10f;

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
	 * Evaluator of the orthonormal associated Legendre functions.
	 */
	private PreNormalizedAssociatedLegendrePolynomialEvaluator legendre;

	/**
	 * Factor converting the orthonormal associated Legendre value of order  m  into the geodetic fully normalized one:
	 * {@code (-1)^m sqrt( 4 pi ( 2 - delta_{m0} ) )}.
	 */
	private double[] geodeticFactor;

	/**
	 * Even zonal coefficients of the GRS80 reference ellipsoid, indexed by degree (only degrees 2, 4, 6, 8 are non-zero).
	 */
	private double[] ellipsoidZonal;

	/**
	 * Reused buffers for  cos( m lambda )  and  sin( m lambda ) .
	 */
	private double[] cosMLambda;
	private double[] sinMLambda;

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



	////////////////////////////////////////////////////////////////
	/// MESH STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Cartesian coordinates of the grid points on the globe.
	 */
	private float[][] meshX = new float[GRID_N][GRID_N];
	private float[][] meshY = new float[GRID_N][GRID_N];
	private float[][] meshZ = new float[GRID_N][GRID_N];

	/**
	 * Display radius of each grid point on the exaggerated globe.
	 */
	private float[][] meshRadius = new float[GRID_N][GRID_N];

	/**
	 * Color of each grid point.
	 */
	private int[][] meshColor = new int[GRID_N][GRID_N];

	/**
	 * Whether {@link #meshColor} and the coordinate arrays hold a valid mesh.
	 */
	private boolean meshReady;

	/**
	 * Minimum and maximum anomaly (in mGal) of the current mesh, and the degree used to evaluate it.
	 */
	private double anomalyMin;
	private double anomalyMax;
	private int viewDegree;

	/**
	 * View parameters used to produce the current mesh.
	 */
	private double meshCenterLatitude;
	private double meshCenterLongitude;
	private double meshHalfLatSpan;
	private double meshHalfLonSpan;
	private double meshLatMin;
	private double meshLatMax;
	private double meshLonMin;
	private double meshLonMax;
	private double meshCellLatDegrees;
	private double meshCellLonDegrees;



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
		size( 1000 , 1000 , P3D );
		smooth( 8 );
	}


	// identical use to setup in Processing IDE except for size()
	public void setup()
	{
		sphereDetail( 32 );
		textSize( 13 );
		loadModel();
		this.coastlines = Coastlines.loadNaturalEarth110m( GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET );
	}


	// identical use to draw in Processing IDE
	public void draw()
	{
		background(0);
        lights();
        
		//background( 12 );
        
		
		if( !this.modelLoaded ) {
			drawHud( ( this.loadError != null ) ? this.loadError : "Loading EGM2008 ..." );
			return;
		}

		if( this.meshDirty ) {
			regenerateMesh();
			this.meshDirty = false;
		}

		applyProjectionAndCamera();
		
		// Globe body, shown behind the exaggerated colored cap.
		lights();
		noStroke();
		fill( 40 );
		sphere( GLOBE_BODY_RADIUS );

		// Colored anomaly mesh and coastlines. Mesh colors already include a view-keyed hillshade.
		noLights();
		drawMesh();
		this.coastlines.draw( this );

		drawHud( hudText() );
	}



	////////////////////////////////////////////////////////////////
	/// INTERACTION
	////////////////////////////////////////////////////////////////

	public void mouseDragged()
	{
		// Sensitivity scales with the visible span so the drag feels consistent at every zoom level.
		double verticalSensitivity = visibleVerticalHalfSpanDegrees() / ( height * 0.5 );
		double horizontalSensitivity = visibleHorizontalHalfSpanDegrees() / ( width * 0.5 );
		this.centerLatitude += ( mouseY - pmouseY ) * verticalSensitivity;
		this.centerLongitude -= ( mouseX - pmouseX ) * horizontalSensitivity / Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.05 );
		this.centerLatitude = constrainLatitude( this.centerLatitude );
		// The camera follows during the drag; the mesh is regenerated only when the space bar is pressed.
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
		}
	}



	////////////////////////////////////////////////////////////////
	/// MESH GENERATION
	////////////////////////////////////////////////////////////////

	/**
	 * Regenerates the colored mesh over the currently visible spherical cap.
	 */
	private void regenerateMesh()
	{
		double halfLatSpan = visibleVerticalHalfSpanDegrees();
		double cosLat = Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.05 );
		double halfLonSpan = Math.min( 180.0 , visibleHorizontalHalfSpanDegrees() / cosLat );

		double latMin = constrainLatitude( this.centerLatitude - halfLatSpan );
		double latMax = constrainLatitude( this.centerLatitude + halfLatSpan );
		double lonMin = this.centerLongitude - halfLonSpan;
		double lonMax = this.centerLongitude + halfLonSpan;

		double cellLatDegrees = ( latMax - latMin ) / ( GRID_N - 1 );
		double cellLonDegrees = ( lonMax - lonMin ) / ( GRID_N - 1 );
		this.meshLatMin = latMin;
		this.meshLatMax = latMax;
		this.meshLonMin = lonMin;
		this.meshLonMax = lonMax;
		this.meshCellLatDegrees = cellLatDegrees;
		this.meshCellLonDegrees = cellLonDegrees;

		// Adapt the evaluation degree to the angular size of the grid cells (Nyquist), capped at the loaded degree.
		this.viewDegree = (int) Math.round( 180.0 / Math.max( cellLatDegrees , 1.0e-6 ) );
		this.viewDegree = Math.max( 2 , Math.min( this.viewDegree , this.modelMaxDegree ) );

		double gravitationalParameter = this.model.gravitationalParameter();
		double referenceRadius = this.model.referenceRadius();
		double scaleToMilligal = gravitationalParameter / ( referenceRadius * referenceRadius ) * 1.0e5;

		double minimum = Double.POSITIVE_INFINITY;
		double maximum = Double.NEGATIVE_INFINITY;
		double[] anomaly = new double[ GRID_N * GRID_N ];

		for( int i=0; i<GRID_N; i++ ) {
			double latitude = latMin + i * cellLatDegrees;
			double latitudeRad = Math.toRadians( latitude );
			// Evaluate the Legendre functions once per latitude row, at  cos( colatitude ) = sin( latitude ) .
			this.legendre.evaluate( Math.sin( latitudeRad ) );
			double cosLatitude = Math.cos( latitudeRad );
			double sinLatitude = Math.sin( latitudeRad );
			for( int j=0; j<GRID_N; j++ ) {
				double longitude = lonMin + j * cellLonDegrees;
				double value = evaluateAnomaly( longitude ) * scaleToMilligal;
				anomaly[ i * GRID_N + j ] = value;
				if( value < minimum ) {
					minimum = value;
				}
				if( value > maximum ) {
					maximum = value;
				}
				// Position on the globe surface, with signed radial relief exaggeration.
				double longitudeRad = Math.toRadians( longitude );
				float radius = radiusForAnomaly( value );
				double geoX = cosLatitude * Math.cos( longitudeRad );
				double geoY = cosLatitude * Math.sin( longitudeRad );
				double geoZ = sinLatitude;
				this.meshX[i][j] = (float) ( radius * geoY );
				this.meshY[i][j] = (float) ( -radius * geoZ );
				this.meshZ[i][j] = (float) ( radius * geoX );
				this.meshRadius[i][j] = radius;
			}
		}

		PVector lightDirection = rightSideLightDirection();
		PVector viewDirection = surfaceDirection( this.centerLatitude , this.centerLongitude );

		// Map anomaly values to a stable, zero-centered color scale, then apply a fixed-view hillshade.
		for( int i=0; i<GRID_N; i++ ) {
			for( int j=0; j<GRID_N; j++ ) {
				int baseColor = anomalyColor( anomaly[ i * GRID_N + j ] );
				this.meshColor[i][j] = litMeshColor( baseColor , meshNormal( i , j ) , lightDirection , viewDirection );
			}
		}

		this.anomalyMin = minimum;
		this.anomalyMax = maximum;
		this.meshCenterLatitude = this.centerLatitude;
		this.meshCenterLongitude = this.centerLongitude;
		this.meshHalfLatSpan = halfLatSpan;
		this.meshHalfLonSpan = halfLonSpan;
		this.meshReady = true;
		this.coastlines.updateGeometry( this );
	}


	/**
	 * Evaluates the gravity anomaly (without the {@code GM/a^2} scale) at the given longitude, for the latitude row last
	 * passed to {@link PreNormalizedAssociatedLegendrePolynomialEvaluator#evaluate(double)}.
	 *
	 * @param longitudeDegrees	longitude in [deg].
	 * @return	gravity anomaly contribution to be scaled by {@code GM/a^2}.
	 */
	private double evaluateAnomaly( double longitudeDegrees )
	{
		double longitudeRad = Math.toRadians( longitudeDegrees );
		double cosLambda = Math.cos( longitudeRad );
		double sinLambda = Math.sin( longitudeRad );
		this.cosMLambda[0] = 1.0;
		this.sinMLambda[0] = 0.0;
		for( int m=1; m<=this.viewDegree; m++ ) {
			this.cosMLambda[m] = cosLambda * this.cosMLambda[m-1] - sinLambda * this.sinMLambda[m-1];
			this.sinMLambda[m] = sinLambda * this.cosMLambda[m-1] + cosLambda * this.sinMLambda[m-1];
		}

		double sum = 0.0;
		for( int l=2; l<=this.viewDegree; l++ ) {
			double degreeFactor = l - 1;
			for( int m=0; m<=l; m++ ) {
				double cBar = this.model.normalizedC( l , m );
				if(  m == 0  &&  l <= 8  &&  ( l & 1 ) == 0  ) {
					cBar -= this.ellipsoidZonal[l];
				}
				double sBar = this.model.normalizedS( l , m );
				double pBar = this.geodeticFactor[m] * this.legendre.getPolynomialValue( l , m );
				sum += degreeFactor * pBar * ( cBar * this.cosMLambda[m] + sBar * this.sinMLambda[m] );
			}
		}
		return sum;
	}



	////////////////////////////////////////////////////////////////
	/// RENDERING
	////////////////////////////////////////////////////////////////

	/**
	 * Sets the perspective projection and positions the camera so the view center faces it.
	 */
	private void applyProjectionAndCamera()
	{
		float distance = GLOBE_RADIUS + this.altitude;
		perspective( radians( (float) CAMERA_FOV_DEGREES ) , (float) width / height , GLOBE_RADIUS * 0.005f , GLOBE_RADIUS * 100.0f );

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

		camera( eye.x , eye.y , eye.z , 0 , 0 , 0 , screenDown.x , screenDown.y , screenDown.z );
	}


	/**
	 * Draws the colored anomaly mesh as a grid of quads with per-vertex colors.
	 */
	private void drawMesh()
	{
		if( !this.meshReady ) {
			return;
		}
		noStroke();
		beginShape( QUADS );
		for( int i=0; i<GRID_N-1; i++ ) {
			for( int j=0; j<GRID_N-1; j++ ) {
				meshVertex( i , j );
				meshVertex( i , j+1 );
				meshVertex( i+1 , j+1 );
				meshVertex( i+1 , j );
			}
		}
		endShape();
	}

	/**
	 * Emits one colored mesh vertex.
	 *
	 * @param i		latitude index.
	 * @param j		longitude index.
	 */
	private void meshVertex( int i , int j )
	{
		fill( this.meshColor[i][j] );
		vertex( this.meshX[i][j] , this.meshY[i][j] , this.meshZ[i][j] );
	}


	/**
	 * Draws the heads-up display (instructions, view info, and a color bar) in screen space.
	 *
	 * @param info		text to show.
	 */
	private void drawHud( String info )
	{
		hint( DISABLE_DEPTH_TEST );
		camera();
		perspective();
		noLights();

		fill( 230 );
		textAlign( LEFT , TOP );
		text( info , 12 , 12 );

		if( this.meshReady ) {
			drawColorBar();
		}

		hint( ENABLE_DEPTH_TEST );
	}


	/**
	 * Draws a vertical color bar with the fixed anomaly color scale.
	 */
	private void drawColorBar()
	{
		int barX = width - 40;
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
		text( String.format( "+%.0f mGal" , COLOR_LIMIT_MILLIGAL ) , barX - 4 , barY );
		text( "0" , barX - 4 , barY + barHeight * 0.5f );
		text( String.format( "-%.0f mGal" , COLOR_LIMIT_MILLIGAL ) , barX - 4 , barY + barHeight );
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
	 *
	 * @return	horizontal visible patch half-angle, in degrees.
	 */
	private double visibleHorizontalHalfSpanDegrees()
	{
		double verticalHalfAngle = Math.toRadians( CAMERA_FOV_DEGREES * 0.5 );
		double horizontalHalfAngle = Math.atan( Math.tan( verticalHalfAngle ) * (double) width / (double) height );
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
	 * Maps an anomaly value to the fixed rainbow color scale.
	 *
	 * @param anomalyMilligal	gravity anomaly in [mGal].
	 * @return	packed color.
	 */
	private int anomalyColor( double anomalyMilligal )
	{
		double t = 0.5 + 0.5 * anomalyMilligal / COLOR_LIMIT_MILLIGAL;
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
		light.add( PVector.mult( screenRight , 0.76f ) );
		light.add( PVector.mult( screenDown , -0.22f ) );
		light.add( PVector.mult( view , 0.60f ) );
		light.normalize();
		return light;
	}


	/**
	 * Estimates the outward normal of an exaggerated mesh vertex from neighboring mesh coordinates.
	 *
	 * @param i	latitude index.
	 * @param j	longitude index.
	 * @return	unit normal vector.
	 */
	private PVector meshNormal( int i , int j )
	{
		int im = Math.max( 0 , i - 1 );
		int ip = Math.min( GRID_N - 1 , i + 1 );
		int jm = Math.max( 0 , j - 1 );
		int jp = Math.min( GRID_N - 1 , j + 1 );

		PVector dLatitude = new PVector(
				this.meshX[ip][j] - this.meshX[im][j] ,
				this.meshY[ip][j] - this.meshY[im][j] ,
				this.meshZ[ip][j] - this.meshZ[im][j] );
		PVector dLongitude = new PVector(
				this.meshX[i][jp] - this.meshX[i][jm] ,
				this.meshY[i][jp] - this.meshY[i][jm] ,
				this.meshZ[i][jp] - this.meshZ[i][jm] );

		PVector normal = cross( dLatitude , dLongitude );
		PVector outward = new PVector( this.meshX[i][j] , this.meshY[i][j] , this.meshZ[i][j] );
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
	 * Applies a warm right-side key light and cool shadow tint to a scientific anomaly color.
	 *
	 * @param baseColor		scientific anomaly color.
	 * @param normal		mesh surface normal.
	 * @param lightDirection	direction from the surface toward the key light.
	 * @param viewDirection	direction from globe center toward the camera.
	 * @return	lit display color.
	 */
	private int litMeshColor( int baseColor , PVector normal , PVector lightDirection , PVector viewDirection )
	{
		float light = constrain( normal.dot( lightDirection ) , -1.0f , 1.0f );
		float diffuse = (float) Math.pow( Math.max( 0.0f , light ) , 0.72 );
		float shade = MESH_LIGHT_AMBIENT + MESH_LIGHT_DIFFUSE * diffuse;
		float shadow = Math.max( 0.0f , -light );
		float rim = (float) Math.pow( Math.max( 0.0f , 1.0f - Math.max( 0.0f , normal.dot( viewDirection ) ) ) , 2.0 );

		float r = red( baseColor ) * shade + 18.0f * diffuse + 8.0f * rim;
		float g = green( baseColor ) * shade + 12.0f * diffuse + 10.0f * rim;
		float b = blue( baseColor ) * ( shade + MESH_SHADOW_BLUE * shadow ) + 15.0f * rim;
		return color( constrain( r , 0.0f , 255.0f ) ,
				constrain( g , 0.0f , 255.0f ) ,
				constrain( b , 0.0f , 255.0f ) );
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
	 * Returns the display radius for an anomaly, with a square-root response to make small details visible.
	 *
	 * @param anomalyMilligal	gravity anomaly in [mGal].
	 * @return	exaggerated display radius.
	 */
	private static float radiusForAnomaly( double anomalyMilligal )
	{
		double normalized = Math.max( -1.0 , Math.min( 1.0 , anomalyMilligal / RELIEF_LIMIT_MILLIGAL ) );
		double shaped = Math.copySign( Math.sqrt( Math.abs( normalized ) ) , normalized );
		return (float) ( GLOBE_RADIUS + RELIEF_EXAGGERATION * shaped );
	}


	/**
	 * Returns the current mesh radius at a coastline lon/lat point, plus a tiny drawing offset.
	 *
	 * @param latitudeDegrees	latitude in [deg].
	 * @param longitudeDegrees	longitude in [deg].
	 * @return	coastline display radius.
	 */
	@Override
	public float radiusAt( double latitudeDegrees , double longitudeDegrees )
	{
		if( this.meshCellLatDegrees <= 0.0 || this.meshCellLonDegrees <= 0.0
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

		double r00 = this.meshRadius[i0][j0];
		double r01 = this.meshRadius[i0][j0+1];
		double r10 = this.meshRadius[i0+1][j0];
		double r11 = this.meshRadius[i0+1][j0+1];
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
				"EGM2008 gravity anomaly%n" +
				"drag: latitude/longitude   wheel: zoom   space: regenerate%n" +
				"view center: %.1f deg lat , %.1f deg lon   half-span: %.2f deg lat , %.2f deg lon%n" +
				"mesh center: %.1f deg lat , %.1f deg lon   half-span: %.2f deg lat , %.2f deg lon%n" +
				"mesh anomaly: %.0f to %.0f mGal   degree: %d / %d%n" +
				"%s" ,
				this.centerLatitude , wrapLongitude( this.centerLongitude ) ,
				visibleVerticalHalfSpanDegrees() , visibleHorizontalHalfSpanDegrees() ,
				this.meshCenterLatitude , wrapLongitude( this.meshCenterLongitude ) ,
				this.meshHalfLatSpan , this.meshHalfLonSpan ,
				this.anomalyMin , this.anomalyMax , this.viewDegree , this.modelMaxDegree ,
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
		this.legendre = new PreNormalizedAssociatedLegendrePolynomialEvaluator( Math.max( 1 , this.modelMaxDegree ) );

		this.geodeticFactor = new double[ this.modelMaxDegree + 1 ];
		for( int m=0; m<this.geodeticFactor.length; m++ ) {
			double sign = ( m % 2 == 0 ) ? 1.0 : -1.0;
			double normalization = ( m == 0 ) ? 1.0 : 2.0;
			this.geodeticFactor[m] = sign * Math.sqrt( 4.0 * Math.PI * normalization );
		}

		this.cosMLambda = new double[ this.modelMaxDegree + 1 ];
		this.sinMLambda = new double[ this.modelMaxDegree + 1 ];

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
		return locateExistingPath( GFC_CANDIDATE_PATHS );
	}


	/**
	 * Returns the first existing candidate path, or {@code null} if none exist.
	 *
	 * @param candidatePaths	relative paths to check.
	 * @return	first existing path, or {@code null}.
	 */
	private static String locateExistingPath( String[] candidatePaths )
	{
		for( String candidatePath : candidatePaths ) {
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

}
