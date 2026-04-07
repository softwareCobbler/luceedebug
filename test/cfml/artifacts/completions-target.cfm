<cfscript>
/**
 * Target file for completions/autocomplete tests.
 *
 * Tests debug console autocomplete functionality.
 */

function testCompletions() {
	var myString = "hello";
	var myNumber = 42;
	var myStruct = {
		"firstName": "John",
		"lastName": "Doe",
		"address": {
			"street": "123 Main St",
			"city": "Springfield"
		}
	};
	var myArray = [ 1, 2, 3 ];
	var myQuery = queryNew( "id,name", "integer,varchar", [ { id: 1, name: "test" } ] );

	var stopHere = true;

	return myString;
}

result = testCompletions();

writeOutput( "Done: #result#" );
</cfscript>
