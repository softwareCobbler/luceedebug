<cfscript>
/**
 * Target file for variables and scopes tests.
 *
 * Tests various data types and scope access.
 */

// Set up test data in various scopes
url.urlVar = "from-url";
form.formVar = "from-form";
request.requestVar = "from-request";

function testVariables( required string arg1, required numeric arg2 ) {
	// Local variables of different types
	var localString = "hello";
	var localNumber = 42;
	var localFloat = 3.14159;
	var localBoolean = true;
	var localArray = [ 1, 2, 3, "four", { nested: true } ];
	var localStruct = {
		"name": "test",
		"value": 123,
		"nested": {
			"deep": "value"
		}
	};
	var localDate = now();
	var localNull = javacast( "null", 0 );

	// Access various scopes
	var fromUrl = url.urlVar;
	var fromForm = form.formVar;
	var fromRequest = request.requestVar;

	var debugLine = "inspect here";

	return localString & " " & localNumber;
}

result = testVariables( "arg-value", 999 );

writeOutput( "Done: #result#" );
</cfscript>
