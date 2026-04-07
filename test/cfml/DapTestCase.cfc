/**
 * Base test case for DAP (Debug Adapter Protocol) tests.
 *
 * Provides connection management, feature detection, and helper methods.
 *
 * Configuration via environment variables:
 *   DAP_HOST - debugger host (default: localhost)
 *   DAP_PORT - debugger port (default: 10000)
 *   DEBUGGEE_HTTP - HTTP URL of debuggee (default: http://localhost:8888)
 *   DEBUGGEE_ARTIFACT_PATH - filesystem path to artifacts on debuggee (default: same as test runner)
 *   DAP_DEBUG - enable debug logging (default: false)
 */
component extends="org.lucee.cfml.test.LuceeTestCase" {

	variables.dap = javacast( "null", 0 );
	variables.capabilities = {};
	variables.dapHost = "";
	variables.dapPort = 0;
	variables.debuggeeHttp = "";
	variables.debuggeeArtifactPath = "";
	variables.httpThread = "";
	variables.httpResult = {};

	function beforeAll() {
		// Load config from environment
		variables.dapHost = server.system.environment.DAP_HOST ?: "localhost";
		variables.dapPort = val( server.system.environment.DAP_PORT ?: 10000 );
		variables.debuggeeHttp = server.system.environment.DEBUGGEE_HTTP ?: "http://localhost:8888";
		variables.debuggeeArtifactPath = server.system.environment.DEBUGGEE_ARTIFACT_PATH ?: "";
		var debug = ( server.system.environment.DAP_DEBUG ?: "false" ) == "true";

		systemOutput( "DapTestCase: Connecting to #variables.dapHost#:#variables.dapPort#", true );
		systemOutput( "DapTestCase: Debuggee HTTP at #variables.debuggeeHttp#", true );
		if ( len( variables.debuggeeArtifactPath ) ) {
			systemOutput( "DapTestCase: Debuggee artifact path: #variables.debuggeeArtifactPath#", true );
		}

		// Connect to DAP server
		variables.dap = new DapClient( debug = debug );
		variables.dap.connect( variables.dapHost, variables.dapPort );

		// Initialize and store capabilities
		var initResponse = variables.dap.initialize();
		variables.capabilities = initResponse.body ?: {};

		systemOutput( "DapTestCase: Capabilities: #serializeJSON( variables.capabilities )#", true );

		// Send configurationDone
		variables.dap.configurationDone();
	}

	function afterAll() {
		if ( !isNull( variables.dap ) && variables.dap.isConnected() ) {
			try {
				variables.dap.dapDisconnect();
			} catch ( any e ) {
				// Ignore disconnect errors
			}
			variables.dap.disconnect();
		}
	}

	// ========== Capability Detection ==========

	public struct function getCapabilities() {
		return variables.capabilities;
	}

	public boolean function supportsConditionalBreakpoints() {
		return variables.capabilities.supportsConditionalBreakpoints ?: false;
	}

	public boolean function supportsSetVariable() {
		return variables.capabilities.supportsSetVariable ?: false;
	}

	public boolean function supportsCompletions() {
		return variables.capabilities.supportsCompletionsRequest ?: false;
	}

	public boolean function supportsFunctionBreakpoints() {
		return variables.capabilities.supportsFunctionBreakpoints ?: false;
	}

	public boolean function supportsBreakpointLocations() {
		return variables.capabilities.supportsBreakpointLocationsRequest ?: false;
	}

	public boolean function supportsExceptionInfo() {
		return variables.capabilities.supportsExceptionInfoRequest ?: false;
	}

	public boolean function supportsEvaluate() {
		return variables.capabilities.supportsEvaluateForHovers ?: false;
	}

	// ========== Test Helpers ==========

	/**
	 * Get the server path for a test artifact.
	 * Uses DEBUGGEE_ARTIFACT_PATH if set, otherwise assumes same location as test runner.
	 */
	public string function getArtifactPath( required string filename ) {
		if ( len( variables.debuggeeArtifactPath ) ) {
			return variables.debuggeeArtifactPath & arguments.filename;
		}
		var testDir = getDirectoryFromPath( getCurrentTemplatePath() );
		return testDir & "artifacts/" & arguments.filename;
	}

	/**
	 * Get the HTTP URL for a test artifact.
	 */
	public string function getArtifactUrl( required string filename ) {
		return variables.debuggeeHttp & "/test/cfml/artifacts/" & arguments.filename;
	}

	/**
	 * Trigger an HTTP request to an artifact in a background thread.
	 * Use waitForHttpComplete() to wait for completion.
	 */
	public void function triggerArtifact( required string filename, struct params = {} ) {
		var url = getArtifactUrl( arguments.filename );
		var queryString = "";

		for ( var key in arguments.params ) {
			queryString &= ( len( queryString ) ? "&" : "?" ) & encodeForURL( key ) & "=" & encodeForURL( arguments.params[ key ] );
		}

		url &= queryString;
		variables.httpResult = {};
		variables.httpThread = "httpTrigger_" & createUUID();

		thread name="#variables.httpThread#" url=url httpResult=variables.httpResult {
			try {
				http url="#url#" result="local.r" timeout=60;
				httpResult.status = local.r.statusCode;
				httpResult.content = local.r.fileContent;
			} catch ( any e ) {
				httpResult.error = e.message;
			}
		}
	}

	/**
	 * Wait for the background HTTP request to complete.
	 */
	public struct function waitForHttpComplete( numeric timeout = 30000 ) {
		if ( len( variables.httpThread ) ) {
			threadJoin( variables.httpThread, arguments.timeout );
			variables.httpThread = "";
		}
		return variables.httpResult;
	}

	/**
	 * Get the top stack frame from a stopped thread.
	 */
	public struct function getTopFrame( required numeric threadId ) {
		var stackResponse = variables.dap.stackTrace( arguments.threadId );
		var frames = stackResponse.body.stackFrames ?: [];
		if ( frames.len() == 0 ) {
			throw( type="DapTestCase.Error", message="No stack frames available" );
		}
		return frames[ 1 ];
	}

	/**
	 * Get a scope by name from a frame.
	 */
	public struct function getScopeByName( required numeric frameId, required string scopeName ) {
		var scopesResponse = variables.dap.scopes( arguments.frameId );
		var scopes = scopesResponse.body.scopes ?: [];
		for ( var scope in scopes ) {
			if ( scope.name == arguments.scopeName ) {
				return scope;
			}
		}
		throw( type="DapTestCase.Error", message="Scope not found: #arguments.scopeName#" );
	}

	/**
	 * Get a variable by name from a variables reference.
	 */
	public struct function getVariableByName( required numeric variablesReference, required string name ) {
		var varsResponse = variables.dap.getVariables( arguments.variablesReference );
		var vars = varsResponse.body.variables ?: [];
		for ( var v in vars ) {
			if ( v.name == arguments.name ) {
				return v;
			}
		}
		throw( type="DapTestCase.Error", message="Variable not found: #arguments.name#" );
	}

	/**
	 * Clean up after a test - ensure thread is continued.
	 */
	public void function cleanupThread( required numeric threadId ) {
		try {
			variables.dap.continueThread( arguments.threadId );
		} catch ( any e ) {
			// Ignore - thread may already be running
		}
		waitForHttpComplete();
	}

	/**
	 * Clear all breakpoints for a file.
	 */
	public void function clearBreakpoints( required string path ) {
		variables.dap.setBreakpoints( arguments.path, [] );
	}

	/**
	 * Clear all function breakpoints.
	 */
	public void function clearFunctionBreakpoints() {
		variables.dap.setFunctionBreakpoints( [] );
	}

}
