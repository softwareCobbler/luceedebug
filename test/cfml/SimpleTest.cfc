/**
 * Simple test to verify test discovery works.
 */
component extends="org.lucee.cfml.test.LuceeTestCase" labels="dap" {

	function testSimple() {
		expect( 1 + 1 ).toBe( 2 );
	}

}
