/**
 * Tests for basic breakpoint functionality.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in breakpoint-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		conditionalFunctionStart: 12,  // var result = 0;
		ifBlockBody: 14,               // result = arguments.value * 2;
		elseBlockBody: 16,             // result = arguments.value + 5;
		writeOutput: 24                // writeOutput( "Done: ..." );
	};

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "breakpoint-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_breakpointTarget() skip="notSupportsBreakpointLocations" {
		var locations = dap.breakpointLocations( variables.targetFile, 1, 30 );
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

	// ========== Basic Breakpoints ==========

	function testSetBreakpointReturnsVerified() {
		var response = dap.setBreakpoints( variables.targetFile, [ lines.conditionalFunctionStart ] );

		expect( response.body ).toHaveKey( "breakpoints" );
		expect( response.body.breakpoints ).toHaveLength( 1 );
		expect( response.body.breakpoints[ 1 ].verified ).toBeTrue( "Breakpoint should be verified" );
	}

	function testSetMultipleBreakpoints() {
		var response = dap.setBreakpoints( variables.targetFile, [
			lines.conditionalFunctionStart,
			lines.ifBlockBody,
			lines.writeOutput
		] );

		expect( response.body.breakpoints ).toHaveLength( 3 );
		for ( var bp in response.body.breakpoints ) {
			expect( bp.verified ).toBeTrue( "All breakpoints should be verified" );
		}
	}

	function testBreakpointHits() {
		var bpResponse = dap.setBreakpoints( variables.targetFile, [ lines.conditionalFunctionStart ] );

		systemOutput( "testBreakpointHits: targetFile=#variables.targetFile#", true );
		systemOutput( "testBreakpointHits: response=#serializeJSON( bpResponse )#", true );

		expect( bpResponse.body.breakpoints ).toHaveLength( 1, "Should have 1 breakpoint" );
		expect( bpResponse.body.breakpoints[ 1 ].verified ).toBeTrue( "Breakpoint should be verified" );

		triggerArtifact( "breakpoint-target.cfm" );
		sleep( 500 );
		systemOutput( "testBreakpointHits: httpResult=#serializeJSON( variables.httpResult )#", true );
		expect( variables.httpResult ).notToHaveKey( "error" );

		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "breakpoint" );

		var frame = getTopFrame( stopped.body.threadId );
		expect( frame.line ).toBe( lines.conditionalFunctionStart );

		cleanupThread( stopped.body.threadId );
	}

	function testClearBreakpoints() {
		dap.setBreakpoints( variables.targetFile, [ lines.conditionalFunctionStart, lines.ifBlockBody ] );

		var response = dap.setBreakpoints( variables.targetFile, [] );

		expect( response.body.breakpoints ).toHaveLength( 0 );
	}

	function testReplaceBreakpoints() {
		dap.setBreakpoints( variables.targetFile, [ lines.conditionalFunctionStart, lines.ifBlockBody ] );

		var response = dap.setBreakpoints( variables.targetFile, [ lines.writeOutput ] );

		expect( response.body.breakpoints ).toHaveLength( 1 );
		expect( response.body.breakpoints[ 1 ].line ).toBe( lines.writeOutput );
	}

	// ========== Conditional Breakpoints ==========

	function testConditionalBreakpointHits() skip="notSupportsConditionalBreakpoints" {
		// Set conditional breakpoint on if block - only hit when value > 10
		dap.setBreakpoints( variables.targetFile, [ lines.ifBlockBody ], [ "arguments.value > 10" ] );

		// Trigger - conditionalFunction is called with 15
		triggerArtifact( "breakpoint-target.cfm" );
		sleep( 500 );
		systemOutput( "testConditionalBreakpointHits: httpResult=#serializeJSON( variables.httpResult )#", true );
		expect( variables.httpResult ).notToHaveKey( "error" );

		// Should hit because 15 > 10
		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "breakpoint" );

		var frame = getTopFrame( stopped.body.threadId );
		expect( frame.line ).toBe( lines.ifBlockBody );

		cleanupThread( stopped.body.threadId );
	}

	function testConditionalBreakpointSkipsWhenFalse() skip="notSupportsConditionalBreakpoints" {
		// Set conditional breakpoint that should NOT hit
		dap.setBreakpoints( variables.targetFile, [ lines.ifBlockBody ], [ "arguments.value > 100" ] );

		// Trigger - conditionalFunction is called with 15
		triggerArtifact( "breakpoint-target.cfm" );
		sleep( 500 );
		systemOutput( "testConditionalBreakpointSkipsWhenFalse: httpResult=#serializeJSON( variables.httpResult )#", true );
		expect( variables.httpResult ).notToHaveKey( "error" );

		// Should NOT hit because 15 is not > 100
		sleep( 2000 );

		var hasStoppedEvent = dap.hasEvent( "stopped" );
		expect( hasStoppedEvent ).toBeFalse( "Should not stop when condition is false" );

		waitForHttpComplete();
	}

}
