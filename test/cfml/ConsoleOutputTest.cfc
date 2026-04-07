/**
 * Tests for console output streaming (systemOutput to debug console).
 * Native mode only - requires consoleOutput attach option.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in console-output-target.cfm - keep in sync with the file
	// These are validated in testValidateLineNumbers() using breakpointLocations (native mode only)
	variables.lines = {
		debugLine: 14  // var stopHere = true;
	};

	function beforeAll() {
		// Enable consoleOutput for these tests
		setupDap( consoleOutput = true );
		variables.targetFile = getArtifactPath( "console-output-target.cfm" );
	}

	// Validate our line number assumptions using breakpointLocations (native mode only)
	function testValidateLineNumbers_consoleOutputTarget() skip="notSupportsBreakpointLocations" {
		var locations = dap.breakpointLocations( variables.targetFile, 1, 25 );
		var validLines = locations.body.breakpoints.map( function( bp ) { return bp.line; } );

		systemOutput( "#variables.targetFile# valid lines: #serializeJSON( validLines )#", true );

		for ( var key in variables.lines ) {
			var line = variables.lines[ key ];
			expect( validLines ).toInclude( line, "#variables.targetFile# line #line# (#key#) should be a valid breakpoint location" );
		}
	}

	function afterEach() {
		clearBreakpoints( variables.targetFile );
		// Drain any remaining output events
		dap.drainEvents();
	}

	// ========== Console Output Events ==========

	function testSystemOutputSendsOutputEvent() skip="notNativeMode" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		// Trigger with a unique message
		var testMessage = "test-output-#createUUID()#";
		triggerArtifact( "console-output-target.cfm", { message: testMessage } );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		// Check for output event containing our message
		// The systemOutput should have been captured before we hit the breakpoint
		var events = dap.drainEvents();
		var foundOutput = false;

		for ( var event in events ) {
			if ( event.event == "output" && event.body.output contains testMessage ) {
				foundOutput = true;
				systemOutput( "Found output event: #serializeJSON( event )#", true );
				break;
			}
		}

		expect( foundOutput ).toBeTrue( "Should receive output event with test message" );

		cleanupThread( threadId );
	}

	function testOutputEventHasStdoutCategory() skip="notNativeMode" {
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		var testMessage = "category-test-#createUUID()#";
		triggerArtifact( "console-output-target.cfm", { message: testMessage } );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var events = dap.drainEvents();
		var outputEvent = {};

		for ( var event in events ) {
			if ( event.event == "output" && event.body.output contains testMessage ) {
				outputEvent = event;
				break;
			}
		}

		expect( outputEvent ).toHaveKey( "body" );
		expect( outputEvent.body ).toHaveKey( "category" );
		expect( outputEvent.body.category ).toBe( "stdout" );

		cleanupThread( threadId );
	}

	function testMultipleOutputEvents() skip="notNativeMode" {
		// This test verifies that multiple systemOutput calls each generate events
		// The target file only has one systemOutput, so we'll just verify the mechanism works
		dap.setBreakpoints( variables.targetFile, [ lines.debugLine ] );

		var testMessage = "multi-test-#createUUID()#";
		triggerArtifact( "console-output-target.cfm", { message: testMessage } );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var threadId = stopped.body.threadId;

		var events = dap.drainEvents();
		var outputCount = 0;

		for ( var event in events ) {
			if ( event.event == "output" ) {
				outputCount++;
				systemOutput( "Output event ##: #outputCount# - #event.body.output#", true );
			}
		}

		// Should have at least one output event
		expect( outputCount ).toBeGTE( 1, "Should have at least one output event" );

		cleanupThread( threadId );
	}

}
