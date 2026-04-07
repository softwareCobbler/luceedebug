/**
 * Tests for breakpointLocations request (valid breakpoint lines in a file).
 *
 * This is a native-only feature that uses Page.getExecutableLines() from Lucee core.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	variables.targetFile = "";

	function beforeAll() {
		setupDap();
		variables.targetFile = getArtifactPath( "breakpoint-target.cfm" );
	}

	function afterAll() {
		teardownDap();
	}

	// ========== Basic Breakpoint Locations ==========

	function testBreakpointLocationsReturnsResults() skip="notSupportsBreakpointLocations" {
		var response = dap.breakpointLocations( variables.targetFile, 1 );

		expect( response.body ).toHaveKey( "breakpoints" );
		expect( response.body.breakpoints ).toBeArray();
	}

	function testBreakpointLocationsForSingleLine() skip="notSupportsBreakpointLocations" {
		// Line 12 is inside simpleFunction - should be executable
		var response = dap.breakpointLocations( variables.targetFile, 12 );

		expect( response.body.breakpoints.len() ).toBeGTE( 1, "Line 12 should be executable" );

		// Verify the returned location
		var location = response.body.breakpoints[ 1 ];
		expect( location.line ).toBe( 12 );
	}

	function testBreakpointLocationsForRange() skip="notSupportsBreakpointLocations" {
		// Request locations for lines 10-20 (covers simpleFunction and conditionalFunction)
		var response = dap.breakpointLocations( variables.targetFile, 10, 20 );

		expect( response.body.breakpoints.len() ).toBeGT( 1, "Should have multiple executable lines in range" );

		// All returned lines should be within the requested range
		for ( var loc in response.body.breakpoints ) {
			expect( loc.line ).toBeGTE( 10, "Line should be >= 10" );
			expect( loc.line ).toBeLTE( 20, "Line should be <= 20" );
		}
	}

	// ========== Non-Executable Lines ==========

	function testBreakpointLocationsEmptyForCommentLine() skip="notSupportsBreakpointLocations" {
		// Lines 1-9 are comments/function declaration - may not all be executable
		// Line 3 specifically is a comment line
		var response = dap.breakpointLocations( variables.targetFile, 3, 3 );

		// Comment lines should not be executable
		// Either empty or the response adjusts to nearest executable line
		if ( response.body.breakpoints.len() > 0 ) {
			// If it returns a location, it should be adjusted to a different line
			expect( response.body.breakpoints[ 1 ].line ).notToBe( 3, "Comment line should not be directly executable" );
		}
	}

	function testBreakpointLocationsEmptyForBlankLine() skip="notSupportsBreakpointLocations" {
		// Test a blank line (if there is one in the file)
		// For breakpoint-target.cfm, let's check line 28 area
		var response = dap.breakpointLocations( variables.targetFile, 28, 28 );

		// This is the last line with content - should be executable or empty
		// The test is mainly to ensure the request doesn't error
		expect( response.body ).toHaveKey( "breakpoints" );
	}

	// ========== Executable Lines Verification ==========

	function testAllReturnedLocationsAreValid() skip="notSupportsBreakpointLocations" {
		// Get all locations in the file
		var response = dap.breakpointLocations( variables.targetFile, 1, 50 );

		// Each returned location should allow setting a breakpoint
		for ( var loc in response.body.breakpoints ) {
			var bpResponse = dap.setBreakpoints( variables.targetFile, [ loc.line ] );
			expect( bpResponse.body.breakpoints[ 1 ].verified ).toBeTrue(
				"Breakpoint at line #loc.line# should be verified"
			);
		}

		// Clean up
		dap.setBreakpoints( variables.targetFile, [] );
	}

	// ========== Different File Types ==========

	function testBreakpointLocationsForSteppingTarget() skip="notSupportsBreakpointLocations" {
		var steppingFile = getArtifactPath( "stepping-target.cfm" );
		var response = dap.breakpointLocations( steppingFile, 1, 30 );

		expect( response.body.breakpoints.len() ).toBeGT( 0, "Should have executable lines" );

		// Lines inside functions should be executable
		var lines = response.body.breakpoints.map( function( loc ) { return loc.line; } );

		// Line 12 (inside innerFunc) should be executable
		expect( lines ).toInclude( 12, "Line 12 in innerFunc should be executable" );

		// Line 17 (inside outerFunc) should be executable
		expect( lines ).toInclude( 17, "Line 17 in outerFunc should be executable" );
	}

	function testBreakpointLocationsForVariablesTarget() skip="notSupportsBreakpointLocations" {
		var variablesFile = getArtifactPath( "variables-target.cfm" );
		var response = dap.breakpointLocations( variablesFile, 1, 50 );

		expect( response.body.breakpoints.len() ).toBeGT( 0, "Should have executable lines" );

		// Line 35 (debugLine assignment) should be executable
		var lines = response.body.breakpoints.map( function( loc ) { return loc.line; } );
		expect( lines ).toInclude( 35, "Line 35 should be executable" );
	}

}
