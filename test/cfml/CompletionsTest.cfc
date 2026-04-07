/**
 * Tests for debug console completions/autocomplete functionality.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in completions-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		debugLine: 22  // var stopHere = true;
	};

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "completions-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_completionsTarget() skip="notSupportsBreakpointLocations" {
		var locations = dap.breakpointLocations( variables.targetFile, 1, 35 );
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

	// ========== Basic Completions ==========

	function testCompletionsReturnsResults() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "my"
		var completionsResponse = dap.completions( frame.id, "my" );

		expect( completionsResponse.body ).toHaveKey( "targets" );
		expect( completionsResponse.body.targets.len() ).toBeGT( 0, "Should return completions" );

		cleanupThread( threadId );
	}

	function testCompletionsForLocalVariables() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "my" - should include myString, myNumber, myStruct, etc.
		var completionsResponse = dap.completions( frame.id, "my" );
		var targets = completionsResponse.body.targets;

		var labels = targets.map( function( t ) { return t.label; } );

		expect( labels ).toInclude( "myString" );
		expect( labels ).toInclude( "myNumber" );
		expect( labels ).toInclude( "myStruct" );
		expect( labels ).toInclude( "myArray" );

		cleanupThread( threadId );
	}

	// ========== Dot-Notation Completions ==========

	function testCompletionsForStructKeys() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "myStruct." - should show struct keys
		var completionsResponse = dap.completions( frame.id, "myStruct." );
		var targets = completionsResponse.body.targets;

		var labels = targets.map( function( t ) { return t.label; } );

		expect( labels ).toInclude( "firstName" );
		expect( labels ).toInclude( "lastName" );
		expect( labels ).toInclude( "address" );

		cleanupThread( threadId );
	}

	function testCompletionsForNestedStructKeys() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "myStruct.address." - should show nested keys
		var completionsResponse = dap.completions( frame.id, "myStruct.address." );
		var targets = completionsResponse.body.targets;

		var labels = targets.map( function( t ) { return t.label; } );

		expect( labels ).toInclude( "street" );
		expect( labels ).toInclude( "city" );

		cleanupThread( threadId );
	}

	// ========== Partial Completions ==========

	function testCompletionsWithPartialInput() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "myStruct.f" - should filter to firstName
		var completionsResponse = dap.completions( frame.id, "myStruct.f" );
		var targets = completionsResponse.body.targets;

		var labels = targets.map( function( t ) { return t.label; } );

		expect( labels ).toInclude( "firstName" );
		// lastName shouldn't be included (doesn't start with 'f')
		expect( labels ).notToInclude( "lastName" );

		cleanupThread( threadId );
	}

	// ========== Component Method Completions ==========

	function testCompletionsForComponentMethods() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for "myComponent." - should show component methods
		var completionsResponse = dap.completions( frame.id, "myComponent." );
		var targets = completionsResponse.body.targets;

		var labels = targets.map( function( t ) { return t.label; } );

		// DapClient has methods like connect, disconnect, initialize, etc.
		expect( labels ).toInclude( "connect" );
		expect( labels ).toInclude( "disconnect" );

		cleanupThread( threadId );
	}

	// ========== Empty/Invalid Input ==========

	function testCompletionsWithEmptyInput() skip="notSupportsCompletions" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		triggerArtifact( "completions-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );

		// Request completions for empty string - should return local variables
		var completionsResponse = dap.completions( frame.id, "" );
		var targets = completionsResponse.body.targets;

		// Should have some completions (local variables, etc.)
		expect( targets.len() ).toBeGT( 0, "Empty input should return completions" );

		cleanupThread( threadId );
	}

}
