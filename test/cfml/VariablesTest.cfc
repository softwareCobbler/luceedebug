/**
 * Tests for variables and scopes inspection.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in variables-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		debugLine: 35  // var debugLine = "inspect here";
	};

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "variables-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_variablesTarget() skip="notSupportsBreakpointLocations" {
		var locations = dap.breakpointLocations( variables.targetFile, 1, 45 );
		var validLines = locations.body.breakpoints.map( function( bp ) { return bp.line; } );

		systemOutput( "#variables.targetFile# valid lines: #serializeJSON( validLines )#", true );

		for ( var key in variables.lines ) {
			var line = variables.lines[ key ];
			expect( validLines ).toInclude( line, "#variables.targetFile# line #line# (#key#) should be a valid breakpoint location" );
		}
	}

	function afterEach() {
		clearBreakpoints( variables.targetFile );
	}

	// ========== Scopes ==========

	function testScopesReturnsExpectedScopes() {
		// Set breakpoint inside testVariables
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "variables-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var scopesResponse = dap.scopes( frame.id );
		var scopes = scopesResponse.body.scopes;

		// Should have Local, Arguments, and other scopes
		var scopeNames = scopes.map( function( s ) { return s.name; } );

		expect( scopeNames ).toInclude( "Local", "Should have Local scope" );
		expect( scopeNames ).toInclude( "Arguments", "Should have Arguments scope" );

		cleanupThread( threadId );
	}

	// ========== Local Variables ==========

	function testLocalVariablesShowCorrectTypes() {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "variables-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );
		var varsResponse = dap.getVariables( localScope.variablesReference );
		var vars = varsResponse.body.variables;

		// Create a lookup map
		var varMap = {};
		for ( var v in vars ) {
			varMap[ v.name ] = v;
		}

		// Check string
		expect( varMap ).toHaveKey( "localString" );
		expect( varMap.localString.value ).toBe( '"hello"' );

		// Check number
		expect( varMap ).toHaveKey( "localNumber" );
		expect( varMap.localNumber.value ).toBe( "42" );

		// Check boolean
		expect( varMap ).toHaveKey( "localBoolean" );
		expect( varMap.localBoolean.value.lcase() ).toBe( "true" );

		// Check array has variablesReference for expansion
		expect( varMap ).toHaveKey( "localArray" );
		expect( varMap.localArray.variablesReference ).toBeGT( 0, "Array should be expandable" );

		// Check struct has variablesReference for expansion
		expect( varMap ).toHaveKey( "localStruct" );
		expect( varMap.localStruct.variablesReference ).toBeGT( 0, "Struct should be expandable" );

		cleanupThread( threadId );
	}

	// ========== Arguments ==========

	function testArgumentsScope() {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "variables-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var argsScope = getScopeByName( frame.id, "Arguments" );
		var varsResponse = dap.getVariables( argsScope.variablesReference );
		var vars = varsResponse.body.variables;

		// Create a lookup map
		var varMap = {};
		for ( var v in vars ) {
			varMap[ v.name ] = v;
		}

		// Check arg1
		expect( varMap ).toHaveKey( "arg1" );
		expect( varMap.arg1.value ).toBe( '"arg-value"' );

		// Check arg2
		expect( varMap ).toHaveKey( "arg2" );
		expect( varMap.arg2.value ).toBe( "999" );

		cleanupThread( threadId );
	}

	// ========== Nested Structures ==========

	function testExpandNestedStruct() {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "variables-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );
		var varsResponse = dap.getVariables( localScope.variablesReference );

		// Find localStruct
		var localStruct = {};
		for ( var v in varsResponse.body.variables ) {
			if ( v.name == "localStruct" ) {
				localStruct = v;
				break;
			}
		}

		expect( localStruct.variablesReference ).toBeGT( 0, "localStruct should be expandable" );

		// Expand the struct
		var nestedResponse = dap.getVariables( localStruct.variablesReference );
		var nestedVars = nestedResponse.body.variables;

		var nestedMap = {};
		for ( var v in nestedVars ) {
			nestedMap[ v.name ] = v;
		}

		expect( nestedMap ).toHaveKey( "name" );
		expect( nestedMap ).toHaveKey( "nested" );
		expect( nestedMap.nested.variablesReference ).toBeGT( 0, "nested should be expandable" );

		// Expand nested.nested
		var deepResponse = dap.getVariables( nestedMap.nested.variablesReference );
		var deepVars = deepResponse.body.variables;

		var deepMap = {};
		for ( var v in deepVars ) {
			deepMap[ v.name ] = v;
		}

		expect( deepMap ).toHaveKey( "deep" );
		expect( deepMap.deep.value ).toBe( '"value"' );

		cleanupThread( threadId );
	}

	// ========== Arrays ==========

	function testExpandArray() {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "variables-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );
		var varsResponse = dap.getVariables( localScope.variablesReference );

		// Find localArray
		var localArray = {};
		for ( var v in varsResponse.body.variables ) {
			if ( v.name == "localArray" ) {
				localArray = v;
				break;
			}
		}

		expect( localArray.variablesReference ).toBeGT( 0, "localArray should be expandable" );

		// Expand the array
		var arrayResponse = dap.getVariables( localArray.variablesReference );
		var arrayVars = arrayResponse.body.variables;

		// Should have indexed elements
		expect( arrayVars.len() ).toBeGTE( 5, "Array should have at least 5 elements" );

		// Check first few elements
		var arrayMap = {};
		for ( var v in arrayVars ) {
			arrayMap[ v.name ] = v;
		}

		expect( arrayMap[ "1" ].value ).toBe( "1" );
		expect( arrayMap[ "2" ].value ).toBe( "2" );
		expect( arrayMap[ "4" ].value ).toBe( '"four"' );

		cleanupThread( threadId );
	}

}
