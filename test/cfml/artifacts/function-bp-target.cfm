<cfscript>
/**
 * Target file for function breakpoint tests.
 *
 * Tests breaking on function names without specifying file/line.
 *
 * Function breakpoint targets:
 *   - targetFunction (exact match)
 *   - onRequest* (wildcard)
 */

function targetFunction( required string input ) {
	var result = "processed: " & arguments.input;
	return result;
}

function onRequestStart() {
	// Simulates Application.cfc callback
	return true;
}

function onRequestEnd() {
	// Simulates Application.cfc callback
	return true;
}

function anotherFunction() {
	return "another";
}

// Execution
onRequestStart();

result1 = targetFunction( "test-value" );
result2 = anotherFunction();

onRequestEnd();

writeOutput( "Results: #result1# / #result2#" );
</cfscript>
