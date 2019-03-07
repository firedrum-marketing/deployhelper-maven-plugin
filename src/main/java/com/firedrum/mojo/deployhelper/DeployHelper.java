package com.firedrum.mojo.schema;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.json.JSONException;
import org.json.JSONObject;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;


/**
 * Sends requests to an HTTP endpoint.
 */
@Mojo( name = "execute", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true )
public class DeployHelper extends AbstractMojo {
	private static final int HTTP_MOVED_TEMP = 307;

	private static final String GZIP_CONTENT_ENCODING = "gzip";

	/**
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "string" )
	private String type;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private String failKey;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private String failNotEquals;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private String failPrefix;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private Map<String, String> headers;

	/**
	 * URL.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "url", required = true )
	private String url;

	/**
	 * Username. If not given, it will be looked up through <code>settings.xml</code>'s server with
	 * <code>${settingsKey}</code> as key.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "username" )
	private String username;

	/**
	 * Password. If not given, it will be looked up through <code>settings.xml</code>'s server with
	 * <code>${settingsKey}</code> as key.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "password" )
	private String password;

	/**
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "${settings}", readonly = true, required = true )
	private Settings settings;

	/**
	 * Server's <code>id</code> in <code>settings.xml</code> to look up username and password. Defaults to
	 * <code>${url}</code> if not given.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "settingsKey" )
	private String settingsKey;

	/**
	 * MNG-4384
	 * 
	 * @since 1.0.0
	 */
	@Component( role = org.sonatype.plexus.components.sec.dispatcher.SecDispatcher.class, hint = "default" )
	private SecDispatcher securityDispatcher;

	/**
	 * The Maven Project Object
	 */
	@Parameter( defaultValue = "${project}", readonly = true, required = true )
	private MavenProject project;

	/**
	 */
	@Parameter( defaultValue = "${session}", readonly = true, required = true )
	private MavenSession mavenSession;

	/**
	 * When <code>true</code>, skip the execution.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false" )
	private boolean skip;

	private URL source;
	private URLConnection connection;

	/**
	 * <p>
	 * Determine if the mojo execution should get skipped.
	 * </p>
	 * This is the case if:
	 * <ul>
	 * <li>{@link #skip} is <code>true</code></li>
	 * <li>if the mojo gets executed on a project with packaging type 'pom' and {@link #forceMojoExecution} is
	 * <code>false</code></li>
	 * </ul>
	 * 
	 * @return <code>true</code> if the mojo execution should be skipped.
	 */
	private boolean skipMojo() {
		if ( skip ) {
			getLog().info( "User has requested to skip execution." );
			return true;
		}

		return false;
	}

	/**
	 * Load the sql file and then execute it
	 * 
	 * @throws MojoExecutionException
	 */
	public void execute() throws MojoExecutionException {
		if ( skipMojo() ) {
			return;
		}

		loadUserInfoFromSettings();

		BufferedReader in = null;
		try {
			source = new URL( url );
			connection = openConnection( source );

			in = new BufferedReader( new InputStreamReader( GZIP_CONTENT_ENCODING.equals( connection.getContentEncoding() ) ? new GZIPInputStream( connection.getInputStream() ) : connection.getInputStream() ) );

			String response = "";
			String inputLine;

			while ( ( inputLine = in.readLine() ) != null ) {
				response += inputLine;
			}

			String value;
			switch ( type ) {
				case "json":
					if ( failKey != null ) {
						try {
							value = new JSONObject( response ).optString( failKey );
						} catch ( JSONException e ) {
							throw new MojoExecutionException( "Deploy Helper [FAILED]: Invalid JSON Response:\n" + response, e );
						}
					} else {
						value = response;
					}
					break;
				case "string":
				default:
					value = response;
					break;
			}

			if ( failPrefix != null && value.startsWith( failPrefix ) ) {
				throw new MojoExecutionException( "Deploy Helper [FAILED]: " + value );
			}

			if ( failNotEquals != null && !failNotEquals.equals( value ) ) {
				throw new MojoExecutionException( "Deploy Helper [FAILED]: " + value );
			}

			getLog().info( "Deploy Helper [PASSED]: " + value );
		} catch ( IOException | SecurityException e ) {
			throw new MojoExecutionException( "Deploy Helper [FAILED]: " + e.getMessage(), e );
		} finally {
			if ( in != null ) {
				try {
					in.close();
				} catch ( Exception e ) {
					// ignore
				}
			}
		}
	}

