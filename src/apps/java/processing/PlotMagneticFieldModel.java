package processing;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import controlP5.ControlP5;
import controlP5.Slider;

import geophysicalModelLibrary.magneticModels.WorldMagneticModel;
import numericalLibrary.types.Vector3;
import utils.Coastlines;

import processing.core.PApplet;
import processing.core.PShape;
import processing.core.PVector;
import processing.event.MouseEvent;



/**
 * Interactive 3D visualization of the World Magnetic Model (WMM).
 * <p>
 * The Earth is drawn as a globe with coastlines, and the magnetic field is shown as a grid of vector glyphs over a
 * spherical shell at a selectable altitude: each glyph is oriented along the field (showing the declination and
 * inclination) and colored by the total field intensity. Two ControlP5 sliders control the decimal year (within the
 * model validity window, exercising the secular variation) and the altitude.
 * <p>
 * Interaction:
 * <ul>
 * <li> Drag with the mouse to rotate the globe (away from the sliders).
 * <li> Mouse wheel to zoom in and out.
 * </ul>
 * This is a first version, meant to be refined (for example with field-line tracing, or coloring by declination /
 * inclination instead of intensity).
 */
public class PlotMagneticFieldModel
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
	private static final float CAMERA_FOV_DEGREES = 50.0f;

	/**
	 * Small radial offset that keeps coastline strokes above the globe surface without visibly floating.
	 */
	private static final float COASTLINE_SURFACE_OFFSET = 0.8f;

	/**
	 * Latitude / longitude spacing of the field-glyph grid, in degrees.
	 */
	private static final double GRID_STEP_DEGREES = 1.0;

	/**
	 * Highest latitude at which a glyph is drawn, in degrees (the poles are skipped to avoid clutter).
	 */
	private static final double GRID_LATITUDE_LIMIT_DEGREES = 89.0;

	/**
	 * Length of a field glyph in world units.
	 */
	private static final float GLYPH_LENGTH = 2.0f;

	/**
	 * Radial lift of a glyph's center above the globe surface, in world units, so its outward half stays visible.
	 */
	private static final float GLYPH_LIFT = 2.0f;

	/**
	 * Field intensities mapped to the bottom and top of the color scale, in [nT].
	 */
	private static final float INTENSITY_MIN_NANOTESLA = 20000.0f;
	private static final float INTENSITY_MAX_NANOTESLA = 68000.0f;

	/**
	 * Maximum altitude offered by the altitude slider, in [km].
	 */
	private static final float MAX_ALTITUDE_KILOMETERS = 2000.0f;

	/**
	 * Candidate locations of the WMM {@code .COF} file, relative to the working directory.
	 */
	private static final String[] COF_CANDIDATE_PATHS = {
			"res/WMM2025COF/WMM.COF",
			"lib/OpenGeophysicalModelLibrary-java/res/WMM2025COF/WMM.COF",
	};



	////////////////////////////////////////////////////////////////
	/// STATE
	////////////////////////////////////////////////////////////////

	/**
	 * Loaded magnetic model.
	 */
	private WorldMagneticModel model;

	/**
	 * Flag indicating whether the model finished loading.
	 */
	private boolean modelLoaded;

	/**
	 * Error message shown when the model could not be loaded.
	 */
	private String loadError;

	/**
	 * Coastline line strips at the globe surface.
	 */
	private Coastlines coastlines = Coastlines.empty( "coastlines: not loaded" );

	/**
	 * ControlP5 instance hosting the sliders.
	 */
	private ControlP5 controls;

	/**
	 * Sliders for the decimal year and the altitude [km].
	 */
	private Slider yearSlider;
	private Slider altitudeSlider;

	/**
	 * Latitude and longitude (in degrees) of the point brought in front of the camera.
	 */
	private double centerLatitude = 20.0;
	private double centerLongitude = 0.0;

	/**
	 * Camera altitude above the globe surface, in world units (zoom).
	 */
	private float cameraAltitude = GLOBE_RADIUS;

	/**
	 * Retained field-glyph geometry, rebuilt when the year or altitude changes.
	 */
	private PShape fieldGlyphs;

	/**
	 * Year and altitude [km] for which {@link #fieldGlyphs} was built, to detect slider changes.
	 */
	private double builtYear = Double.NaN;
	private double builtAltitudeKilometers = Double.NaN;



	////////////////////////////////////////////////////////////////
	/// MAIN
	////////////////////////////////////////////////////////////////

	public static void main( String[] args )
	{
		PApplet.main( "processing.PlotMagneticFieldModel" );
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
		loadModel();
		this.coastlines = Coastlines.loadNaturalEarth110m( GLOBE_RADIUS + COASTLINE_SURFACE_OFFSET );

		double epoch = this.modelLoaded ? this.model.epoch() : 2025.0;
		this.controls = new ControlP5( this );
		this.controls.setAutoDraw( false );
		this.yearSlider = this.controls.addSlider( "year" )
				.setPosition( 20 , this.height - 60 ).setSize( 320 , 18 )
				.setRange( (float) epoch , (float) ( epoch + 5.0 ) ).setValue( (float) epoch );
		this.altitudeSlider = this.controls.addSlider( "altitude [km]" )
				.setPosition( 20 , this.height - 32 ).setSize( 320 , 18 )
				.setRange( 0.0f , MAX_ALTITUDE_KILOMETERS ).setValue( 0.0f );
	}


	public void draw()
	{
		background( 8 );

		if( !this.modelLoaded ) {
			drawHeadsUpDisplay( ( this.loadError != null ) ? this.loadError : "Loading WMM ..." );
			return;
		}

		double year = this.yearSlider.getValue();
		double altitudeKilometers = this.altitudeSlider.getValue();
		if(  year != this.builtYear  ||  altitudeKilometers != this.builtAltitudeKilometers  ) {
			this.model.setDecimalYear( year );
			this.fieldGlyphs = buildFieldGlyphs( altitudeKilometers * 1000.0 );
			this.builtYear = year;
			this.builtAltitudeKilometers = altitudeKilometers;
		}

		applyProjectionAndCamera();

		// Globe: a lit backing sphere with a head-light so the side facing the camera is always visible.
		PVector view = surfaceDirection( this.centerLatitude , this.centerLongitude );
		ambientLight( 70 , 70 , 78 );
		directionalLight( 210 , 210 , 215 , -view.x , -view.y , -view.z );
		noStroke();
		fill( 45 );
		sphere( GLOBE_RADIUS );

		// Coastlines and field glyphs are drawn unlit, with their own colors.
		noLights();
		this.coastlines.updateGeometry( this );
		this.coastlines.draw( this );
		if( this.fieldGlyphs != null ) {
			shape( this.fieldGlyphs );
		}

		drawHeadsUpDisplay( headsUpText( year , altitudeKilometers ) );
	}



	////////////////////////////////////////////////////////////////
	/// INTERACTION
	////////////////////////////////////////////////////////////////

	public void mouseDragged()
	{
		if( this.controls.isMouseOver() ) {
			return;
		}
		double sensitivity = 0.25 * ( this.cameraAltitude + GLOBE_RADIUS ) / ( 2.0 * GLOBE_RADIUS );
		this.centerLatitude += ( mouseY - pmouseY ) * sensitivity;
		this.centerLongitude -= ( mouseX - pmouseX ) * sensitivity / Math.max( Math.cos( Math.toRadians( this.centerLatitude ) ) , 0.1 );
		this.centerLatitude = Math.max( -89.0 , Math.min( 89.0 , this.centerLatitude ) );
	}


	public void mouseWheel( MouseEvent event )
	{
		if( this.controls.isMouseOver() ) {
			return;
		}
		float factor = ( event.getCount() > 0 ) ? 1.2f : 1.0f / 1.2f;
		this.cameraAltitude = constrain( this.cameraAltitude * factor , GLOBE_RADIUS * 0.05f , GLOBE_RADIUS * 40.0f );
	}



	////////////////////////////////////////////////////////////////
	/// FIELD GLYPHS
	////////////////////////////////////////////////////////////////

	/**
	 * Builds the field-glyph geometry over the grid, evaluating the model at the given altitude.
	 *
	 * @param altitudeMeters	geometric altitude above the reference sphere. [m]
	 * @return	shape of colored line glyphs, one per grid point.
	 */
	private PShape buildFieldGlyphs( double altitudeMeters )
	{
		double radius = WorldMagneticModel.GEOMAGNETIC_REFERENCE_RADIUS + altitudeMeters;
		PShape shape = createShape();
		shape.beginShape( LINES );
		shape.noFill();
		shape.strokeWeight( 1.5f );
		for( double latitude=-GRID_LATITUDE_LIMIT_DEGREES; latitude<=GRID_LATITUDE_LIMIT_DEGREES; latitude+=GRID_STEP_DEGREES ) {
			double latitudeRad = Math.toRadians( latitude );
			double cosLatitude = Math.cos( latitudeRad );
			double sinLatitude = Math.sin( latitudeRad );
			for( double longitude=-180.0; longitude<180.0; longitude+=GRID_STEP_DEGREES ) {
				double longitudeRad = Math.toRadians( longitude );
				double geoX = cosLatitude * Math.cos( longitudeRad );
				double geoY = cosLatitude * Math.sin( longitudeRad );
				double geoZ = sinLatitude;

				this.model.setPosition( Vector3.fromComponents( radius * geoX , radius * geoY , radius * geoZ ) );
				Vector3 field = this.model.getMagneticField();
				double intensity = field.norm();
				if( intensity <= 0.0 ) {
					continue;
				}

				// Unit field direction, mapped into the display frame: geo ( x , y , z ) -> display ( y , -z , x ).
				float unitX = (float) ( field.y() / intensity );
				float unitY = (float) ( -field.z() / intensity );
				float unitZ = (float) ( field.x() / intensity );

				// Glyph center, lifted slightly above the globe surface.
				float anchorScale = GLOBE_RADIUS + GLYPH_LIFT;
				float centerX = (float) ( anchorScale * geoY );
				float centerY = (float) ( -anchorScale * geoZ );
				float centerZ = (float) ( anchorScale * geoX );

				float half = 0.5f * GLYPH_LENGTH;
				shape.stroke( intensityColor( (float) intensity ) );
				shape.vertex( centerX - half * unitX , centerY - half * unitY , centerZ - half * unitZ );
				shape.vertex( centerX + half * unitX , centerY + half * unitY , centerZ + half * unitZ );
			}
		}
		shape.endShape();
		return shape;
	}


	/**
	 * Maps a field intensity to the rainbow color scale.
	 *
	 * @param intensity	field intensity. [nT]
	 * @return	packed color.
	 */
	private int intensityColor( float intensity )
	{
		return rainbowColor( ( intensity - INTENSITY_MIN_NANOTESLA ) / ( INTENSITY_MAX_NANOTESLA - INTENSITY_MIN_NANOTESLA ) );
	}


	/**
	 * Returns the declination, inclination, and total intensity of the field at a point.
	 *
	 * @param latitudeDegrees	latitude. [deg]
	 * @param longitudeDegrees	longitude. [deg]
	 * @param altitudeMeters	geometric altitude above the reference sphere. [m]
	 * @return	{ declination [deg], inclination [deg], total intensity [nT] }.
	 */
	private double[] fieldDeclinationInclinationIntensity( double latitudeDegrees , double longitudeDegrees , double altitudeMeters )
	{
		double latitudeRad = Math.toRadians( latitudeDegrees );
		double longitudeRad = Math.toRadians( longitudeDegrees );
		double cosLatitude = Math.cos( latitudeRad );
		double sinLatitude = Math.sin( latitudeRad );
		double cosLongitude = Math.cos( longitudeRad );
		double sinLongitude = Math.sin( longitudeRad );
		double geoX = cosLatitude * cosLongitude;
		double geoY = cosLatitude * sinLongitude;
		double geoZ = sinLatitude;

		double radius = WorldMagneticModel.GEOMAGNETIC_REFERENCE_RADIUS + altitudeMeters;
		this.model.setPosition( Vector3.fromComponents( radius * geoX , radius * geoY , radius * geoZ ) );
		Vector3 field = this.model.getMagneticField();

		// Local north, east, down components ( the model frame is aligned with the geocentric axes ).
		double north = -sinLatitude * cosLongitude * field.x() - sinLatitude * sinLongitude * field.y() + cosLatitude * field.z();
		double east = -sinLongitude * field.x() + cosLongitude * field.y();
		double down = -geoX * field.x() - geoY * field.y() - geoZ * field.z();

		double horizontal = Math.hypot( north , east );
		double declination = Math.toDegrees( Math.atan2( east , north ) );
		double inclination = Math.toDegrees( Math.atan2( down , horizontal ) );
		return new double[]{ declination , inclination , field.norm() };
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
		perspective( radians( CAMERA_FOV_DEGREES ) , (float) this.width / this.height , GLOBE_RADIUS * 0.01f , GLOBE_RADIUS * 100.0f );

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
	 * Draws the heads-up text and the sliders in screen space.
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
	 * @param year					current decimal year.
	 * @param altitudeKilometers	current altitude. [km]
	 * @return	heads-up text.
	 */
	private String headsUpText( double year , double altitudeKilometers )
	{
		double[] field = fieldDeclinationInclinationIntensity( this.centerLatitude , this.centerLongitude , altitudeKilometers * 1000.0 );
		return String.format(
				"%s  (vectors colored by total intensity)%n" +
				"drag: rotate   wheel: zoom%n" +
				"year: %.2f   altitude: %.0f km%n" +
				"view center: %.1f deg lat , %.1f deg lon%n" +
				"center field:  declination %.2f deg , inclination %.2f deg , intensity %.0f nT" ,
				this.model.modelName() ,
				year , altitudeKilometers ,
				this.centerLatitude , wrapLongitude( this.centerLongitude ) ,
				field[0] , field[1] , field[2] );
	}


	/**
	 * Returns the outward unit direction of the globe surface at the given latitude and longitude, mapped into
	 * Processing's display frame: +X east at Greenwich, +Y south, +Z toward lon/lat 0/0.
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
	 * Returns local geodetic north at a lon/lat point, mapped into Processing's display frame.
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
	 * Wraps a longitude into the interval [-180, 180] for display.
	 *
	 * @param longitudeDegrees	longitude. [deg]
	 * @return	wrapped longitude. [deg]
	 */
	private static double wrapLongitude( double longitudeDegrees )
	{
		double wrapped = ( ( longitudeDegrees + 180.0 ) % 360.0 + 360.0 ) % 360.0 - 180.0;
		return wrapped;
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
	 * Loads the World Magnetic Model from the first {@code .COF} file found, recording an error message otherwise.
	 */
	private void loadModel()
	{
		String path = locateCofPath();
		if( path == null ) {
			this.loadError = "WMM.COF not found in " + String.join( " or " , COF_CANDIDATE_PATHS );
			return;
		}
		try {
			this.model = WorldMagneticModel.fromFilePath( path );
			this.modelLoaded = true;
		} catch( IOException e ) {
			this.loadError = "Could not load WMM.COF: " + e.getMessage();
		}
	}


	/**
	 * Returns the path to the WMM {@code .COF} file, or {@code null} if it cannot be found.
	 *
	 * @return	path to the {@code .COF} file, or {@code null}.
	 */
	private static String locateCofPath()
	{
		for( String candidatePath : COF_CANDIDATE_PATHS ) {
			if( Files.exists( Paths.get( candidatePath ) ) ) {
				return candidatePath;
			}
		}
		return null;
	}

}
