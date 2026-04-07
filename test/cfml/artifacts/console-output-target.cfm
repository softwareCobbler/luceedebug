<cfscript>
/**
 * Target file for console output tests.
 *
 * Tests that systemOutput() streams to debug console via DAP output events.
 */

function testOutput( required string message ) {
	var prefix = "ConsoleOutputTest";

	// Output to stdout
	systemOutput( "#prefix#: #arguments.message#", true );

	var stopHere = true;

	return arguments.message;
}

result = testOutput( url.message ?: "default-message" );

writeOutput( "Done: #result#" );
</cfscript>