	/**
	 * Load username password from settings if user has not set them in JVM properties
	 * 
	 * @throws MojoExecutionException
	 */
	private void loadUserInfoFromSettings() throws MojoExecutionException {
		if ( this.settingsKey == null ) {
			this.settingsKey = getUrl();
		}

		if ( ( getUsername() == null || getPassword() == null ) && ( settings != null ) ) {
			Server server = this.settings.getServer( this.settingsKey );

			if ( server != null ) {
				if ( getUsername() == null ) {
					setUsername( server.getUsername() );
				}

				if ( getPassword() == null && server.getPassword() != null ) {
					try {
						setPassword( securityDispatcher.decrypt( server.getPassword() ) );
					} catch ( SecDispatcherException e ) {
						throw new MojoExecutionException( e.getMessage() );
					}
				}
			}
		}

		if ( getUsername() == null ) {
			// allow empty username
			setUsername( "" );
		}

		if ( getPassword() == null ) {
			// allow empty password
			setPassword( "" );
		}
	}

	private boolean isMoved(final int responseCode) {
		return responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == HTTP_MOVED_TEMP;
	}

	private URLConnection openConnection(final URL aSource) throws IOException, MojoExecutionException {
		// set up the URL connection
		final URLConnection connection = aSource.openConnection();
		// modify the headers

		// set credentials
		connection.setRequestProperty( "Authorization", "Basic " + Base64.getEncoder().encodeToString( ( username + ":" + password ).getBytes() ) );
		connection.setRequestProperty( "Accept-Encoding", GZIP_CONTENT_ENCODING );

		if ( headers != null ) {
			for ( final Map.Entry<String, String> header : headers.entrySet() ) {
				//we do not log the header value as it may contain sensitive data like passwords
				getLog().debug( String.format( "Adding header '%s'", header.getKey() ) );
				connection.setRequestProperty( header.getKey(), header.getValue() );
			}
		}

		if ( connection instanceof HttpURLConnection ) {
			( (HttpURLConnection) connection ).setInstanceFollowRedirects( true );
			connection.setUseCaches( false );
		}

		// connect to the remote site (may take some time)
		try {
			connection.connect();
		} catch (final NullPointerException e) {
			//bad URLs can trigger NPEs in some JVMs
			throw new MojoExecutionException( "Failed to parse " + source.toString(), e );
		}

		// First check on a 301 / 302 (moved) response (HTTP only)
		if ( connection instanceof HttpURLConnection ) {
			final HttpURLConnection httpConnection = (HttpURLConnection) connection;
			final int responseCode = httpConnection.getResponseCode();
			if ( isMoved( responseCode ) ) {
				final String newLocation = httpConnection.getHeaderField( "Location" );
				getLog().info( aSource + ( responseCode == HttpURLConnection.HTTP_MOVED_PERM ? " permanently" : "" ) + " moved to " + newLocation );
				final URL newURL = new URL( aSource, newLocation );
				return openConnection( newURL );
			}
			// test for 401 result (HTTP only)
			if ( responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ) {
				throw new MojoExecutionException( "HTTP Authorization failure" );
			}
		}

		//REVISIT: at this point even non HTTP connections may
		//support the if-modified-since behaviour -we just check
		//the date of the content and skip the write if it is not
		//newer. Some protocols (FTP) don't include dates, of
		//course.
		return connection;
	}
	//
	// helper accessors for unit test purposes
	//

	public String getUsername() {
		return this.username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return this.password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	void setSettings(Settings settings) {
		this.settings = settings;
	}

	void setSettingsKey(String key) {
		this.settingsKey = key;
	}

	void setSkip(boolean skip) {
		this.skip = skip;
	}

	public void setSecurityDispatcher(SecDispatcher securityDispatcher) {
		this.securityDispatcher = securityDispatcher;
	}
}
