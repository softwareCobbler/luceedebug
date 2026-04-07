<cfscript>
/**
 * Includable DAP test helper functions.
 * Include this in tests that extend LuceeTestCase directly.
 */

variables.dap = javacast( "null", 0 );
variables.capabilities = {};
variables.dapHost = "";
variables.dapPort = 0;
variables.debuggeeHttp = "";
// Initialize debuggeeArtifactPath at include-time so getArtifactPath() works before setupDap()
variables.debuggeeArtifactPath = server.system.environment.DEBUGGEE_ARTIFACT_PATH ?: "";
variables.httpThread = "";
variables.httpResult = {};

function setupDap( boolean attach = true, boolean consoleOutput = false ) {
	variables.dapHost = server.system.environment.DAP_HOST ?: "localhost";
	variables.dapPort = val( server.system.environment.DAP_PORT ?: 10000 );
	variables.debuggeeHttp = server.system.environment.DEBUGGEE_HTTP ?: "http://localhost:8888";
	variables.debuggeeArtifactPath = server.system.environment.DEBUGGEE_ARTIFACT_PATH ?: "";
	variables.dapSecret = server.system.environment.DAP_SECRET ?: "testing";
	var debug = ( server.system.environment.DAP_DEBUG ?: "false" ) == "true";

	// Clean up any stale connection from a previous test that may have failed
	if ( structKeyExists( server, "_dapTestClient" ) && !isNull( server._dapTestClient ) ) {
		systemOutput( "DapTestCase: Cleaning up stale DAP connection", true );
		try {
			if ( server._dapTestClient.isConnected() ) {
				try { server._dapTestClient.dapDisconnect(); } catch ( any e ) {}
				server._dapTestClient.disconnect();
			}
		} catch ( any e ) {
			// Ignore cleanup errors
		}
		server._dapTestClient = javacast( "null", 0 );
	}

	systemOutput( "DapTestCase: Connecting to #variables.dapHost#:#variables.dapPort#", true );
	systemOutput( "DapTestCase: Debuggee HTTP at #variables.debuggeeHttp#", true );
	if ( len( variables.debuggeeArtifactPath ) ) {
		systemOutput( "DapTestCase: Debuggee artifact path: #variables.debuggeeArtifactPath#", true );
	}

	variables.dap = new DapClient( debug = debug );
	variables.dap.connect( variables.dapHost, variables.dapPort );

	// Stash in server scope for cleanup by next test if this one fails
	server._dapTestClient = variables.dap;

	var initResponse = variables.dap.initialize();
	variables.capabilities = initResponse.body ?: {};

	systemOutput( "DapTestCase: Capabilities: #serializeJSON( variables.capabilities )#", true );

	if ( arguments.attach ) {
		variables.dap.attach( variables.dapSecret, {}, arguments.consoleOutput );
		variables.dap.configurationDone();
	}
}

function teardownDap() {
	// Optional - can still be called explicitly, but setupDap handles cleanup too
	if ( !isNull( variables.dap ) && variables.dap.isConnected() ) {
		try {
			variables.dap.dapDisconnect();
		} catch ( any e ) {
		}
		variables.dap.disconnect();
	}
	if ( structKeyExists( server, "_dapTestClient" ) ) {
		server._dapTestClient = javacast( "null", 0 );
	}
}

function getCapabilities() {
	return variables.capabilities;
}

function supportsConditionalBreakpoints() {
	return variables.capabilities.supportsConditionalBreakpoints ?: false;
}
function notSupportsConditionalBreakpoints() {
	return !supportsConditionalBreakpoints();
}

function supportsSetVariable() {
	return variables.capabilities.supportsSetVariable ?: false;
}
function notSupportsSetVariable() {
	return !supportsSetVariable();
}

function supportsCompletions() {
	return variables.capabilities.supportsCompletionsRequest ?: false;
}
function notSupportsCompletions() {
	return !supportsCompletions();
}

function supportsFunctionBreakpoints() {
	return variables.capabilities.supportsFunctionBreakpoints ?: false;
}
function notSupportsFunctionBreakpoints() {
	return !supportsFunctionBreakpoints();
}

function supportsBreakpointLocations() {
	return variables.capabilities.supportsBreakpointLocationsRequest ?: false;
}
function notSupportsBreakpointLocations() {
	return !supportsBreakpointLocations();
}

