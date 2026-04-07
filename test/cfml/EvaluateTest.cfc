/**
 * Tests for evaluate/expression functionality.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in evaluate-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		debugLine: 26  // return localVar & " - " & dataName;
	};

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "evaluate-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_evaluateTarget() skip="notSupportsBreakpointLocations" {
		var locations = dap.breakpointLocations( variables.targetFile, 1, 40 );
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

	// ========== Basic Evaluate ==========

	function testEvaluateSimpleExpression() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate a simple expression
		var evalResponse = dap.evaluate( frame.id, "1 + 1" );

		expect( evalResponse.body.result ).toBe( "2" );

		cleanupThread( threadId );
	}

	function testEvaluateLocalVariable() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate local variable
		var evalResponse = dap.evaluate( frame.id, "localVar" );

		expect( evalResponse.body.result ).toBe( '"local-value"' );

		cleanupThread( threadId );
	}

	function testEvaluateNumericVariable() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate numeric variable
		var evalResponse = dap.evaluate( frame.id, "localNum" );

		expect( evalResponse.body.result ).toBe( "100" );

		cleanupThread( threadId );
	}

	// ========== Struct/Array Access ==========

	function testEvaluateStructKey() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate struct key access
		var evalResponse = dap.evaluate( frame.id, "localStruct.key1" );

		expect( evalResponse.body.result ).toBe( '"value1"' );

		cleanupThread( threadId );
	}

	function testEvaluateNestedStructKey() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate nested struct access
		var evalResponse = dap.evaluate( frame.id, "localStruct.nested.inner" );

		expect( evalResponse.body.result ).toBe( '"deep"' );

		cleanupThread( threadId );
	}

	function testEvaluateArrayElement() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate array element
		var evalResponse = dap.evaluate( frame.id, "localArray[2]" );

		expect( evalResponse.body.result ).toBe( "20" );

		cleanupThread( threadId );
	}

	// ========== Function Arguments ==========

	function testEvaluateArgumentsScope() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate argument access
		var evalResponse = dap.evaluate( frame.id, "arguments.data.name" );

		expect( evalResponse.body.result ).toBe( '"test-input"' );

		cleanupThread( threadId );
	}

	// ========== Expressions ==========

	function testEvaluateStringConcatenation() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate string concatenation
		var evalResponse = dap.evaluate( frame.id, "localVar & ' - ' & dataName" );

		expect( evalResponse.body.result ).toBe( '"local-value - test-input"' );

		cleanupThread( threadId );
	}

	function testEvaluateMathExpression() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate math expression
		var evalResponse = dap.evaluate( frame.id, "localNum * 2 + 50" );

		expect( evalResponse.body.result ).toBe( "250" );

		cleanupThread( threadId );
	}

	// ========== Built-in Functions ==========

	function testEvaluateBuiltInFunction() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate built-in function
		var evalResponse = dap.evaluate( frame.id, "len(localVar)" );

		expect( evalResponse.body.result ).toBe( "11" ); // "local-value" = 11 chars

		cleanupThread( threadId );
	}

	function testEvaluateArrayLen() skip="notSupportsEvaluate" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "evaluate-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Evaluate array length
		var evalResponse = dap.evaluate( frame.id, "arrayLen(localArray)" );

		expect( evalResponse.body.result ).toBe( "3" );

		cleanupThread( threadId );
	}

}
