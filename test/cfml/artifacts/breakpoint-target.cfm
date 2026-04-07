<cfscript>
/**
 * Target file for breakpoint tests.
 */

function simpleFunction( required string name ) {
	var greeting = "Hello, " & arguments.name;
	return greeting;
}

function conditionalFunction( required numeric value ) {
	var result = 0;
	if ( arguments.value > 10 ) {
		result = arguments.value * 2;
	} else {
		result = arguments.value + 5;
	}
	return result;
}

output1 = simpleFunction( "Test" );
output2 = conditionalFunction( 15 );

writeOutput( "Done: #output1# / #output2#" );
</cfscript>
