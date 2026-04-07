<cfscript>
/**
 * Target file for stepping tests (stepIn, stepOver, stepOut).
 *
 * Call hierarchy: main -> outerFunc -> innerFunc
 */

function innerFunc( required numeric x ) {
	var doubled = arguments.x * 2;
	return doubled;
}

function outerFunc( required numeric value ) {
	var intermediate = arguments.value + 10;
	var result = innerFunc( intermediate );
	return result;
}

// Main execution
startValue = 5;
finalResult = outerFunc( startValue );

writeOutput( "Result: #finalResult#" );

// Second call for additional stepping tests
secondResult = outerFunc( 100 );

writeOutput( " Second: #secondResult#" );
</cfscript>
