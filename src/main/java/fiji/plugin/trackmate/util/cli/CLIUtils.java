/*-
 * #%L
 * TrackMate: your buddy for everyday tracking.
 * %%
 * Copyright (C) 2010 - 2024 TrackMate developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package fiji.plugin.trackmate.util.cli;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.input.TailerListenerAdapter;
import org.scijava.prefs.PrefService;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.util.TMUtils;
import ij.IJ;

public class CLIUtils
{

	public static final String CONDA_PATH_PREF_KEY = "trackmate.conda.path";

	public static final String CONDA_ROOT_PREFIX_KEY = "trackmate.conda.root.prefix";

	private static Map< String, String > envMap;

	public static Map< String, String > getEnvMap() throws IOException
	{
		if ( envMap == null )
		{
			synchronized ( CLIUtils.class )
			{
				if ( envMap == null )
				{

					// Prepare the command and environment variables.
					// Command
					final ProcessBuilder pb;
					if ( IJ.isWindows() )
						pb = new ProcessBuilder( Arrays.asList( "cmd.exe", "/c", "conda", "env", "list" ) );
					else
						pb = new ProcessBuilder( Arrays.asList( getCondaPath(), "env", "list" ) );
					// Env variables.
					final Map< String, String > env = new HashMap<>();
					final String condaRootPrefix = getCondaRootPrefix();
					env.put( "MAMBA_ROOT_PREFIX", condaRootPrefix );
					env.put( "CONDA_ROOT_PREFIX", condaRootPrefix );
					pb.environment().putAll( env );
					// Run and collect output.
					final Process process = pb.start();
					final BufferedReader stdOutput = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
					final BufferedReader stdError = new BufferedReader( new InputStreamReader( process.getErrorStream() ) );

					/*
					 * Did we have an error? Read the error from the command
					 */
					String s;
					String errorOutput = "";
					while ( ( s = stdError.readLine() ) != null )
						errorOutput += ( s + '\n' );
					if ( !errorOutput.isEmpty() )
						throw new IOException( "Could not retrieve environment map properly:\n" + errorOutput );

					String line;
					envMap = new HashMap<>();
					while ( ( line = stdOutput.readLine() ) != null )
					{
						line = line.trim();
						line = line.replaceAll( "\\*", "" );
						if ( line.isEmpty() || line.startsWith( "#" ) || line.startsWith( "Name" ) || line.startsWith( "──────" ) )
							continue;

						final String[] parts = line.split( "\\s+" );
						if ( parts.length >= 2 )
						{
							final String envName = parts[ 0 ];
							final String envPath = parts[ 1 ] + "/bin/python";
							envMap.put( envName, envPath );
						}
						else if ( parts.length == 1 )
						{
							/*
							 * When we don't have the right configuration,
							 * sometimes the list returns the path to the envs
							 * but not the name. We try then to extract the name
							 * from the path.
							 */

							final String envRoot = parts[ 0 ];
							if ( !isValidPath( envRoot ) )
								continue;
							final Path path = Paths.get( envRoot );
							final String envName = path.getFileName().toString();
							final String envPath = envRoot + "/bin/python";
							envMap.put( envName, envPath );
						}
					}
				}
			}
		}
		return envMap;
	}

	public static List< String > getEnvList() throws IOException
	{
		final List< String > l = new ArrayList<>( getEnvMap().keySet() );
		l.sort( null );
		return l;
	}

	public static String getCondaPath()
	{
		if ( IJ.isWindows() )
			return "conda";

		final PrefService prefs = TMUtils.getContext().getService( PrefService.class );
		String findPath;
		try
		{
			findPath = CLIUtils.findDefaultCondaPath();
		}
		catch ( final IllegalArgumentException e )
		{
			findPath = "/usr/local/opt/micromamba/bin/micromamba";
		}
		return prefs.get( CLIUtils.class, CLIUtils.CONDA_PATH_PREF_KEY, findPath );
	}

	public static String getCondaRootPrefix()
	{
		final PrefService prefs = TMUtils.getContext().getService( PrefService.class );
		final String findPath = "/usr/local/opt/micromamba";
		return prefs.get( CLIUtils.class, CLIUtils.CONDA_ROOT_PREFIX_KEY, findPath );
	}

	public static String findDefaultCondaPath() throws IllegalArgumentException
	{
		final String username = System.getProperty( "user.name" );
		final String prefix = IJ.isMacOSX()
				? "/Users/"
				: "/home/";
		final String anaconda1 = prefix + username + "/anaconda3/bin/conda";
		final String anaconda2 = "/opt/anaconda3/bin/conda";
		final String miniconda1 = prefix + username + "/miniconda3/bin/conda";
		final String miniconda2 = "/opt/miniconda3/bin/conda";
		final String mamba1 = prefix + username + "/mamba/bin/mamba";
		final String mamba2 = "/opt/mamba/bin/mamba";
		final String micromamba1 = prefix + username + ( IJ.isMacOSX()
				? "/Library/micromamba/bin/micromamba"
				: "/.local/share/micromamba/bin/micromamba" );
		final String micromamba2 = "/usr/local/micromamba/bin/micromamba";
		final String micromamba3 = "/usr/local/opt/micromamba/bin/micromamba";
		final String micromamba4 = "/opt/micromamba/bin/micromamba";
		final String[] toTest = new String[] {
				anaconda1,
				anaconda2,
				miniconda1,
				miniconda2,
				mamba1,
				mamba2,
				micromamba1,
				micromamba2,
				micromamba3,
				micromamba4
		};
		for ( final String str : toTest )
		{
			final Path path = Paths.get( str );
			if ( Files.isExecutable( path ) )
				return str;
		}
		throw new IllegalArgumentException( "Could not find a conda executable I know of, within: " + Arrays.asList( toTest ) );
	}

	/**
	 * Add a hook to delete the content of given path when Fiji quits. Taken
	 * from https://stackoverflow.com/a/20280989/201698
	 *
	 * @param path
	 */
	public static void recursiveDeleteOnShutdownHook( final Path path )
	{
		Runtime.getRuntime().addShutdownHook( new Thread( new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					Files.walkFileTree( path, new SimpleFileVisitor< Path >()
					{
						@Override
						public FileVisitResult visitFile( final Path file, final BasicFileAttributes attrs ) throws IOException
						{
							Files.delete( file );
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult postVisitDirectory( final Path dir, final IOException e ) throws IOException
						{
							if ( e == null )
							{
								Files.delete( dir );
								return FileVisitResult.CONTINUE;
							}
							throw e;
						}
					} );
				}
				catch ( final IOException e )
				{
					throw new RuntimeException( "Failed to delete " + path, e );
				}
			}
		} ) );
	}

	public static class LoggerTailerListener extends TailerListenerAdapter
	{
		private final Logger logger;

		public Color COLOR = Logger.BLUE_COLOR.darker();

		private final static Pattern PERCENTAGE_PATTERN = Pattern.compile( ".+\\D(\\d+(?:\\.\\d+)?)%.+" );

		private final static Pattern INFO_PATTERN = Pattern.compile( "(.+\\[INFO\\]\\s+(.+)|^INFO:.*$)" );

		public LoggerTailerListener( final Logger logger )
		{
			this.logger = logger;
		}

		@Override
		public void handle( final String line )
		{
			// Do we have percentage?
			final Matcher matcher = PERCENTAGE_PATTERN.matcher( line );
			if ( matcher.matches() )
			{
				final String percent = matcher.group( 1 );
				logger.setProgress( Double.valueOf( percent ) / 100. );
			}
			else
			{
				final Matcher matcher2 = INFO_PATTERN.matcher( line );
				if ( matcher2.matches() )
				{
					final String str = matcher2.group( 1 ).trim();
					if ( str.length() > 2 )
						logger.setStatus( str
								.replaceAll( "\\[INFO\\]", "" )
								.replaceAll( "INFO:", "" )
								.replaceAll( "INFO", "" ) );
				}
				else if ( !line.trim().isEmpty() )
				{
					logger.log( " - " + line + '\n', COLOR );
				}
			}
		}
	}

	public static boolean isValidPath( final String pathString )
	{
		try
		{
			// Convert the string to a Path object
			final Path path = Paths.get( pathString );

			// Check for basic path characteristics
			if ( !pathString.contains( "/" ) && !pathString.contains( "\\" ) )
				return false;

			// Check for illegal characters (Windows-specific example)
			if ( System.getProperty( "os.name" ).toLowerCase().contains( "win" ) )
			{
				final Pattern illegalCharsPattern = Pattern.compile( "[<>:*?\"|]" );
				if ( illegalCharsPattern.matcher( pathString ).find() )
					return false;
			}

			return Files.exists( path );
		}
		catch ( final InvalidPathException e )
		{
			return false;
		}
	}

	public static void main( final String[] args ) throws Exception
	{
		System.out.println( "Conda path: " + findDefaultCondaPath() );
		System.out.println( "Known environments: " + getEnvList() );
		System.out.println( "Paths:" );
		getEnvMap().forEach( ( k, v ) -> System.out.println( k + " -> " + v ) );
	}
}
