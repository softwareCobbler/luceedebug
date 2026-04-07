<cfscript>
/**
 * Target file for setVariable tests.
 *
 * Tests modifying variables at runtime.
 */

function testSetVariable( required string input ) {
	var modifiable = "original";
	var numericVar = 100;
	var structVar = { "key": "original-value" };

	// First breakpoint - modify variables here
	var checkpoint1 = true;

	// Use the (potentially modified) values
	var result = modifiable & " - " & numericVar;

	// Second checkpoint after potential modification
	var checkpoint2 = true;

	return result;
}

output = testSetVariable( "test-input" );

writeOutput( "Output: #output#" );
</cfscript>
