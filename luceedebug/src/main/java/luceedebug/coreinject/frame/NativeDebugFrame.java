package luceedebug.coreinject.frame;

import lucee.runtime.PageContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import luceedebug.*;
import luceedebug.coreinject.CfValueDebuggerBridge;
import luceedebug.coreinject.DebugEntity;
import luceedebug.coreinject.ValTracker;
import luceedebug.coreinject.CfValueDebuggerBridge.MarkerTrait;

/**
 * Adapter that wraps Lucee7's native DebuggerFrame to implement IDebugFrame.
 * Uses reflection to access the new Lucee7 APIs so we can compile against older Lucee versions.
 *
 * This is used when Lucee's DEBUGGER_ENABLED=true and provides the CFML frame stack
 * without requiring bytecode instrumentation.
 */
public class NativeDebugFrame implements IDebugFrame {
	static private AtomicLong nextId = new AtomicLong( 0 );

	// Reflection cache - initialized once
	private static volatile Boolean nativeFrameSupportAvailable = null;
	private static Method getDebuggerFramesMethod = null;
	private static Method getLineMethod = null;
	private static Method setLineMethod = null;
	private static Field localField = null;
	private static Field argumentsField = null;
	private static Field variablesField = null;
	private static Field pageSourceField = null;
	private static Field functionNameField = null;
	private static Method getDisplayPathMethod = null;

	private final Object nativeFrame; // PageContextImpl.DebuggerFrame (null for synthetic top-level frame)
	private final PageContext pageContext;
	private final ValTracker valTracker;
	private final String sourceFilePath;
	private final String functionName;
	private final long id;
	private final int depth;
	private int syntheticLine; // For synthetic frames only
	private final Throwable exception; // Non-null if this frame is for an exception suspend

	// Scope references from the native frame
	private final Object local;      // lucee.runtime.type.scope.Local
	private final Object arguments;  // lucee.runtime.type.scope.Argument
	private final Object variables;  // lucee.runtime.type.scope.Variables

	// lazy initialized on request for scopes
	private LinkedHashMap<String, CfValueDebuggerBridge> scopes_ = null;

	// Constructor for real native frames (wrapping DebuggerFrame)
	private NativeDebugFrame( Object nativeFrame, PageContext pageContext, ValTracker valTracker, int depth, Throwable exception ) throws Exception {
		this.nativeFrame = nativeFrame;
		this.pageContext = pageContext;
		this.valTracker = valTracker;
		this.id = nextId.incrementAndGet();
		this.depth = depth;
		this.exception = exception;

		// Extract fields using reflection
		this.local = localField.get( nativeFrame );
		this.arguments = argumentsField.get( nativeFrame );
		this.variables = variablesField.get( nativeFrame );
		Object pageSource = pageSourceField.get( nativeFrame );
		this.sourceFilePath = (String) getDisplayPathMethod.invoke( pageSource );
		this.functionName = (String) functionNameField.get( nativeFrame );
	}

