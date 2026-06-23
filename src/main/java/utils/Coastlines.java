package utils;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import processing.core.PApplet;



/**
 * Loader for Natural Earth coastline line strings converted to display-space globe coordinates.
 */
public final class Coastlines
{
	/**
	 * Candidate Natural Earth coastline files, relative to the working directory.
	 */
	private static final String[] NATURAL_EARTH_110M_CANDIDATE_PATHS = {
			"res/ne_110m_coastline.geojson",
			"src/main/resources/coastlines/ne_110m_coastline.geojson",
			"target/classes/coastlines/ne_110m_coastline.geojson",
	};

	/**
	 * Coastline line strips.
	 */
	private final List<Segment> segments;

	/**
	 * Human-readable loading status.
	 */
	private final String status;


	private Coastlines( List<Segment> segments , String status )
	{
		this.segments = Collections.unmodifiableList( segments );
		this.status = status;
	}


	/**
	 * Creates an empty coastline result.
	 *
	 * @param status	human-readable status.
	 * @return	empty coastline result.
	 */
	public static Coastlines empty( String status )
	{
		return new Coastlines( Collections.emptyList() , status );
	}


	/**
	 * Loads the project Natural Earth 1:110m coastline file if available.
	 *
	 * @param radius	initial display radius used for all coastline points.
	 * @return	loaded coastlines, or an empty result with a status message.
	 */
	public static Coastlines loadNaturalEarth110m( float radius )
	{
		return load( NATURAL_EARTH_110M_CANDIDATE_PATHS , radius );
	}


	/**
	 * Loads the first existing coastline GeoJSON from a candidate path list.
	 *
	 * @param candidatePaths	relative paths to check.
	 * @param radius		initial display radius used for all coastline points.
	 * @return	loaded coastlines, or an empty result with a status message.
	 */
	public static Coastlines load( String[] candidatePaths , float radius )
	{
		String path = locateExistingPath( candidatePaths );
		if( path == null ) {
			return empty( "coastlines: file not found" );
		}

		try {
			String geojson = new String( Files.readAllBytes( Paths.get( path ) ) , StandardCharsets.UTF_8 );
			List<Segment> segments = parseCoastlineGeojson( geojson );
			Coastlines coastlines = new Coastlines( segments ,
					String.format( "coastlines: %d segments from %s" , segments.size() , path ) );
			coastlines.updateGeometry( new ConstantRadiusProvider( radius ) );
			return coastlines;
		} catch( IOException e ) {
			return empty( "coastlines: could not load " + path + " (" + e.getMessage() + ")" );
		}
	}


	/**
	 * Returns the loaded coastline line strips.
	 *
	 * @return	unmodifiable list of coastline segments.
	 */
	public List<Segment> segments()
	{
		return this.segments;
	}


	/**
	 * Returns the loading status.
	 *
	 * @return	human-readable status.
	 */
	public String status()
	{
		return this.status;
	}


	/**
	 * Updates coastline display coordinates with a radius supplied for each lon/lat vertex.
	 *
	 * @param radiusProvider	provides display radius for each coastline point.
	 */
	public void updateGeometry( RadiusProvider radiusProvider )
	{
		for( Segment segment : this.segments ) {
			segment.updateGeometry( radiusProvider );
		}
	}


	/**
	 * Draws the coastline line strips into a Processing sketch.
	 *
	 * @param app	Processing sketch receiving the draw calls.
	 */
	public void draw( PApplet app )
	{
		if( this.segments.isEmpty() ) {
			return;
		}
		app.noFill();
		app.stroke( 245 , 235 );
		app.strokeWeight( 1.2f );
		for( Segment segment : this.segments ) {
			float[] x = segment.x();
			float[] y = segment.y();
			float[] z = segment.z();
			app.beginShape();
			for( int i=0; i<x.length; i++ ) {
				app.vertex( x[i] , y[i] , z[i] );
			}
			app.endShape();
		}
	}


	/**
	 * Returns the first existing candidate path, or {@code null} if none exist.
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
	 * Parses the LineString coordinate arrays in a coastline GeoJSON.
	 */
	private static List<Segment> parseCoastlineGeojson( String geojson )
		throws IOException
	{
		List<Segment> segments = new ArrayList<>();
		int searchFrom = 0;
		while( true ) {
			int key = geojson.indexOf( "\"coordinates\"" , searchFrom );
			if( key < 0 ) {
				break;
			}
			int colon = geojson.indexOf( ':' , key );
			int arrayStart = skipWhitespace( geojson , colon + 1 );
			if( arrayStart >= geojson.length() || geojson.charAt( arrayStart ) != '[' ) {
				throw new IOException( "Malformed coordinates array" );
			}
			int arrayEnd = matchingBracket( geojson , arrayStart );
			Segment segment = parseLineStringCoordinates( geojson , arrayStart , arrayEnd );
			if( segment != null ) {
				segments.add( segment );
			}
			searchFrom = arrayEnd + 1;
		}
		return segments;
	}


