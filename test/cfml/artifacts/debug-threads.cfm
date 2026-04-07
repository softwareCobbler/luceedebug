<cfscript>
// Dump luceedebug threads for debugging DAP server issues
threads = createObject( "java", "java.lang.management.ManagementFactory" )
	.getThreadMXBean()
	.dumpAllThreads( true, true );

found = false;
for ( t in threads ) {
	if ( t.getThreadName() contains "luceedebug" ) {
		found = true;
		echo( "Thread: #t.getThreadName()#" & chr(10) );
		echo( "State: #t.getThreadState().name()#" & chr(10) );
		for ( f in t.getStackTrace() ) {
			echo( "  at #f.toString()#" & chr(10) );
		}
		echo( chr(10) );
	}
}

if ( !found ) {
	echo( "No luceedebug threads found!" & chr(10) );
	echo( chr(10) & "All threads:" & chr(10) );
	for ( t in threads ) {
		echo( "#t.getThreadName()# - #t.getThreadState().name()#" & chr(10) );
	}
}
</cfscript>