	// Constructor for synthetic top-level frame (no DebuggerFrame exists)
	private NativeDebugFrame( PageContext pageContext, ValTracker valTracker, String file, int line, String label, Throwable exception ) {
		this.nativeFrame = null; // synthetic - no native frame
		this.pageContext = pageContext;
		this.valTracker = valTracker;
		this.id = nextId.incrementAndGet();
		this.depth = 0;
		this.syntheticLine = line;
		this.sourceFilePath = file;
		this.exception = exception;

		// Build frame name - use label if provided, otherwise try to get request URL
		if ( label != null && !label.isEmpty() ) {
			this.functionName = label;
		} else {
			// Try to get request URL for more useful frame name
			String requestUrl = getRequestUrl( pageContext );
			this.functionName = (requestUrl != null) ? requestUrl : "<top-level>";
		}

		// For top-level code, use PageContext scopes directly
		this.local = null;
		this.arguments = null;
		try {
			this.variables = pageContext.variablesScope();
		} catch ( Exception e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * Try to get the request URL from PageContext's CGI scope.
	 */
	private static String getRequestUrl( PageContext pc ) {
		try {
			Object cgiScope = pc.cgiScope();
			if ( cgiScope instanceof Map ) {
				@SuppressWarnings("unchecked")
				Map<Object, Object> cgi = (Map<Object, Object>) cgiScope;
				// Try script_name first (just the path), then request_url
				Object scriptName = cgi.get( "script_name" );
				if ( scriptName == null ) {
					// Try with Key object if direct string lookup fails
					for ( Map.Entry<Object, Object> entry : cgi.entrySet() ) {
						String keyStr = entry.getKey().toString().toLowerCase();
						if ( "script_name".equals( keyStr ) ) {
							scriptName = entry.getValue();
							break;
						}
					}
				}
				if ( scriptName != null && !scriptName.toString().isEmpty() ) {
					return scriptName.toString();
				}
			}
		} catch ( Exception e ) {
			// Ignore - fall back to default
		}
		return null;
	}

	@Override
	public String getSourceFilePath() {
		return sourceFilePath;
	}

	@Override
	public long getId() {
		return id;
	}

	@Override
	public String getName() {
		if ( functionName == null ) {
			return "??";
		}
		// Don't add () for synthetic frames (start with < or /) or exception labels
		if ( functionName.startsWith( "<" ) || functionName.startsWith( "/" ) || functionName.contains( ":" ) ) {
			return functionName;
		}
		return functionName + "()";
	}

	@Override
	public int getDepth() {
		return depth;
	}

	@Override
	public int getLine() {
		if ( nativeFrame == null ) {
			// Synthetic frame - return stored line
			return syntheticLine;
		}
		try {
			return (int) getLineMethod.invoke( nativeFrame );
		} catch ( Exception e ) {
			return 0;
		}
	}

	@Override
	public void setLine( int line ) {
		if ( nativeFrame == null ) {
			// Synthetic frame - update stored line
			syntheticLine = line;
			return;
		}
		try {
			setLineMethod.invoke( nativeFrame, line );
		} catch ( Exception e ) {
			// ignore
		}
	}

	/**
	 * Get the PageContext for this frame.
	 * Used by setVariable to execute Lucee code in the correct context.
	 */
	public PageContext getPageContext() {
		return pageContext;
	}

	private void checkedPutScopeRef( String name, Object scope ) {
		if ( scope != null && scope instanceof Map ) {
			var v = new MarkerTrait.Scope( (Map<?, ?>) scope );
			CfValueDebuggerBridge.pin( v );
			var bridge = new CfValueDebuggerBridge( valTracker, v );
			// Track the path for setVariable support - scope name is the root path
			valTracker.setPath( bridge.id, name );
			// Track the frame ID for setVariable support - needed to get PageContext
			valTracker.setFrameId( bridge.id, id );
			scopes_.put( name, bridge );
		}
	}

	private void lazyInitScopeRefs() {
		if ( scopes_ != null ) {
			return;
		}

		scopes_ = new LinkedHashMap<>();

		// If this frame has an exception, add cfcatch scope first (most relevant when debugging exceptions)
		if ( exception != null ) {
			addCfcatchScope();
		}

		// Frame-specific scopes from native DebuggerFrame
		checkedPutScopeRef( "local", local );
		checkedPutScopeRef( "arguments", arguments );
		checkedPutScopeRef( "variables", variables );

		// Global scopes from PageContext - these are shared across frames
		try {
			checkedPutScopeRef( "application", pageContext.applicationScope() );
		} catch ( Throwable e ) { /* scope not available */ }

		try {
			checkedPutScopeRef( "form", pageContext.formScope() );
		} catch ( Throwable e ) { /* scope not available */ }

		try {
			checkedPutScopeRef( "request", pageContext.requestScope() );
		} catch ( Throwable e ) { /* scope not available */ }

		try {
			if ( pageContext.getApplicationContext().isSetSessionManagement() ) {
				checkedPutScopeRef( "session", pageContext.sessionScope() );
			}
		} catch ( Throwable e ) { /* scope not available */ }

		try {
			checkedPutScopeRef( "server", pageContext.serverScope() );
		} catch ( Throwable e ) { /* scope not available */ }

		try {
			checkedPutScopeRef( "url", pageContext.urlScope() );
		} catch ( Throwable e ) { /* scope not available */ }

		// Try to get 'this' scope from variables if it's a ComponentScope
		try {
			if ( variables != null && variables.getClass().getName().equals( "lucee.runtime.ComponentScope" ) ) {
				Method getComponentMethod = variables.getClass().getMethod( "getComponent" );
				Object component = getComponentMethod.invoke( variables );
				checkedPutScopeRef( "this", component );
			}
		} catch ( Throwable e ) { /* scope not available */ }
	}

	/**
	 * Add a cfcatch scope with exception details.
	 * Mimics the structure of CFML's cfcatch variable.
	 */
	private void addCfcatchScope() {
		// Build a map with cfcatch-like properties
		Map<String, Object> cfcatch = new LinkedHashMap<>();

		// Basic exception properties
		cfcatch.put( "type", getExceptionType( exception ) );
		cfcatch.put( "message", exception.getMessage() != null ? exception.getMessage() : "" );

		// Get detail if it's a PageException
		String detail = "";
		String errorCode = "";
		String extendedInfo = "";
		if ( exception instanceof lucee.runtime.exp.PageException ) {
			lucee.runtime.exp.PageException pe = (lucee.runtime.exp.PageException) exception;
			detail = pe.getDetail() != null ? pe.getDetail() : "";
			errorCode = pe.getErrorCode() != null ? pe.getErrorCode() : "";
			extendedInfo = pe.getExtendedInfo() != null ? pe.getExtendedInfo() : "";
		}
		cfcatch.put( "detail", detail );
		cfcatch.put( "errorCode", errorCode );
		cfcatch.put( "extendedInfo", extendedInfo );

		// Java exception info
		cfcatch.put( "javaClass", exception.getClass().getName() );

		// Stack trace as string
		java.io.StringWriter sw = new java.io.StringWriter();
		exception.printStackTrace( new java.io.PrintWriter( sw ) );
		cfcatch.put( "stackTrace", sw.toString() );

		// Add as scope - pin both the wrapper and the inner map to prevent GC
		var v = new MarkerTrait.Scope( cfcatch );
		CfValueDebuggerBridge.pin( cfcatch );
		CfValueDebuggerBridge.pin( v );
		var bridge = new CfValueDebuggerBridge( valTracker, v );
		// Track the path for setVariable support
		valTracker.setPath( bridge.id, "cfcatch" );
		// Track the frame ID for setVariable support
		valTracker.setFrameId( bridge.id, id );
		scopes_.put( "cfcatch", bridge );
	}

	/**
	 * Get the CFML-style type for an exception.
	 */
	private String getExceptionType( Throwable t ) {
		if ( t instanceof lucee.runtime.exp.PageException ) {
			lucee.runtime.exp.PageException pe = (lucee.runtime.exp.PageException) t;
			String type = pe.getTypeAsString();
			if ( type != null && !type.isEmpty() ) {
				return type;
			}
		}
		// Fall back to Java exception type
		return t.getClass().getSimpleName();
	}

	@Override
	public IDebugEntity[] getScopes() {
		lazyInitScopeRefs();
		IDebugEntity[] result = new DebugEntity[scopes_.size()];
		int i = 0;
		for ( var kv : scopes_.entrySet() ) {
			String name = kv.getKey();
			CfValueDebuggerBridge entityRef = kv.getValue();
			var entity = new DebugEntity();
			entity.name = name;
			entity.namedVariables = entityRef.getNamedVariablesCount();
			entity.indexedVariables = entityRef.getIndexedVariablesCount();
			entity.expensive = true;
			entity.variablesReference = entityRef.id;
			result[i] = entity;
			i += 1;
		}
		return result;
	}

	/**
	 * Initialize reflection handles for Lucee7's native debugger frame support.
	 * Returns true if initialization succeeded (Lucee7 with DEBUGGER_ENABLED=true).
	 * @param luceeClassLoader ClassLoader to use for loading Lucee core classes (required in OSGi extension mode)
	 */
	private static synchronized boolean initReflection( ClassLoader luceeClassLoader ) {
		if ( nativeFrameSupportAvailable != null ) {
			return nativeFrameSupportAvailable;
		}

		try {
			// Check if DAP debugger is enabled (via LUCEE_DAP_SECRET env var)
			if ( !EnvUtil.isDebuggerEnabled() ) {
				Log.info( "Native frame support disabled: LUCEE_DAP_SECRET not set" );
				nativeFrameSupportAvailable = false;
				return false;
			}

			// Use provided classloader, fall back to PageContext's classloader
			ClassLoader cl = luceeClassLoader;
			if ( cl == null ) {
				cl = PageContext.class.getClassLoader();
			}

			// Load PageContextImpl via reflection (not directly accessible in OSGi extension mode)
			Class<?> pciClass = cl.loadClass( "lucee.runtime.PageContextImpl" );

			// Get the getDebuggerFrames method
			getDebuggerFramesMethod = pciClass.getMethod( "getDebuggerFrames" );

			// Get DebuggerFrame class (inner class of PageContextImpl)
			Class<?> debuggerFrameClass = cl.loadClass( "lucee.runtime.PageContextImpl$DebuggerFrame" );

			// Get DebuggerFrame fields and methods
			localField = debuggerFrameClass.getField( "local" );
			argumentsField = debuggerFrameClass.getField( "arguments" );
			variablesField = debuggerFrameClass.getField( "variables" );
			pageSourceField = debuggerFrameClass.getField( "pageSource" );
			functionNameField = debuggerFrameClass.getField( "functionName" );
			getLineMethod = debuggerFrameClass.getMethod( "getLine" );
			setLineMethod = debuggerFrameClass.getMethod( "setLine", int.class );

			// Get PageSource.getDisplayPath method
			Class<?> pageSourceClass = cl.loadClass( "lucee.runtime.PageSource" );
			getDisplayPathMethod = pageSourceClass.getMethod( "getDisplayPath" );

			nativeFrameSupportAvailable = true;
			return true;

		} catch ( Throwable e ) {
			// Lucee version doesn't have native debugger frame support
			Log.error( "Failed to initialize native frame support: " + e.getMessage() );
			nativeFrameSupportAvailable = false;
			return false;
		}
	}

	/**
	 * Check if native debugger frames are available in this Lucee version.
	 * Returns true if DEBUGGER_ENABLED is true in Lucee7+.
	 * @param luceeClassLoader ClassLoader to use for loading Lucee core classes
	 */
	public static boolean isNativeFrameSupportAvailable( ClassLoader luceeClassLoader ) {
		return initReflection( luceeClassLoader );
	}

	/**
	 * Get frames from Lucee's native debugger frame stack.
	 * If no native DebuggerFrames exist (top-level code), creates a synthetic frame using the suspend location.
	 * @param pageContext The PageContext
	 * @param valTracker Value tracker for scope references
	 * @param threadId Java thread ID to look up suspend location (for synthetic frames)
	 * @param luceeClassLoader ClassLoader to use for loading Lucee core classes
	 * @return Array of debug frames, or null if not available
	 */
	public static IDebugFrame[] getNativeFrames( PageContext pageContext, ValTracker valTracker, long threadId, ClassLoader luceeClassLoader ) {
		if ( !isNativeFrameSupportAvailable( luceeClassLoader ) ) {
			Log.debug( "getNativeFrames: native frame support not available" );
			return null;
		}

		try {
			// pageContext is actually a PageContextImpl, invoke method via reflection
			Object[] nativeFrames = (Object[]) getDebuggerFramesMethod.invoke( pageContext );

			// Get suspend location - may contain exception info
			var location = luceedebug.coreinject.NativeDebuggerListener.getSuspendLocation( threadId );
			Throwable exception = (location != null) ? location.exception : null;

			// Convert to IDebugFrame array, filtering frames with line 0
			ArrayList<IDebugFrame> result = new ArrayList<>();

			if ( nativeFrames != null && nativeFrames.length > 0 ) {
				// Native frames are in push order (oldest first), DAP expects newest first
				for ( int i = nativeFrames.length - 1; i >= 0; i-- ) {
					Object nf = nativeFrames[i];
					int line = (int) getLineMethod.invoke( nf );

					// Skip frames with line 0 (not yet stepped into)
					if ( line == 0 ) {
						continue;
					}

					// Only pass exception to the topmost frame (first one added to result)
					Throwable frameException = result.isEmpty() ? exception : null;
					result.add( new NativeDebugFrame( nf, pageContext, valTracker, i, frameException ) );
				}
			}

			// If no frames from native stack, try to create synthetic frame from suspend location
			if ( result.isEmpty() && threadId >= 0 ) {
				Log.trace( "Checking suspend location for thread " + threadId + ": " + (location != null ? location.file + ":" + location.line : "null") );
				if ( location != null && location.file != null && location.line > 0 ) {
					Log.trace( "Creating synthetic frame for top-level code: " + location.file + ":" + location.line + (location.label != null ? " label=" + location.label : "") );
					result.add( new NativeDebugFrame( pageContext, valTracker, location.file, location.line, location.label, exception ) );
				}
			}

			if ( result.isEmpty() ) {
				return null;
			}

			return result.toArray( new IDebugFrame[0] );

		} catch ( Throwable e ) {
			System.err.println( "[luceedebug] Error getting native frames: " + e.getMessage() );
			return null;
		}
	}
}
