<cfscript>
/**
 * Target file for evaluate/expression tests.
 *
 * Tests evaluate functionality in watch/repl context.
 */

function testEvaluate( required struct data ) {
	var localVar = "local-value";
	var localNum = 100;
	var localArray = [ 10, 20, 30 ];
	var localStruct = {
		"key1": "value1",
		"key2": 42,
		"nested": {
			"inner": "deep"
		}
	};

	// Access argument
	var dataName = arguments.data.name;
	var dataValue = arguments.data.value;

	var stopHere = true;

	return localVar & " - " & dataName;
}

inputData = {
	"name": "test-input",
	"value": 12345
};

result = testEvaluate( inputData );

writeOutput( "Result: #result#" );
</cfscript>
