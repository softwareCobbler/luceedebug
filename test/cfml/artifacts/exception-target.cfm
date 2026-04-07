<cfscript>
/**
 * Target file for exception breakpoint tests.
 *
 * Tests breaking on uncaught exceptions.
 */

param name="url.throwException" default="false";
param name="url.catchException" default="false";

function riskyFunction( required boolean shouldThrow ) {
	if ( arguments.shouldThrow ) {
		throw( type="TestException", message="Intentional test exception", detail="This is for testing" );
	}
	return "success";
}

function wrapperFunction( required boolean shouldThrow, required boolean shouldCatch ) {
	if ( arguments.shouldCatch ) {
		try {
			return riskyFunction( arguments.shouldThrow );
		} catch ( any e ) {
			return "caught: " & e.message;
		}
	} else {
		return riskyFunction( arguments.shouldThrow );
	}
}

result = wrapperFunction(
	shouldThrow = url.throwException == "true",
	shouldCatch = url.catchException == "true"
);

writeOutput( "Result: #result#" );
</cfscript>
