/**
 * Tests for setVariable functionality (modifying variables at runtime).
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in set-variable-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		checkpoint1: 14,  // var checkpoint1 = true;
		checkpoint2: 20   // var checkpoint2 = true;
	};

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "set-variable-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_setVariableTarget() skip="notSupportsBreakpointLocations" {
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

	// ========== Set String Variable ==========

	function testSetStringVariable() skip="notSupportsSetVariable" {
		// Set breakpoint at first checkpoint
		dap.setBreakpoints( variables.targetFile, [ lines.checkpoint1 ] );

		triggerArtifact( "set-variable-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );

		// Verify original value
		var modifiable = getVariableByName( localScope.variablesReference, "modifiable" );
		expect( modifiable.value ).toBe( '"original"' );

		// Set new value
		var setResponse = dap.setVariable( localScope.variablesReference, "modifiable", '"modified"' );

		expect( setResponse.body.value ).toBe( '"modified"' );

		// Verify change persisted
		var updatedVar = getVariableByName( localScope.variablesReference, "modifiable" );
		expect( updatedVar.value ).toBe( '"modified"' );

		cleanupThread( threadId );
	}

	// ========== Set Numeric Variable ==========

	function testSetNumericVariable() skip="notSupportsSetVariable" {
		dap.setBreakpoints( variables.targetFile, [ lines.checkpoint1 ] );

		triggerArtifact( "set-variable-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );

		// Verify original value
		var numericVar = getVariableByName( localScope.variablesReference, "numericVar" );
		expect( numericVar.value ).toBe( "100" );

		// Set new value
		var setResponse = dap.setVariable( localScope.variablesReference, "numericVar", "999" );

		expect( setResponse.body.value ).toBe( "999" );

		cleanupThread( threadId );
	}

	// ========== Verify Runtime Effect ==========

	function testSetVariableAffectsExecution() skip="notSupportsSetVariable" {
		// Set breakpoints at both checkpoints
		dap.setBreakpoints( variables.targetFile, [ lines.checkpoint1, lines.checkpoint2 ] );

		triggerArtifact( "set-variable-target.cfm" );

		// Hit first breakpoint
		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );

		// Modify the variable
		dap.setVariable( localScope.variablesReference, "modifiable", '"CHANGED"' );
		dap.setVariable( localScope.variablesReference, "numericVar", "555" );

		// Continue to second breakpoint
		dap.continueThread( threadId );
		stopped = dap.waitForEvent( "stopped", 2000 );
		threadId = stopped.body.threadId;

		// At second checkpoint, verify the result variable reflects our changes
		frame = getTopFrame( threadId );
		localScope = getScopeByName( frame.id, "Local" );

		var result = getVariableByName( localScope.variablesReference, "result" );
		// result = modifiable & " - " & numericVar = "CHANGED - 555"
		expect( result.value ).toBe( '"CHANGED - 555"' );

		cleanupThread( threadId );
	}

	// ========== Set Variable Types ==========

	function testSetBooleanVariable() skip="notSupportsSetVariable" {
		dap.setBreakpoints( variables.targetFile, [ lines.checkpoint1 ] );

		triggerArtifact( "set-variable-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var frame = getTopFrame( threadId );
		var localScope = getScopeByName( frame.id, "Local" );

		// checkpoint1 is a boolean
		var checkpoint = getVariableByName( localScope.variablesReference, "checkpoint1" );
		expect( checkpoint.value.lcase() ).toBe( "true" );

		// Set to false
		var setResponse = dap.setVariable( localScope.variablesReference, "checkpoint1", "false" );

		expect( setResponse.body.value.lcase() ).toBe( "false" );

		cleanupThread( threadId );
	}

}
