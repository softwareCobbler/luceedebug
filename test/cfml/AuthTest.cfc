/**
 * Tests for DAP authentication - verifies proper errors when attach() not called.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	include "DapTestCase.cfm";

	function afterEach() {
		teardownDap();
	}

	// ========== Auth Tests ==========

	/**
	 * Verify that setBreakpoints returns error when attach() not called.
	 */
	function testSetBreakpointsWithoutAttachReturnsError() {
		setupDap( attach = false );

		var threw = false;
		var errorMessage = "";
		try {
			dap.setBreakpoints( "/some/file.cfm", [ 10 ] );
		} catch ( any e ) {
			threw = true;
			errorMessage = e.message;
		}

		expect( threw ).toBeTrue( "setBreakpoints should throw when not attached" );
		expect( errorMessage ).toInclude( "Not authorized" );
	}

	/**
	 * Verify that threads returns error when attach() not called.
	 */
	function testThreadsWithoutAttachReturnsError() {
		setupDap( attach = false );

		var threw = false;
		var errorMessage = "";
		try {
			dap.threads();
		} catch ( any e ) {
			threw = true;
			errorMessage = e.message;
		}

		expect( threw ).toBeTrue( "threads should throw when not attached" );
		expect( errorMessage ).toInclude( "Not authorized" );
	}

	/**
	 * Verify that attach with wrong secret returns error.
	 */
	function testAttachWithWrongSecretReturnsError() {
		setupDap( attach = false );

		var threw = false;
		var errorMessage = "";
		try {
			dap.attach( "wrong-secret-value" );
		} catch ( any e ) {
			threw = true;
			errorMessage = e.message;
		}

		expect( threw ).toBeTrue( "attach with wrong secret should throw" );
		expect( errorMessage ).toInclude( "Invalid" );
	}

	/**
	 * Verify that attach with correct secret works.
	 */
	function testAttachWithCorrectSecretSucceeds() {
		setupDap( attach = false );

		var threw = false;
		try {
			dap.attach( variables.dapSecret );
		} catch ( any e ) {
			threw = true;
		}

		expect( threw ).toBeFalse( "attach with correct secret should succeed" );
	}

}