function supportsExceptionInfo() {
	return variables.capabilities.supportsExceptionInfoRequest ?: false;
}
function notSupportsExceptionInfo() {
	return !supportsExceptionInfo();
}

function supportsExceptionBreakpoints() {
	var filters = variables.capabilities.exceptionBreakpointFilters ?: [];
	return arrayLen( filters ) > 0;
}
function notSupportsExceptionBreakpoints() {
	return !supportsExceptionBreakpoints();
}

function supportsEvaluate() {
	return variables.capabilities.supportsEvaluateForHovers ?: false;
}
function notSupportsEvaluate() {
	return !supportsEvaluate();
}

// Native mode (Lucee 7.1+) - has breakpointLocations and consoleOutput support
function isNativeMode() {
	return variables.capabilities.supportsBreakpointLocationsRequest ?: false;
}
function notNativeMode() {
	return !isNativeMode();
}

function getArtifactPath( required string filename ) {
	if ( len( variables.debuggeeArtifactPath ) ) {
		return variables.debuggeeArtifactPath & arguments.filename;
	}
	var testDir = getDirectoryFromPath( getCurrentTemplatePath() );
	return testDir & "artifacts/" & arguments.filename;
}

function getArtifactUrl( required string filename ) {
	return variables.debuggeeHttp & "/test/cfml/artifacts/" & arguments.filename;
}

function triggerArtifact( required string filename, struct params = {}, boolean allowErrors = false ) {
	var requestUrl = getArtifactUrl( arguments.filename );
	var queryString = "";

	for ( var key in arguments.params ) {
		queryString &= ( len( queryString ) ? "&" : "?" ) & urlEncodedFormat( key ) & "=" & urlEncodedFormat( arguments.params[ key ] );
	}

	requestUrl &= queryString;
	variables.httpResult = {};
	variables.httpThread = "httpTrigger_" & createUUID();

	thread name="#variables.httpThread#" requestUrl=requestUrl httpResult=variables.httpResult allowErrors=arguments.allowErrors {
		try {
			systemOutput( "triggerArtifact: #attributes.requestUrl#", true );
			http url="#attributes.requestUrl#" result="local.r" timeout=60 throwonerror=!attributes.allowErrors;

			httpResult.status = local.r.statusCode;
			httpResult.content = local.r.fileContent;
		} catch ( any e ) {
			systemOutput( "triggerArtifact HTTP error: #e.message#", true );
			systemOutput( e, true );
			httpResult.error = e.message;
		}
	}
}

function waitForHttpComplete( numeric timeout = 30000 ) {
	if ( len( variables.httpThread ) ) {
		threadJoin( variables.httpThread, arguments.timeout );
		variables.httpThread = "";
	}
	return variables.httpResult;
}

function getTopFrame( required numeric threadId ) {
	var stackResponse = variables.dap.stackTrace( arguments.threadId );
	systemOutput( "getTopFrame: threadId=#arguments.threadId# response=#serializeJSON( stackResponse )#", true );
	var frames = stackResponse.body.stackFrames ?: [];
	if ( frames.len() == 0 ) {
		throw( type="DapTestCase.Error", message="No stack frames available" );
	}
	return frames[ 1 ];
}

function getScopeByName( required numeric frameId, required string scopeName ) {
	var scopesResponse = variables.dap.scopes( arguments.frameId );
	var scopes = scopesResponse.body.scopes ?: [];
	for ( var scope in scopes ) {
		if ( scope.name == arguments.scopeName ) {
			return scope;
		}
	}
	throw( type="DapTestCase.Error", message="Scope not found: #arguments.scopeName#, available scopes: #serializeJSON(scopes)#" );
}

function getVariableByName( required numeric variablesReference, required string name ) {
	var varsResponse = variables.dap.getVariables( arguments.variablesReference );
	var vars = varsResponse.body.variables ?: [];
	for ( var v in vars ) {
		if ( v.name == arguments.name ) {
			return v;
		}
	}
	throw( type="DapTestCase.Error", message="Variable not found: #arguments.name#, available variables: #serializeJSON(vars)#" );
}

function cleanupThread( required numeric threadId ) {
	try {
		variables.dap.continueThread( arguments.threadId );
	} catch ( any e ) {
	}
	waitForHttpComplete();
}

function clearBreakpoints( required string path ) {
	variables.dap.setBreakpoints( arguments.path, [] );
}

function clearFunctionBreakpoints() {
	variables.dap.setFunctionBreakpoints( [] );
}
</cfscript>
