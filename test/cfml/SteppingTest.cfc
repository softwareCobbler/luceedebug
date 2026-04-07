/**
 * Tests for stepping functionality (stepIn, stepOver, stepOut).
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	// Line numbers in stepping-target.cfm - keep in sync with the file
	// Agent mode (JDWP) stops at function declaration line, native mode stops at first statement
	variables.lines = {};

	function initLines() {
		// Base line numbers (native mode - stops at first executable statement)
		variables.lines = {
			innerFuncDecl: 8,       // function innerFunc(...) - agent mode stops here on step-in
			innerFuncBody: 9,       // var doubled = arguments.x * 2;
			innerFuncReturn: 10,    // return doubled;
			outerFuncDecl: 13,      // function outerFunc(...) - agent mode stops here on step-in
			outerFuncBody: 14,      // var intermediate = arguments.value + 10;
			outerFuncCallInner: 15, // var result = innerFunc( intermediate );
			outerFuncReturn: 16,    // return result;
			mainCallOuter: 21,      // finalResult = outerFunc( startValue );
			mainWriteOutput: 23,    // writeOutput( "Result: #finalResult#" );
			mainSecondCall: 26,     // secondResult = outerFunc( 100 );
			mainSecondOutput: 28    // writeOutput( " Second: #secondResult#" );
		};
	}

	// Get expected line for step-in: agent mode stops at function declaration, native at first statement
	function getStepInLine( funcBodyLine, funcDeclLine ) {
		if ( isNativeMode() ) {
			return funcBodyLine;
		}
		// Agent mode (JDWP) stops at method entry = function declaration line
		return funcDeclLine;
	}

	// Get expected line for step-over from a function CALL (not from inside a function)
	// Agent mode step-over from a function call stays on same line (JDWP quirk)
	// But step-over from inside a function advances normally
	function getStepOverFromCallLine( expectedLine, currentLine ) {
		if ( isNativeMode() ) {
			return expectedLine;
		}
		// Agent mode step-over from a function call stays on same line
		return currentLine;
	}

	// Get expected line for step-out: agent mode returns to the call line, native returns to next line
	function getStepOutLine( nextLineAfterCall, callLine ) {
		if ( isNativeMode() ) {
			return nextLineAfterCall;
		}
		// Agent mode step-out returns to the call line (before it completes)
		return callLine;
	}

	function run( testResults, testBox ) {
		variables.targetFile = getArtifactPath( "stepping-target.cfm" );

		describe( "Stepping Tests", function() {

			beforeEach( function() {
				// Fresh DAP connection for each test - disconnect triggers continueAll() on server
				setupDap();
				initLines();
			} );

			afterEach( function() {
				clearBreakpoints( variables.targetFile );
				teardownDap();
			} );

			it( title="validates line numbers in stepping-target", skip=notSupportsBreakpointLocations(), body=function() {
				var locations = dap.breakpointLocations( variables.targetFile, 1, 35 );
				var validLines = locations.body.breakpoints.map( function( bp ) { return bp.line; } );

				systemOutput( "#variables.targetFile# valid lines: #serializeJSON( validLines )#", true );

				for ( var key in variables.lines ) {
					var line = variables.lines[ key ];
					expect( validLines ).toInclude( line, "#variables.targetFile# line #line# (#key#) should be a valid breakpoint location. Valid lines: #serializeJSON( validLines )#" );
				}
			} );

			// ========== Step Over ==========

			it( "stepOver skips function call", function() {
				// Set breakpoint at call to outerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.mainCallOuter ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Verify we're at mainCallOuter
				var frame = getTopFrame( threadId );
				expect( frame.line ).toBe( lines.mainCallOuter, "Should start at mainCallOuter" );

				// Step over - should skip into outerFunc and land on mainWriteOutput
				dap.stepOver( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				var expectedLine = getStepOverFromCallLine( lines.mainWriteOutput, lines.mainCallOuter );
				expect( frame.line ).toBe( expectedLine, "Step over should land on line #expectedLine#" );

				cleanupThread( threadId );
			} );

			// ========== Step In ==========

			it( "stepIn enters function", function() {
				// Set breakpoint at call to outerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.mainCallOuter ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Verify we're at mainCallOuter
				var frame = getTopFrame( threadId );
				expect( frame.line ).toBe( lines.mainCallOuter, "Should start at mainCallOuter" );

				// Step in - should enter outerFunc
				dap.stepIn( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				var expectedLine = getStepInLine( lines.outerFuncBody, lines.outerFuncDecl );
				expect( frame.line ).toBe( expectedLine, "Step in should enter outerFunc at line #expectedLine#" );

				cleanupThread( threadId );
			} );

			it( "stepIn enters nested function", function() {
				// Set breakpoint at call to outerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.mainCallOuter ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Step into outerFunc
				dap.stepIn( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				var frame = getTopFrame( threadId );
				var expectedLine = getStepInLine( lines.outerFuncBody, lines.outerFuncDecl );
				expect( frame.line ).toBe( expectedLine, "Should be at outerFunc entry (line #expectedLine#)" );

				// Step over to advance within outerFunc
				// In native mode: from line 14 to 15; in agent mode: from line 13 to 14
				dap.stepOver( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				// After step-over from entry point, we should be at the first/next statement
				// Native: was at 14, now at 15 (outerFuncCallInner)
				// Agent: was at 13 (func decl), now at 14 (outerFuncBody)
				if ( isNativeMode() ) {
					expectedLine = lines.outerFuncCallInner;
				} else {
					expectedLine = lines.outerFuncBody;
				}
				expect( frame.line ).toBe( expectedLine, "Should be at line #expectedLine#" );

				// Agent mode needs an extra step to get to the innerFunc call line
				if ( !isNativeMode() ) {
					dap.stepOver( threadId );
					stopped = dap.waitForEvent( "stopped", 2000 );
					frame = getTopFrame( threadId );
					expect( frame.line ).toBe( lines.outerFuncCallInner, "Agent mode: should now be at outerFuncCallInner" );
				}

				// Step into innerFunc
				dap.stepIn( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				expectedLine = getStepInLine( lines.innerFuncBody, lines.innerFuncDecl );
				expect( frame.line ).toBe( expectedLine, "Step in should enter innerFunc at line #expectedLine#" );

				cleanupThread( threadId );
			} );

			// ========== Step Out ==========

			it( "stepOut exits function", function() {
				// Set breakpoint at call to outerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.mainCallOuter ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Step into outerFunc
				dap.stepIn( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				var frame = getTopFrame( threadId );
				var expectedLine = getStepInLine( lines.outerFuncBody, lines.outerFuncDecl );
				expect( frame.line ).toBe( expectedLine, "Should be inside outerFunc at line #expectedLine#" );

				// Step out - should return to caller
				// Native mode: returns to line after call (mainWriteOutput = 23)
				// Agent mode: returns to call line (mainCallOuter = 21)
				dap.stepOut( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				expectedLine = getStepOutLine( lines.mainWriteOutput, lines.mainCallOuter );
				expect( frame.line ).toBe( expectedLine, "Step out should return to line #expectedLine#" );

				cleanupThread( threadId );
			} );

			it( "stepOut from nested function returns to caller", function() {
				// Set breakpoint inside innerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.innerFuncBody ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Verify we're in innerFunc
				var frame = getTopFrame( threadId );
				expect( frame.line ).toBe( lines.innerFuncBody, "Should be at innerFuncBody" );

				// Step out - should return to outerFunc
				// Native mode: returns to line after innerFunc call (outerFuncReturn = 16)
				// Agent mode: returns to call line (outerFuncCallInner = 15)
				dap.stepOut( threadId );
				stopped = dap.waitForEvent( "stopped", 2000 );

				frame = getTopFrame( threadId );
				var expectedLine = getStepOutLine( lines.outerFuncReturn, lines.outerFuncCallInner );
				expect( frame.line ).toBe( expectedLine, "Step out should return to line #expectedLine#" );

				cleanupThread( threadId );
			} );

			// ========== Stack Trace ==========

			it( "stack trace shows call hierarchy", function() {
				// Set breakpoint inside innerFunc
				dap.setBreakpoints( variables.targetFile, [ lines.innerFuncBody ] );

				triggerArtifact( "stepping-target.cfm" );

				var stopped = dap.waitForEvent( "stopped", 2000 );
				var threadId = stopped.body.threadId;

				// Get full stack trace
				var stackResponse = dap.stackTrace( threadId );
				var frames = stackResponse.body.stackFrames;

				// Should have at least 2 frames: innerFunc -> outerFunc
				// Note: Native mode only shows UDF frames, not the top-level "main" frame
				// Agent mode may show 3 frames including top-level code
				expect( frames.len() ).toBeGTE( 2, "Should have at least 2 stack frames, got #serializeJSON( frames )#" );

				// Top frame should be innerFunc
				expect( frames[ 1 ].name ).toInclude( "innerFunc" );
				expect( frames[ 1 ].line ).toBe( lines.innerFuncBody );

				// Second frame should be outerFunc
				expect( frames[ 2 ].name ).toInclude( "outerFunc" );

				cleanupThread( threadId );
			} );

		} );
	}

}