	/**
	 * Parses one GeoJSON LineString coordinates array.
	 */
	private static Segment parseLineStringCoordinates( String text , int start , int end )
	{
		List<Double> longitude = new ArrayList<>();
		List<Double> latitude = new ArrayList<>();

		int index = start + 1;
		while( index < end ) {
			if( text.charAt( index ) == '[' ) {
				int longitudeStart = skipWhitespace( text , index + 1 );
				if( longitudeStart < end && isNumberStart( text.charAt( longitudeStart ) ) ) {
					int longitudeEnd = numberEnd( text , longitudeStart );
					int comma = skipWhitespace( text , longitudeEnd );
					int latitudeStart = skipWhitespace( text , comma + 1 );
					int latitudeEnd = numberEnd( text , latitudeStart );
					longitude.add( Double.parseDouble( text.substring( longitudeStart , longitudeEnd ) ) );
					latitude.add( Double.parseDouble( text.substring( latitudeStart , latitudeEnd ) ) );
					index = latitudeEnd;
				}
			}
			index++;
		}

		if( longitude.size() < 2 ) {
			return null;
		}
		return new Segment( toDoubleArray( longitude ) , toDoubleArray( latitude ) );
	}


	/**
	 * Returns the matching closing bracket for an array.
	 */
	private static int matchingBracket( String text , int openingBracket )
		throws IOException
	{
		int depth = 0;
		for( int i=openingBracket; i<text.length(); i++ ) {
			char c = text.charAt( i );
			if( c == '[' ) {
				depth++;
			} else if( c == ']' ) {
				depth--;
				if( depth == 0 ) {
					return i;
				}
			}
		}
		throw new IOException( "Unclosed coordinates array" );
	}


	/**
	 * Skips JSON whitespace.
	 */
	private static int skipWhitespace( String text , int index )
	{
		while( index < text.length() && Character.isWhitespace( text.charAt( index ) ) ) {
			index++;
		}
		return index;
	}


	/**
	 * Returns whether a character can begin a JSON number.
	 */
	private static boolean isNumberStart( char c )
	{
		return c == '-' || c == '+' || c == '.' || Character.isDigit( c );
	}


	/**
	 * Returns the index immediately after a JSON number.
	 */
	private static int numberEnd( String text , int index )
	{
		while( index < text.length() ) {
			char c = text.charAt( index );
			if( c != '-' && c != '+' && c != '.' && c != 'e' && c != 'E' && !Character.isDigit( c ) ) {
				break;
			}
			index++;
		}
		return index;
	}


	/**
	 * Converts a boxed double list to a primitive array.
	 */
	private static double[] toDoubleArray( List<Double> values )
	{
		double[] array = new double[ values.size() ];
		for( int i=0; i<array.length; i++ ) {
			array[i] = values.get( i );
		}
		return array;
	}


	/**
	 * Provides the display radius for a coastline point.
	 */
	public interface RadiusProvider
	{
		float radiusAt( double latitudeDegrees , double longitudeDegrees );
	}


	/**
	 * Radius provider used before a mesh-specific provider is available.
	 */
	private static final class ConstantRadiusProvider
		implements RadiusProvider
	{
		private final float radius;


		private ConstantRadiusProvider( float radius )
		{
			this.radius = radius;
		}


		public float radiusAt( double latitudeDegrees , double longitudeDegrees )
		{
			return this.radius;
		}
	}


	/**
	 * One coastline line strip in display-space globe coordinates.
	 */
	public static final class Segment
	{
		private final double[] longitude;
		private final double[] latitude;
		private final float[] x;
		private final float[] y;
		private final float[] z;


		private Segment( double[] longitude , double[] latitude )
		{
			this.longitude = longitude;
			this.latitude = latitude;
			this.x = new float[ longitude.length ];
			this.y = new float[ longitude.length ];
			this.z = new float[ longitude.length ];
		}


		private void updateGeometry( RadiusProvider radiusProvider )
		{
			for( int i=0; i<this.longitude.length; i++ ) {
				double latitudeRad = Math.toRadians( this.latitude[i] );
				double longitudeRad = Math.toRadians( this.longitude[i] );
				double cosLatitude = Math.cos( latitudeRad );
				double geoX = cosLatitude * Math.cos( longitudeRad );
				double geoY = cosLatitude * Math.sin( longitudeRad );
				double geoZ = Math.sin( latitudeRad );
				float radius = radiusProvider.radiusAt( this.latitude[i] , this.longitude[i] );
				this.x[i] = (float) ( radius * geoY );
				this.y[i] = (float) ( -radius * geoZ );
				this.z[i] = (float) ( radius * geoX );
			}
		}


		public float[] x()
		{
			return this.x;
		}


		public float[] y()
		{
			return this.y;
		}


		public float[] z()
		{
			return this.z;
		}
	}
}
