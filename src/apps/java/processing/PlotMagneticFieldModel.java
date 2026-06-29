package processing;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import controlP5.ControlP5;
import controlP5.RadioButton;
import controlP5.Slider;
import geophysicalModelLibrary.magnetic.WorldMagneticModel;
import numericalLibrary.types.Vector3;
import utils.Coastlines;

import processing.core.PApplet;
import processing.core.PShape;
import processing.core.PVector;
import processing.event.MouseEvent;



/**
 * Interactive 3D visualization of the World Magnetic Model (WMM).
 * <p>
 * The Earth is drawn as a globe with coastlines, and the magnetic field is shown as a grid of glyphs over a spherical
 * shell whose radius grows with a selectable altitude, so the altitude slider scans through altitude shells. Each glyph
 * is a stick capped at its head by a small three-sided pyramid; a radio button selects what it shows:
 * <ul>
 * <li> 3D vector: the full field direction, colored by total intensity.
 * <li> horizontal needle: the field projected onto the local north-east plane at fixed length, colored by the vertical
 *      component (red where the field points up / out of the ground, blue where it points down / in).
 * <li> horizontal magnitude: the same horizontal projection but with the stick length scaled by the horizontal
 *      magnitude, also colored by the vertical component.
 * </ul>
 * Two ControlP5 sliders control the decimal year (within the model validity window, exercising the secular variation)
 * and the altitude.
 * <p>
 * Interaction:
 * <ul>
 * <li> Drag with the mouse to rotate the globe (away from the controls).
 * <li> Mouse wheel to zoom in and out.
 * </ul>
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
	 * Field representations selectable with the radio buttons.
	 * {@link #REPRESENTATION_VECTOR_3D} draws the full field direction, colored by total intensity.
	 * {@link #REPRESENTATION_HORIZONTAL} draws the field projected onto the local north-east plane as a fixed-length
	 * compass needle, colored by the vertical component (red where the field points up/out, blue where it points down/in).
	 * {@link #REPRESENTATION_HORIZONTAL_MAGNITUDE} draws the same horizontal projection but with the stick length scaled by
	 * the horizontal magnitude, also colored by the vertical component.
	 */
	private static final int REPRESENTATION_VECTOR_3D = 0;
	private static final int REPRESENTATION_HORIZONTAL = 1;
	private static final int REPRESENTATION_HORIZONTAL_MAGNITUDE = 2;

	/**
	 * Height and base radius of a glyph's three-sided pyramid tip, as fractions of that glyph's length.
	 */
	private static final float PYRAMID_HEIGHT_FRACTION = 0.25f;
	private static final float PYRAMID_RADIUS_FRACTION = 0.1f;

	/**
	 * Total-intensity range mapped to the rainbow color scale in the 3D-vector representation. [nT]
	 */
	private static final float INTENSITY_MIN_NANOTESLA = 22000.0f;
	private static final float INTENSITY_MAX_NANOTESLA = 66000.0f;

	/**
	 * Vertical-component magnitude mapped to fully saturated red / blue in the horizontal representations. [nT]
	 */
	private static final float VERTICAL_COMPONENT_SCALE_NANOTESLA = 70000.0f;

	/**
	 * Horizontal magnitude that maps to one glyph length in the horizontal-magnitude representation. [nT]
	 */
	private static final float HORIZONTAL_INTENSITY_SCALE_NANOTESLA = 30000.0f;

	/**
	 * Maximum altitude offered by the altitude slider, in [km].
	 */
	private static final float MAX_ALTITUDE_KILOMETERS = 2000.0f;

	/**
	 * Location of the WMM {@code .COF} file, relative to the working directory.
	 */
	private static final String COF_PATH = "res/magnetic/WMM2025COF/WMM.COF";



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
	 * Radio button selecting the field representation.
	 */
	private RadioButton representationRadio;

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
	 * Year, altitude [km], and representation for which {@link #fieldGlyphs} was built, to detect changes.
	 */
	private double builtYear = Double.NaN;
	private double builtAltitudeKilometers = Double.NaN;
	private int builtRepresentation = -1;



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

		this.representationRadio = this.controls.addRadioButton( "representation" )
				.setPosition( this.width - 240 , 24 ).setSize( 14 , 14 ).setSpacingRow( 6 )
				.addItem( "3D vector (intensity)" , REPRESENTATION_VECTOR_3D )
				.addItem( "horizontal needle (vertical comp.)" , REPRESENTATION_HORIZONTAL )
				.addItem( "horizontal magnitude (vertical comp.)" , REPRESENTATION_HORIZONTAL_MAGNITUDE )
				.activate( REPRESENTATION_VECTOR_3D );
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
		int representation = selectedValue( this.representationRadio , REPRESENTATION_VECTOR_3D );
		if(  year != this.builtYear  ||  altitudeKilometers != this.builtAltitudeKilometers
				||  representation != this.builtRepresentation  ) {
			this.model.setDecimalYear( year );
			this.fieldGlyphs = buildFieldGlyphs( altitudeKilometers * 1000.0 , representation );
			this.builtYear = year;
			this.builtAltitudeKilometers = altitudeKilometers;
			this.builtRepresentation = representation;
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

		drawHeadsUpDisplay( headsUpText( year , altitudeKilometers , representation ) );
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
	 * <p>
	 * The glyphs sit on a shell whose radius grows with the altitude, so moving the altitude slider scans visibly through
	 * altitude shells. Each glyph is a stick capped at its head by a three-sided pyramid, oriented, lengthened, and colored
	 * according to {@code representation}. The geometry is returned as a group of two children (the sticks as lines and the
	 * pyramid tips as triangles) so the lines and the filled tips can keep their own rendering styles.
	 *
	 * @param altitudeMeters	geometric altitude above the reference sphere. [m]
	 * @param representation	{@link #REPRESENTATION_VECTOR_3D}, {@link #REPRESENTATION_HORIZONTAL}, or
	 *							{@link #REPRESENTATION_HORIZONTAL_MAGNITUDE}.
	 * @return	group shape holding the glyph geometry.
	 */
	private PShape buildFieldGlyphs( double altitudeMeters , int representation )
	{
		double radius = WorldMagneticModel.GEOMAGNETIC_REFERENCE_RADIUS + altitudeMeters;
		// The glyph shell rises with the altitude, so moving the slider scans visibly through altitude shells.
		float shellRadius = (float) ( GLOBE_RADIUS * radius / WorldMagneticModel.GEOMAGNETIC_REFERENCE_RADIUS ) + GLYPH_LIFT;

		PShape group = createShape( GROUP );
		PShape sticks = createShape();
		sticks.beginShape( LINES );
		sticks.noFill();
		sticks.strokeWeight( 1.5f );
		PShape tips = createShape();
		tips.beginShape( TRIANGLES );
		tips.noStroke();

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

				// Outward vertical (up) component of the field.
				double upComponent = field.x() * geoX + field.y() * geoY + field.z() * geoZ;

				// Field direction to draw (geocentric frame), glyph length, and color, depending on the representation.
				double directionGeoX;
				double directionGeoY;
				double directionGeoZ;
				float glyphLength;
				int glyphColor;
				if( representation == REPRESENTATION_HORIZONTAL  ||  representation == REPRESENTATION_HORIZONTAL_MAGNITUDE ) {
					// Field projected onto the local horizontal (north-east) plane: remove the radial part.
					double hx = field.x() - upComponent * geoX;
					double hy = field.y() - upComponent * geoY;
					double hz = field.z() - upComponent * geoZ;
					double hmag = Math.sqrt( hx * hx + hy * hy + hz * hz );
					if( hmag < 1.0e-9 ) {
						// Field is essentially vertical (near a magnetic pole): no horizontal direction to show.
						continue;
					}
					directionGeoX = hx / hmag;
					directionGeoY = hy / hmag;
					directionGeoZ = hz / hmag;
					glyphLength = ( representation == REPRESENTATION_HORIZONTAL_MAGNITUDE )
							? (float) ( GLYPH_LENGTH * hmag / HORIZONTAL_INTENSITY_SCALE_NANOTESLA )
							: GLYPH_LENGTH;
					glyphColor = verticalComponentColor( upComponent );
				} else {
					directionGeoX = field.x() / intensity;
					directionGeoY = field.y() / intensity;
					directionGeoZ = field.z() / intensity;
					glyphLength = GLYPH_LENGTH;
					glyphColor = intensityColor( (float) intensity );
				}

				// Map direction and outward radial into the display frame: geo ( x , y , z ) -> display ( y , -z , x ).
				float directionX = (float) directionGeoY;
				float directionY = (float) -directionGeoZ;
				float directionZ = (float) directionGeoX;
				float radialX = (float) geoY;
				float radialY = (float) -geoZ;
				float radialZ = (float) geoX;

				float centerX = shellRadius * radialX;
				float centerY = shellRadius * radialY;
				float centerZ = shellRadius * radialZ;

				float half = 0.5f * glyphLength;
				float tailX = centerX - half * directionX;
				float tailY = centerY - half * directionY;
				float tailZ = centerZ - half * directionZ;
				float headX = centerX + half * directionX;
				float headY = centerY + half * directionY;
				float headZ = centerZ + half * directionZ;

				sticks.stroke( glyphColor );
				sticks.vertex( tailX , tailY , tailZ );
				sticks.vertex( headX , headY , headZ );

				addPyramidTip( tips , headX , headY , headZ , directionX , directionY , directionZ ,
						radialX , radialY , radialZ , glyphLength , glyphColor );
			}
		}

		sticks.endShape();
		tips.endShape();
		group.addChild( sticks );
		group.addChild( tips );
		return group;
	}


	/**
	 * Adds a small three-sided pyramid, apex at the glyph head and base behind it, capping the stick to show its direction.
	 * The base ring lies in the plane perpendicular to the field direction, oriented from the plane spanned by the field
	 * direction and the outward radial. The three side faces are given slightly different brightness so the pyramid reads
	 * as a solid shape even though the glyphs are drawn unlit.
	 *
	 * @param tips			shape collecting the pyramid triangles.
	 * @param headX			pyramid apex x in the display frame. [world units]
	 * @param headY			pyramid apex y in the display frame. [world units]
	 * @param headZ			pyramid apex z in the display frame. [world units]
	 * @param directionX	unit field direction x in the display frame.
	 * @param directionY	unit field direction y in the display frame.
	 * @param directionZ	unit field direction z in the display frame.
	 * @param radialX		unit outward radial x in the display frame (reference for the base orientation).
	 * @param radialY		unit outward radial y in the display frame.
	 * @param radialZ		unit outward radial z in the display frame.
	 * @param glyphLength	length of the glyph this pyramid caps; the pyramid is sized as a fraction of it. [world units]
	 * @param glyphColor	base color of the pyramid.
	 */
	private void addPyramidTip( PShape tips ,
			float headX , float headY , float headZ ,
			float directionX , float directionY , float directionZ ,
			float radialX , float radialY , float radialZ ,
			float glyphLength , int glyphColor )
	{
		// First base axis: perpendicular to the field, in the plane of the field and the radial.
		float ux = directionY * radialZ - directionZ * radialY;
		float uy = directionZ * radialX - directionX * radialZ;
		float uz = directionX * radialY - directionY * radialX;
		float un = (float) Math.sqrt( ux * ux + uy * uy + uz * uz );
		if( un < 1.0e-4f ) {
			ux = -directionZ;
			uy = 0.0f;
			uz = directionX;
			un = (float) Math.sqrt( ux * ux + uy * uy + uz * uz );
			if( un < 1.0e-4f ) {
				ux = 1.0f;
				uy = 0.0f;
				uz = 0.0f;
				un = 1.0f;
			}
		}
		ux /= un;
		uy /= un;
		uz /= un;
		// Second base axis: perpendicular to both ( direction x u ), already unit length.
		float vx = directionY * uz - directionZ * uy;
		float vy = directionZ * ux - directionX * uz;
		float vz = directionX * uy - directionY * ux;

		float height = PYRAMID_HEIGHT_FRACTION * glyphLength;
		float baseRadius = PYRAMID_RADIUS_FRACTION * glyphLength;
		float baseCenterX = headX - height * directionX;
		float baseCenterY = headY - height * directionY;
		float baseCenterZ = headZ - height * directionZ;

		// Three base vertices at 0, 120, 240 degrees around the base center.
		float[] cosines = { 1.0f , -0.5f , -0.5f };
		float[] sines = { 0.0f , 0.8660254f , -0.8660254f };
		float[] baseX = new float[3];
		float[] baseY = new float[3];
		float[] baseZ = new float[3];
		for( int k=0; k<3; k++ ) {
			baseX[k] = baseCenterX + baseRadius * ( cosines[k] * ux + sines[k] * vx );
			baseY[k] = baseCenterY + baseRadius * ( cosines[k] * uy + sines[k] * vy );
			baseZ[k] = baseCenterZ + baseRadius * ( cosines[k] * uz + sines[k] * vz );
		}

		// Three side faces, all darker than the full-brightness stick (so it stays visible passing through the pyramid),
		// each shaded a bit differently so the facets are distinguishable.
		float[] shades = { 0.6f , 0.45f , 0.32f };
		for( int k=0; k<3; k++ ) {
			int next = ( k + 1 ) % 3;
			tips.fill( shadeColor( glyphColor , shades[k] ) );
			tips.vertex( headX , headY , headZ );
			tips.vertex( baseX[k] , baseY[k] , baseZ[k] );
			tips.vertex( baseX[next] , baseY[next] , baseZ[next] );
		}
	}


	/**
	 * Maps a total field intensity to the rainbow color scale.
	 *
	 * @param intensity	total field intensity. [nT]
	 * @return	packed color.
	 */
	private int intensityColor( float intensity )
	{
		float t = ( intensity - INTENSITY_MIN_NANOTESLA ) / ( INTENSITY_MAX_NANOTESLA - INTENSITY_MIN_NANOTESLA );
		return rainbowColor( t );
	}


	/**
	 * Maps the vertical (up) component of the field to a diverging color: red where the field points up / out of the
	 * ground, blue where it points down / into the ground, and pale where it is nearly horizontal.
	 *
	 * @param upComponent	outward vertical component of the field. [nT]
	 * @return	packed color.
	 */
	private int verticalComponentColor( double upComponent )
	{
		float t = constrain( (float) ( upComponent / VERTICAL_COMPONENT_SCALE_NANOTESLA ) , -1.0f , 1.0f );
		int paleColor = color( 240 , 240 , 240 );
		int upColor = color( 255 , 70 , 40 );
		int downColor = color( 40 , 110 , 255 );
		if( t >= 0.0f ) {
			return lerpColor( paleColor , upColor , t );
		}
		return lerpColor( paleColor , downColor , -t );
	}


	/**
	 * Maps a normalized value to a blue-to-red rainbow color.
	 *
	 * @param t		value in [0,1] (clamped).
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
	 * Scales the brightness of a color by a factor, keeping it opaque.
	 *
	 * @param packedColor	color to scale.
	 * @param factor		brightness factor.
	 * @return	scaled color.
	 */
	private int shadeColor( int packedColor , float factor )
	{
		return color( red( packedColor ) * factor , green( packedColor ) * factor , blue( packedColor ) * factor );
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
	 * @param representation		active field representation.
	 * @return	heads-up text.
	 */
	private String headsUpText( double year , double altitudeKilometers , int representation )
	{
		double[] field = fieldDeclinationInclinationIntensity( this.centerLatitude , this.centerLongitude , altitudeKilometers * 1000.0 );
		String legend;
		if( representation == REPRESENTATION_HORIZONTAL ) {
			legend = "horizontal direction (fixed length); color = vertical component (red up / out, blue down / in)";
		} else if( representation == REPRESENTATION_HORIZONTAL_MAGNITUDE ) {
			legend = "horizontal field (length = magnitude); color = vertical component (red up / out, blue down / in)";
		} else {
			legend = "3D field direction; color = total intensity (blue low to red high)";
		}
		return String.format(
				"%s  (%s)%n" +
				"drag: rotate   wheel: zoom%n" +
				"year: %.2f   altitude: %.0f km%n" +
				"view center: %.1f deg lat , %.1f deg lon%n" +
				"center field:  declination %.2f deg , inclination %.2f deg , intensity %.0f nT" ,
				this.model.modelName() , legend ,
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
		if( !Files.exists( Paths.get( COF_PATH ) ) ) {
			this.loadError = "WMM.COF not found at " + COF_PATH;
			return;
		}
		try {
			this.model = WorldMagneticModel.fromFilePath( COF_PATH );
			this.modelLoaded = true;
		} catch( IOException e ) {
			this.loadError = "Could not load WMM.COF: " + e.getMessage();
		}
	}

}
