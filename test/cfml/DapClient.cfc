/**
 * DAP (Debug Adapter Protocol) client for testing luceedebug.
 *
 * Usage:
 *   dap = new DapClient();
 *   dap.connect( "localhost", 10000 );
 *   dap.initialize();
 *   dap.setBreakpoints( "/path/to/file.cfm", [ 10, 20 ] );
 *   dap.configurationDone();
 *   // ... trigger breakpoint via HTTP ...
 *   event = dap.waitForEvent( "stopped", 2000 );
 *   stack = dap.stackTrace( event.body.threadId );
 *   dap.continueThread( event.body.threadId );
 *   dap.disconnect();
 */
component {

	variables.socket = javacast( "null", 0 );
	variables.inputStream = javacast( "null", 0 );
	variables.outputStream = javacast( "null", 0 );
	variables.seq = 0;
	variables.eventQueue = [];
	variables.pendingResponses = {};
	variables.debug = false;

	public function init( boolean debug = false ) {
		variables.debug = arguments.debug;
		return this;
	}

	// ========== Connection ==========

	public function connect( required string host, required numeric port ) {
		variables.socket = createObject( "java", "java.net.Socket" ).init( arguments.host, arguments.port );
		variables.socket.setSoTimeout( 100 ); // 100ms read timeout for polling
		variables.inputStream = variables.socket.getInputStream();
		variables.outputStream = variables.socket.getOutputStream();
		debugLog( "Connected to #arguments.host#:#arguments.port#" );
	}

	public function disconnect() {
		if ( !isNull( variables.socket ) ) {
			variables.socket.close();
			variables.socket = javacast( "null", 0 );
			debugLog( "Disconnected" );
		}
	}

	public boolean function isConnected() {
		return !isNull( variables.socket ) && variables.socket.isConnected() && !variables.socket.isClosed();
	}

	// ========== DAP Commands ==========

	public struct function initialize() {
		var response = sendRequest( "initialize", {
			"clientID": "cfml-dap-test",
			"adapterID": "luceedebug",
			"pathFormat": "path",
			"linesStartAt1": true,
			"columnsStartAt1": true
		} );
		return response;
	}

	public struct function attach( required string secret, struct pathTransforms = {}, boolean consoleOutput = false ) {
		var args = {
			"secret": arguments.secret
		};
		if ( !structIsEmpty( arguments.pathTransforms ) ) {
			args[ "pathTransforms" ] = arguments.pathTransforms;
		}
		if ( arguments.consoleOutput ) {
			args[ "consoleOutput" ] = true;
		}
		var response = sendRequest( "attach", args );
		// Wait for initialized event from server
		waitForEvent( "initialized", 5000 );
		return response;
	}

	public struct function setBreakpoints( required string path, required array lines, array conditions = [] ) {
		var breakpoints = [];
		for ( var i = 1; i <= arguments.lines.len(); i++ ) {
			var bp = { "line": arguments.lines[ i ] };
			if ( arguments.conditions.len() >= i && len( arguments.conditions[ i ] ) ) {
				bp[ "condition" ] = arguments.conditions[ i ];
			}
			breakpoints.append( bp );
		}
		var response = sendRequest( "setBreakpoints", {
			"source": { "path": arguments.path },
			"breakpoints": breakpoints
		} );
		systemOutput( "setBreakpoints: path=#arguments.path# lines=#serializeJSON( arguments.lines )# response=#serializeJSON( response )#", true );
		return response;
	}

	public struct function setFunctionBreakpoints( required array names, array conditions = [] ) {
		var breakpoints = [];
		for ( var i = 1; i <= arguments.names.len(); i++ ) {
			var bp = { "name": arguments.names[ i ] };
			if ( arguments.conditions.len() >= i && len( arguments.conditions[ i ] ) ) {
				bp[ "condition" ] = arguments.conditions[ i ];
			}
			breakpoints.append( bp );
		}
		var response = sendRequest( "setFunctionBreakpoints", {
			"breakpoints": breakpoints
		} );
		return response;
	}

	public struct function setExceptionBreakpoints( required array filters ) {
		var response = sendRequest( "setExceptionBreakpoints", {
			"filters": arguments.filters
		} );
		return response;
	}

	public struct function breakpointLocations( required string path, required numeric line, numeric endLine = 0 ) {
		var args = {
			"source": { "path": arguments.path },
			"line": arguments.line
		};
		if ( arguments.endLine > 0 ) {
			args[ "endLine" ] = arguments.endLine;
		}
		var response = sendRequest( "breakpointLocations", args );
		return response;
	}

	public struct function configurationDone() {
		return sendRequest( "configurationDone", {} );
	}

	public struct function threads() {
		return sendRequest( "threads", {} );
	}

	public struct function stackTrace( required numeric threadId, numeric startFrame = 0, numeric levels = 20 ) {
		return sendRequest( "stackTrace", {
			"threadId": arguments.threadId,
			"startFrame": arguments.startFrame,
			"levels": arguments.levels
		} );
	}

	public struct function scopes( required numeric frameId ) {
		return sendRequest( "scopes", {
			"frameId": arguments.frameId
		} );
	}

	public struct function getVariables( required numeric variablesReference ) {
		return sendRequest( "variables", {
			"variablesReference": arguments.variablesReference
		} );
	}

	public struct function setVariable( required numeric variablesReference, required string name, required string value ) {
		return sendRequest( "setVariable", {
			"variablesReference": arguments.variablesReference,
			"name": arguments.name,
			"value": arguments.value
		} );
	}

	public struct function continueThread( required numeric threadId ) {
		systemOutput( "continueThread: threadId=#arguments.threadId#", true );
		var response = sendRequest( "continue", {
			"threadId": arguments.threadId
		} );
		systemOutput( "continueThread: response=#serializeJSON( response )#", true );
		return response;
	}

	public struct function stepOver( required numeric threadId ) {
		systemOutput( "stepOver: threadId=#arguments.threadId#", true );
		var response = sendRequest( "next", {
			"threadId": arguments.threadId
		} );
		systemOutput( "stepOver: response=#serializeJSON( response )#", true );
		return response;
	}

	public struct function stepIn( required numeric threadId ) {
		systemOutput( "stepIn: threadId=#arguments.threadId#", true );
		var response = sendRequest( "stepIn", {
			"threadId": arguments.threadId
		} );
		systemOutput( "stepIn: response=#serializeJSON( response )#", true );
		return response;
	}

	public struct function stepOut( required numeric threadId ) {
		systemOutput( "stepOut: threadId=#arguments.threadId#", true );
		var response = sendRequest( "stepOut", {
			"threadId": arguments.threadId
		} );
		systemOutput( "stepOut: response=#serializeJSON( response )#", true );
		return response;
	}

	public struct function evaluate( required numeric frameId, required string expression, string context = "watch" ) {
		return sendRequest( "evaluate", {
			"frameId": arguments.frameId,
			"expression": arguments.expression,
			"context": arguments.context
		} );
	}

	public struct function completions( required numeric frameId, required string text, numeric column = 0 ) {
		return sendRequest( "completions", {
			"frameId": arguments.frameId,
			"text": arguments.text,
			"column": arguments.column > 0 ? arguments.column : len( arguments.text ) + 1
		} );
	}

	public struct function exceptionInfo( required numeric threadId ) {
		return sendRequest( "exceptionInfo", {
			"threadId": arguments.threadId
		} );
	}

	public struct function dapDisconnect() {
		return sendRequest( "disconnect", {} );
	}

	// ========== Event Handling ==========

	/**
	 * Wait for a specific event type.
	 * @eventType The event type to wait for (e.g., "stopped", "thread")
	 * @timeoutMs Maximum time to wait in milliseconds
	 * @return The event struct, or throws if timeout
	 */
	public struct function waitForEvent( required string eventType, numeric timeoutMs = 5000 ) {
		var startTime = getTickCount();

		// Give the server a moment to process and send the event
		sleep( 50 );

		while ( getTickCount() - startTime < arguments.timeoutMs ) {
			// Poll for new messages first
			pollMessages();

			// Check queued events
			for ( var i = 1; i <= variables.eventQueue.len(); i++ ) {
				if ( variables.eventQueue[ i ].event == arguments.eventType ) {
					var event = variables.eventQueue[ i ];
					variables.eventQueue.deleteAt( i );
					systemOutput( "waitForEvent: found #arguments.eventType# event=#serializeJSON( event )#", true );
					return event;
				}
			}

			sleep( 10 );
		}

		throw( type="DapClient.Timeout", message="Timeout waiting for event: #arguments.eventType#" );
	}

	/**
	 * Check if any events of a type are queued.
	 */
	public boolean function hasEvent( required string eventType ) {
		pollMessages();
		for ( var event in variables.eventQueue ) {
			if ( event.event == arguments.eventType ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Get all queued events (clears the queue).
	 */
	public array function drainEvents() {
		pollMessages();
		var events = variables.eventQueue;
		variables.eventQueue = [];
		return events;
	}

	// ========== Protocol Implementation ==========

	private struct function sendRequest( required string command, required struct args ) {
		var requestSeq = ++variables.seq;
		var dapRequest = {
			"seq": requestSeq,
			"type": "request",
			"command": arguments.command,
			"arguments": arguments.args
		};

		sendMessage( dapRequest );

		// Wait for response with matching request_seq
		var startTime = getTickCount();
		var timeout = 10000; // 10 second timeout for responses

		while ( getTickCount() - startTime < timeout ) {
			pollMessages();

			if ( variables.pendingResponses.keyExists( requestSeq ) ) {
				var response = variables.pendingResponses[ requestSeq ];
				variables.pendingResponses.delete( requestSeq );

				if ( !response.success ) {
					throw( type="DapClient.Error", message="DAP error: #response.message ?: 'unknown'#" );
				}

				return response;
			}

			sleep( 10 );
		}

		throw( type="DapClient.Timeout", message="Timeout waiting for response to: #arguments.command#" );
	}

	private void function sendMessage( required struct message ) {
		var json = serializeJSON( arguments.message );
		var bytes = json.getBytes( "UTF-8" );
		var CRLF = chr( 13 ) & chr( 10 );
		var header = "Content-Length: #arrayLen( bytes )#" & CRLF & CRLF;

		debugLog( ">>> #json#" );

		variables.outputStream.write( header.getBytes( "UTF-8" ) );
		variables.outputStream.write( bytes );
		variables.outputStream.flush();
	}

	private void function pollMessages() {
		try {
			while ( variables.inputStream.available() > 0 ) {
				var message = readMessage();
				if ( !isNull( message ) ) {
					handleMessage( message );
				}
			}
		} catch ( any e ) {
			// Socket timeout is expected, ignore
			if ( !e.message contains "timed out" && !e.message contains "Read timed out" ) {
				rethrow;
			}
		}
	}

	private any function readMessage() {
		// Read headers until empty line
		var headers = {};
		var headerLine = readLine();

		while ( headerLine != "" ) {
			var colonPos = headerLine.find( ":" );
			if ( colonPos > 0 ) {
				var key = headerLine.left( colonPos - 1 ).trim();
				var value = headerLine.mid( colonPos + 1, headerLine.len() ).trim();
				headers[ key ] = value;
			}
			headerLine = readLine();
		}

		if ( !headers.keyExists( "Content-Length" ) ) {
			return javacast( "null", 0 );
		}

		// Read body
		var contentLength = val( headers[ "Content-Length" ] );
		var bodyBytes = createObject( "java", "java.io.ByteArrayOutputStream" ).init();
		var remaining = contentLength;

		while ( remaining > 0 ) {
			var b = variables.inputStream.read();
			if ( b == -1 ) {
				throw( type="DapClient.Error", message="Unexpected end of stream" );
			}
			bodyBytes.write( b );
			remaining--;
		}

		var json = bodyBytes.toString( "UTF-8" );
		debugLog( "<<< #json#" );

		return deserializeJSON( json );
	}

	private string function readLine() {
		var line = createObject( "java", "java.lang.StringBuilder" ).init();
		var prevChar = 0;

		while ( true ) {
			var b = variables.inputStream.read();
			if ( b == -1 ) {
				break;
			}
			var c = chr( b );
			if ( c == chr( 10 ) ) { // LF
				break;
			}
			if ( c != chr( 13 ) ) { // Skip CR
				line.append( c );
			}
		}

		return line.toString();
	}

	private void function handleMessage( required struct message ) {
		switch ( arguments.message.type ) {
			case "response":
				variables.pendingResponses[ arguments.message.request_seq ] = arguments.message;
				break;
			case "event":
				variables.eventQueue.append( arguments.message );
				break;
			default:
				debugLog( "Unknown message type: #arguments.message.type#" );
		}
	}

	private void function debugLog( required string msg ) {
		if ( variables.debug ) {
			systemOutput( "[DapClient] #arguments.msg#", true );
		}
	}

}
