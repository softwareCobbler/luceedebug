/**
 * Tests for function breakpoints (break on function name without file/line).
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "function-bp-target.cfm" );
	}

	function afterEach() {
		clearFunctionBreakpoints();
		clearBreakpoints( variables.targetFile );
	}

	// ========== Basic Function Breakpoints ==========

	function testSetFunctionBreakpoint() skip="notSupportsFunctionBreakpoints" {
		var response = dap.setFunctionBreakpoints( [ "targetFunction" ] );

		expect( response.body ).toHaveKey( "breakpoints" );
		expect( response.body.breakpoints ).toHaveLength( 1 );
		// Function breakpoints may not be verified until hit
	}

	function testFunctionBreakpointHits() skip="notSupportsFunctionBreakpoints" {
		dap.setFunctionBreakpoints( [ "targetFunction" ] );

		triggerArtifact( "function-bp-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "function breakpoint" );

		// Verify we're in targetFunction
		var frame = getTopFrame( stopped.body.threadId );
		expect( frame.name ).toInclude( "targetFunction" );

		cleanupThread( stopped.body.threadId );
	}

	// ========== Case Insensitivity ==========

	function testFunctionBreakpointCaseInsensitive() skip="notSupportsFunctionBreakpoints" {
		// Set breakpoint with different case
		dap.setFunctionBreakpoints( [ "TARGETFUNCTION" ] );

		triggerArtifact( "function-bp-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "function breakpoint" );

		var frame = getTopFrame( stopped.body.threadId );
		expect( frame.name.lcase() ).toInclude( "targetfunction" );

		cleanupThread( stopped.body.threadId );
	}

	// ========== Wildcard Function Breakpoints ==========

	function testWildcardFunctionBreakpoint() skip="notSupportsFunctionBreakpoints" {
		// Set wildcard breakpoint for onRequest*
		dap.setFunctionBreakpoints( [ "onRequest*" ] );

		triggerArtifact( "function-bp-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "function breakpoint" );

		// Should hit onRequestStart
		var frame = getTopFrame( stopped.body.threadId );
		expect( frame.name.lcase() ).toInclude( "onrequest" );

		// Continue to see if we hit onRequestEnd too
		dap.continueThread( stopped.body.threadId );

		// May hit onRequestEnd or other onRequest* functions
		try {
			stopped = dap.waitForEvent( "stopped", 2000 );
			var frame2 = getTopFrame( stopped.body.threadId );
			expect( frame2.name.lcase() ).toInclude( "onrequest" );
			cleanupThread( stopped.body.threadId );
		} catch ( any e ) {
			// It's ok if there's no second hit
			waitForHttpComplete();
		}
	}

	// ========== Multiple Function Breakpoints ==========

	function testMultipleFunctionBreakpoints() skip="notSupportsFunctionBreakpoints" {
		dap.setFunctionBreakpoints( [ "targetFunction", "anotherFunction" ] );

		triggerArtifact( "function-bp-target.cfm" );

		// Should hit one of the functions
		var stopped = dap.waitForEvent( "stopped", 2000 );
		var frame = getTopFrame( stopped.body.threadId );

		var hitFunction = frame.name.lcase();
		expect( hitFunction contains "targetfunction" || hitFunction contains "anotherfunction" )
			.toBeTrue( "Should hit one of the breakpoint functions" );

		dap.continueThread( stopped.body.threadId );

		// Should hit the other function
		try {
			stopped = dap.waitForEvent( "stopped", 2000 );
			var frame2 = getTopFrame( stopped.body.threadId );
			var hitFunction2 = frame2.name.lcase();
			expect( hitFunction2 contains "targetfunction" || hitFunction2 contains "anotherfunction" )
				.toBeTrue( "Should hit the other breakpoint function" );
			cleanupThread( stopped.body.threadId );
		} catch ( any e ) {
			waitForHttpComplete();
		}
	}

	// ========== Conditional Function Breakpoints ==========

	function testConditionalFunctionBreakpoint() skip="notSupportsFunctionBreakpoints" {
		// Set conditional function breakpoint
		dap.setFunctionBreakpoints( [ "targetFunction" ], [ "arguments.input == 'test-value'" ] );

		triggerArtifact( "function-bp-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );

		expect( stopped.body.reason ).toBe( "function breakpoint" );

		// Verify we can access arguments
		var frame = getTopFrame( stopped.body.threadId );
		var argsScope = getScopeByName( frame.id, "Arguments" );
		var inputVar = getVariableByName( argsScope.variablesReference, "input" );

		expect( inputVar.value ).toBe( '"test-value"' );

		cleanupThread( stopped.body.threadId );
	}

	// ========== Clear Function Breakpoints ==========

	function testClearFunctionBreakpoints() skip="notSupportsFunctionBreakpoints" {
		// Set function breakpoint
		dap.setFunctionBreakpoints( [ "targetFunction" ] );

		// Clear it
		var response = dap.setFunctionBreakpoints( [] );

		expect( response.body.breakpoints ).toHaveLength( 0 );

		// Verify it doesn't hit
		triggerArtifact( "function-bp-target.cfm" );

		sleep( 2000 );
		var hasStoppedEvent = dap.hasEvent( "stopped" );
		expect( hasStoppedEvent ).toBeFalse( "Should not stop after clearing breakpoints" );

		waitForHttpComplete();
	}

	// ========== Replace Function Breakpoints ==========

	function testReplaceFunctionBreakpoints() skip="notSupportsFunctionBreakpoints" {
		// Set initial breakpoint
		dap.setFunctionBreakpoints( [ "targetFunction" ] );

		// Replace with different function
		var response = dap.setFunctionBreakpoints( [ "anotherFunction" ] );

		expect( response.body.breakpoints ).toHaveLength( 1 );

		triggerArtifact( "function-bp-target.cfm" );

		var stopped = dap.waitForEvent( "stopped", 2000 );
		var frame = getTopFrame( stopped.body.threadId );

		// Should hit anotherFunction, not targetFunction
		expect( frame.name.lcase() ).toInclude( "anotherfunction" );

		cleanupThread( stopped.body.threadId );
	}

}
