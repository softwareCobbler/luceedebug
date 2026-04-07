package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import lucee.runtime.PageContext;

import luceedebug.*;
import luceedebug.coreinject.frame.NativeDebugFrame;
import luceedebug.strong.DapBreakpointID;
import luceedebug.strong.CanonicalServerAbsPath;
import luceedebug.strong.RawIdePath;

/**
 * Native implementation of ILuceeVm that uses only Lucee7+ native debugging APIs.
 * No JDWP connection, no bytecode instrumentation, no agent required.
 *
 * This is for extension-only deployment where luceedebug runs as a Lucee extension
 * rather than a Java agent.
 */
public class NativeLuceeVm implements ILuceeVm {

	private final Config config_;
	private static ClassLoader luceeClassLoader;
	private static final Cleaner cleaner = Cleaner.create();
	private final ValTracker valTracker = new ValTracker(cleaner);

	private Consumer<Long> stepEventCallback = null;
	private BiConsumer<Long, DapBreakpointID> breakpointEventCallback = null;
	private BiConsumer<Long, String> nativeBreakpointEventCallback = null;
	private Consumer<Long> exceptionEventCallback = null;
	private Consumer<Long> pauseEventCallback = null;
	private Consumer<BreakpointsChangedEvent> breakpointsChangedCallback = null;

	private AtomicInteger breakpointID = new AtomicInteger();

	// Cache of frame ID -> frame for scope/variable lookups
	private final ConcurrentHashMap<Long, IDebugFrame> frameCache = new ConcurrentHashMap<>();

	/**
	 * Set the Lucee classloader for reflection access to Lucee core classes.
	 * Must be called before creating NativeLuceeVm in extension mode.
	 */
	public static void setLuceeClassLoader(ClassLoader cl) {
		luceeClassLoader = cl;
	}

	public NativeLuceeVm(Config config) {
		this.config_ = config;

		// Enable native mode
		NativeDebuggerListener.setNativeMode(true);

		// Register native breakpoint suspend callback
		NativeDebuggerListener.setOnNativeSuspendCallback((javaThreadId, label) -> {
			if (nativeBreakpointEventCallback != null) {
				nativeBreakpointEventCallback.accept(javaThreadId, label);
			}
		});

		// Register native step callback
		NativeDebuggerListener.setOnNativeStepCallback(javaThreadId -> {
			if (stepEventCallback != null) {
				stepEventCallback.accept(javaThreadId);
			}
		});

		// Register native exception callback
		NativeDebuggerListener.setOnNativeExceptionCallback(javaThreadId -> {
			if (exceptionEventCallback != null) {
				exceptionEventCallback.accept(javaThreadId);
			}
		});

		// Register native pause callback
		NativeDebuggerListener.setOnNativePauseCallback(javaThreadId -> {
			if (pauseEventCallback != null) {
				pauseEventCallback.accept(javaThreadId);
			}
		});
	}

	private DapBreakpointID nextDapBreakpointID() {
		return new DapBreakpointID(breakpointID.incrementAndGet());
	}

	// ========== Callback registration ==========

	@Override
	public void registerStepEventCallback(Consumer<Long> cb) {
		stepEventCallback = cb;
	}

	@Override
	public void registerBreakpointEventCallback(BiConsumer<Long, DapBreakpointID> cb) {
		// Not used in native-only mode - native breakpoints don't have JDWP breakpoint IDs
		breakpointEventCallback = cb;
	}

	@Override
	public void registerNativeBreakpointEventCallback(BiConsumer<Long, String> cb) {
		nativeBreakpointEventCallback = cb;
	}

	@Override
	public void registerBreakpointsChangedCallback(Consumer<BreakpointsChangedEvent> cb) {
		breakpointsChangedCallback = cb;
	}

	// ========== Thread operations ==========

	// Virtual thread ID for "All Threads" - used when no specific thread is targeted
	// Thread ID 0 means "all threads" in DAP, but VSCode needs a visible thread to send pause
	// We use 1 as a safe ID that won't conflict with real Java thread IDs (which start much higher)
	private static final long ALL_THREADS_VIRTUAL_ID = 1;

	@Override
	public ThreadInfo[] getThreadListing() {
		var result = new ArrayList<ThreadInfo>();
		var seenThreadIds = new java.util.HashSet<Long>();

		// First, add any suspended threads (these are most important for debugging)
		for (Long threadId : NativeDebuggerListener.getSuspendedThreadIds()) {
			Thread thread = findThreadById(threadId);
			if (thread != null) {
				result.add(new ThreadInfo(thread.getId(), thread.getName() + " (suspended)"));
				seenThreadIds.add(threadId);
			}
		}

		try {
			// Get CFMLEngine via the loader's factory (available to extension classloader)
			Object engine = lucee.loader.engine.CFMLEngineFactory.getInstance();

			// The factory returns CFMLEngineWrapper - unwrap to get CFMLEngineImpl
			java.lang.reflect.Method getEngineMethod = engine.getClass().getMethod("getEngine");
			Object engineImpl = getEngineMethod.invoke(engine);

			// Get all CFMLFactory instances from the engine impl
			// CFMLEngineImpl has getCFMLFactories() returning Map<String, CFMLFactory>
			java.lang.reflect.Method getFactoriesMethod = engineImpl.getClass().getMethod("getCFMLFactories");
			@SuppressWarnings("unchecked")
			java.util.Map<String, ?> factoriesMap = (java.util.Map<String, ?>) getFactoriesMethod.invoke(engineImpl);
			Object[] factories = factoriesMap.values().toArray();

			for (Object factory : factories) {
				try {
					// Call getActivePageContexts() - it's in CFMLFactoryImpl
					java.lang.reflect.Method getActiveMethod = factory.getClass().getMethod("getActivePageContexts");
					@SuppressWarnings("unchecked")
					java.util.Map<Integer, ?> activeContexts = (java.util.Map<Integer, ?>) getActiveMethod.invoke(factory);

					// Each PageContext has a getThread() method
					for (Object pc : activeContexts.values()) {
						try {
							java.lang.reflect.Method getThreadMethod = pc.getClass().getMethod("getThread");
							Thread thread = (Thread) getThreadMethod.invoke(pc);
							if (thread != null && !seenThreadIds.contains(thread.getId())) {
								result.add(new ThreadInfo(thread.getId(), thread.getName()));
								seenThreadIds.add(thread.getId());
							}
						} catch (Exception e) {
							// Skip this context if we can't get its thread
						}
					}
				} catch (Exception e) {
					// Skip this factory
				}
			}
		} catch (Exception e) {
			Log.error("Error getting thread listing", e);
		}

		// Always show a virtual "All Threads" entry so VSCode has something to target with pause
		// This allows pause to work even when no specific request thread is visible
		// When paused with this ID, all CFML threads will pause at their next instrumentation point
		if (!seenThreadIds.contains(ALL_THREADS_VIRTUAL_ID)) {
			result.add(0, new ThreadInfo(ALL_THREADS_VIRTUAL_ID, "All CFML Threads"));
		}

		Log.debug("Thread listing: " + result.size() + " threads");
		return result.toArray(new ThreadInfo[0]);
	}

	@Override
	public IDebugFrame[] getStackTrace(long threadID) {
		// In native mode, get frames from the suspended thread's PageContext
		PageContext pc = NativeDebuggerListener.getPageContext(threadID);
		if (pc == null) {
			Log.debug("getStackTrace: no PageContext for thread " + threadID);
			return new IDebugFrame[0];
		}

		// Use NativeDebugFrame to get the CFML stack from PageContext
		// Pass threadID so it can create synthetic frame for top-level code
		IDebugFrame[] frames = NativeDebugFrame.getNativeFrames(pc, valTracker, threadID, luceeClassLoader);
		if (frames == null) {
			Log.debug("getStackTrace: no native frames for thread " + threadID);
			return new IDebugFrame[0];
		}

		// Cache frames for later scope/variable lookups
		for (IDebugFrame frame : frames) {
			frameCache.put(frame.getId(), frame);
		}

		Log.trace("getStackTrace: returning " + frames.length + " frames for thread " + threadID);
		return frames;
	}

	private Thread findThreadById(long threadId) {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.getId() == threadId) {
				return t;
			}
		}
		return null;
	}

	// ========== Variable operations ==========

	@Override
	public IDebugEntity[] getScopes(long frameID) {
		// Look up frame from cache
		IDebugFrame frame = frameCache.get(frameID);
		if (frame == null) {
			Log.debug("getScopes: frame " + frameID + " not found in cache");
			return new IDebugEntity[0];
		}
		return frame.getScopes();
	}

	@Override
	public IDebugEntity[] getVariables(long ID) {
		return getVariablesImpl(ID, null);
	}

	@Override
	public IDebugEntity[] getNamedVariables(long ID) {
		return getVariablesImpl(ID, IDebugEntity.DebugEntityType.NAMED);
	}

	@Override
	public IDebugEntity[] getIndexedVariables(long ID) {
		return getVariablesImpl(ID, IDebugEntity.DebugEntityType.INDEXED);
	}

	private IDebugEntity[] getVariablesImpl(long variablesReference, IDebugEntity.DebugEntityType which) {
		// Look up the object by its variablesReference ID
		var maybeObj = valTracker.maybeGetFromId(variablesReference);
		if (maybeObj.isEmpty()) {
			Log.debug("getVariables: variablesReference " + variablesReference + " not found");
			return new IDebugEntity[0];
		}
		Object obj = maybeObj.get().obj;
		// Get the parent's path and frameId for setVariable support
		String parentPath = valTracker.getPath(variablesReference);
		Long frameId = valTracker.getFrameId(variablesReference);
		return CfValueDebuggerBridge.getAsDebugEntity(valTracker, obj, which, parentPath, frameId);
	}

	// ========== Breakpoint operations ==========

	@Override
	public IBreakpoint[] bindBreakpoints(RawIdePath idePath, CanonicalServerAbsPath serverPath, int[] lines, String[] exprs) {
		// Clear existing native breakpoints for this file
		NativeDebuggerListener.clearBreakpointsForFile(serverPath.get());

		// Get executable lines to validate breakpoints
		int[] executableLines = getExecutableLines(serverPath.get());
		java.util.Set<Integer> validLines = new java.util.HashSet<>();
		for (int line : executableLines) {
			validLines.add(line);
		}

		// Add native breakpoints with optional conditions
		IBreakpoint[] result = new Breakpoint[lines.length];
		for (int i = 0; i < lines.length; i++) {
			String condition = (exprs != null && i < exprs.length) ? exprs[i] : null;
			int requestedLine = lines[i];

			if (validLines.contains(requestedLine)) {
				// Valid executable line - add breakpoint and mark as bound
				NativeDebuggerListener.addBreakpoint(serverPath.get(), requestedLine, condition);
				result[i] = Breakpoint.Bound(requestedLine, nextDapBreakpointID());
			} else {
				// Not an executable line - mark as unbound (unverified)
				result[i] = Breakpoint.Unbound(requestedLine, nextDapBreakpointID());
			}
		}

		return result;
	}

	@Override
	public void clearAllBreakpoints() {
		NativeDebuggerListener.clearAllBreakpoints();
	}

	// ========== Execution control ==========

	@Override
	public void continue_(long threadID) {
		NativeDebuggerListener.resumeNativeThread(threadID);
	}

	@Override
	public void continueAll() {
		NativeDebuggerListener.resumeAllNativeThreads();
	}

	@Override
	public void stepIn(long threadID) {
		int currentDepth = getStackDepthForThread(threadID);
		NativeDebuggerListener.startStepping(threadID, StepMode.STEP_INTO, currentDepth);
		continue_(threadID);
	}

	@Override
	public void stepOver(long threadID) {
		int currentDepth = getStackDepthForThread(threadID);
		NativeDebuggerListener.startStepping(threadID, StepMode.STEP_OVER, currentDepth);
		continue_(threadID);
	}

	@Override
	public void stepOut(long threadID) {
		int currentDepth = getStackDepthForThread(threadID);
		NativeDebuggerListener.startStepping(threadID, StepMode.STEP_OUT, currentDepth);
		continue_(threadID);
	}

	/**
	 * Get the current stack depth for a thread using native debugger frames.
	 * Uses NativeDebuggerListener.getStackDepth() to count only real frames (not synthetic).
	 */
	private int getStackDepthForThread(long threadID) {
		PageContext pc = NativeDebuggerListener.getPageContext(threadID);
		return pc != null ? NativeDebuggerListener.getStackDepth(pc) : 0;
	}

	// ========== Debug utilities ==========

	@Override
	public String dump(int dapVariablesReference) {
		return doDumpNative(dapVariablesReference, false);
	}

	@Override
	public String dumpAsJSON(int dapVariablesReference) {
		return doDumpNative(dapVariablesReference, true);
	}

	@Override
	public String getMetadata(int dapVariablesReference) {
		// Get the object from valTracker
		var maybeObj = valTracker.maybeGetFromId(dapVariablesReference);
		if (maybeObj.isEmpty()) {
			return "\"Variable not found\"";
		}
		Object obj = maybeObj.get().obj;

		// Unwrap MarkerTrait.Scope if needed
		if (obj instanceof CfValueDebuggerBridge.MarkerTrait.Scope) {
			obj = ((CfValueDebuggerBridge.MarkerTrait.Scope) obj).scopelike;
		}

		// Get PageContext from a cached frame
		PageContext pc = null;
		Long frameId = valTracker.getFrameId(dapVariablesReference);
		if (frameId != null) {
			IDebugFrame frame = frameCache.get(frameId);
			if (frame instanceof NativeDebugFrame) {
				pc = ((NativeDebugFrame) frame).getPageContext();
			}
		}

		// Fallback: try any suspended frame's PageContext
		if (pc == null) {
			for (IDebugFrame frame : frameCache.values()) {
				if (frame instanceof NativeDebugFrame) {
					pc = ((NativeDebugFrame) frame).getPageContext();
					if (pc != null) break;
				}
			}
		}

		if (pc == null) {
			return "\"No PageContext available\"";
		}

		return doGetMetadataWithPageContext(pc, obj);
	}

	/**
	 * Execute getMetadata on a separate thread (required for PageContext registration).
	 */
	private String doGetMetadataWithPageContext(PageContext sourcePC, Object target) {
		final var result = new Object() {
			String value = "\"getMetadata failed\"";
		};

		final PageContext pc = sourcePC;
		final Object obj = target;

		Thread thread = new Thread(() -> {
			try {
				ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

				// Register the existing PageContext with ThreadLocal
				Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
				java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
				java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");
				registerMethod.invoke(null, pc);

				try {
					// Call GetMetaData.call(PageContext, Object)
					Class<?> getMetaDataClass = cl.loadClass("lucee.runtime.functions.system.GetMetaData");
					java.lang.reflect.Method callMethod = getMetaDataClass.getMethod("call",
						PageContext.class, Object.class);
					Object metadata = callMethod.invoke(null, pc, obj);

					// Serialize the metadata to JSON
					Class<?> serializeClass = cl.loadClass("lucee.runtime.functions.conversion.SerializeJSON");
					java.lang.reflect.Method serializeMethod = serializeClass.getMethod("call",
						PageContext.class, Object.class, Object.class);
					result.value = (String) serializeMethod.invoke(null, pc, metadata, "struct");
				} finally {
					releaseMethod.invoke(null);
				}
			} catch (Throwable e) {
				Log.debug("getMetadata failed: " + e.getMessage());
				result.value = "\"Error: " + e.getMessage().replace("\"", "\\\"") + "\"";
			}
		});

		thread.start();
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return result.value;
	}

	/**
	 * Native mode dump implementation using reflection to call Lucee functions.
	 * @param dapVariablesReference The variablesReference from DAP
	 * @param asJson If true, returns JSON; if false, returns HTML dump
	 */
	private String doDumpNative(int dapVariablesReference, boolean asJson) {
		// Get the object from valTracker
		var maybeObj = valTracker.maybeGetFromId(dapVariablesReference);
		if (maybeObj.isEmpty()) {
			return asJson ? "\"Variable not found\"" : "<div>Variable not found</div>";
		}
		Object obj = maybeObj.get().obj;

		// Unwrap MarkerTrait.Scope if needed
		if (obj instanceof CfValueDebuggerBridge.MarkerTrait.Scope) {
			obj = ((CfValueDebuggerBridge.MarkerTrait.Scope) obj).scopelike;
		}

		// Get the frameId for this variablesReference to get its PageContext
		Long frameId = valTracker.getFrameId(dapVariablesReference);
		PageContext pc = null;
		if (frameId != null) {
			IDebugFrame frame = frameCache.get(frameId);
			if (frame instanceof NativeDebugFrame) {
				pc = ((NativeDebugFrame) frame).getPageContext();
			}
		}

		// If no PageContext from frame, try to find any suspended frame's PageContext
		if (pc == null) {
			for (IDebugFrame frame : frameCache.values()) {
				if (frame instanceof NativeDebugFrame) {
					pc = ((NativeDebugFrame) frame).getPageContext();
					if (pc != null) break;
				}
			}
		}

		if (pc == null) {
			return asJson ? "\"No PageContext available\"" : "<div>No PageContext available</div>";
		}

		return doDumpWithPageContext(pc, obj, asJson);
	}

	/**
	 * Execute dump on a separate thread (required for PageContext registration).
	 */
	private String doDumpWithPageContext(PageContext sourcePC, Object someDumpable, boolean asJson) {
		final var result = new Object() {
			String value = asJson ? "\"dump failed\"" : "<div>dump failed</div>";
		};

		final PageContext pc = sourcePC;
		final Object dumpable = someDumpable;

		Thread thread = new Thread(() -> {
			try {
				ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

				// Register the existing PageContext with ThreadLocal
				Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
				java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
				java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");
				registerMethod.invoke(null, pc);

				try {
					if (asJson) {
						// Call SerializeJSON
						Class<?> serializeClass = cl.loadClass("lucee.runtime.functions.conversion.SerializeJSON");
						java.lang.reflect.Method callMethod = serializeClass.getMethod("call",
							PageContext.class, Object.class, Object.class);
						result.value = (String) callMethod.invoke(null, pc, dumpable, "struct");
					} else {
						// Use DumpUtil to get DumpData, then HTMLDumpWriter to render
						result.value = wrapDumpInHtmlDoc(dumpObjectAsHtml(pc, cl, dumpable));
					}
				} finally {
					releaseMethod.invoke(null);
				}
			} catch (Throwable e) {
				Log.debug("dump failed: " + e.getMessage());
				result.value = asJson
					? "\"Error: " + e.getMessage().replace("\"", "\\\"") + "\""
					: "<div>Error: " + e.getMessage() + "</div>";
			}
		});

		thread.start();
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return result.value;
	}

	/**
	 * Dump an object to HTML string using Lucee's HTMLDumpWriter.
	 */
	private String dumpObjectAsHtml(PageContext pc, ClassLoader cl, Object obj) throws Exception {
		// Use DumpUtil to get DumpData, then HTMLDumpWriter to render
		Class<?> dumpUtilClass = cl.loadClass("lucee.runtime.dump.DumpUtil");
		Class<?> dumpPropertiesClass = cl.loadClass("lucee.runtime.dump.DumpProperties");
		Class<?> dumpDataClass = cl.loadClass("lucee.runtime.dump.DumpData");

		// Get default dump properties - use DEFAULT_RICH field
		java.lang.reflect.Field defaultField = dumpPropertiesClass.getField("DEFAULT_RICH");
		Object dumpProps = defaultField.get(null);

		// toDumpData(PageContext, Object, int maxlevel, DumpProperties)
		java.lang.reflect.Method toDumpDataMethod = dumpUtilClass.getMethod("toDumpData",
			PageContext.class, Object.class, int.class, dumpPropertiesClass);
		Object dumpData = toDumpDataMethod.invoke(null, pc, obj, 9999, dumpProps);

		// Create HTMLDumpWriter and render
		Class<?> htmlDumpWriterClass = cl.loadClass("lucee.runtime.dump.HTMLDumpWriter");
		Object htmlWriter = htmlDumpWriterClass.getConstructor().newInstance();

		// DumpWriter.toString(PageContext, DumpData)
		java.lang.reflect.Method toStringMethod = htmlDumpWriterClass.getMethod("toString",
			PageContext.class, dumpDataClass);
		return (String) toStringMethod.invoke(htmlWriter, pc, dumpData);
	}

	private static String wrapDumpInHtmlDoc(String dumpHtml) {
		return "<!DOCTYPE html>\n" +
			"<html>\n" +
			"<head>\n" +
			"<style>\n" +
			"body { font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n" +
			"</style>\n" +
			"</head>\n" +
			"<body>\n" +
			dumpHtml +
			"</body>\n" +
			"</html>\n";
	}

	@Override
	public String[] getTrackedCanonicalFileNames() {
		// No class tracking in native mode
		return new String[0];
	}

	@Override
	public String[][] getBreakpointDetail() {
		return NativeDebuggerListener.getBreakpointDetails();
	}

	@Override
	public String getApplicationSettings() {
		// Get PageContext from any suspended frame
		PageContext pc = null;
		for (IDebugFrame frame : frameCache.values()) {
			if (frame instanceof NativeDebugFrame) {
				pc = ((NativeDebugFrame) frame).getPageContext();
				if (pc != null) break;
			}
		}

		if (pc == null) {
			return "\"No PageContext available\"";
		}

		return doGetApplicationSettingsWithPageContext(pc);
	}

	/**
	 * Execute getApplicationSettings on a separate thread (required for PageContext registration).
	 */
	private String doGetApplicationSettingsWithPageContext(PageContext sourcePC) {
		final var result = new Object() {
			String value = "\"getApplicationSettings failed\"";
		};

		final PageContext pc = sourcePC;

		Thread thread = new Thread(() -> {
			try {
				ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

				// Register the existing PageContext with ThreadLocal
				Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
				java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
				java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");
				registerMethod.invoke(null, pc);

				try {
					// Call GetApplicationSettings.call(PageContext)
					Class<?> getAppSettingsClass = cl.loadClass("lucee.runtime.functions.system.GetApplicationSettings");
					java.lang.reflect.Method callMethod = getAppSettingsClass.getMethod("call", PageContext.class);
					Object settings = callMethod.invoke(null, pc);

					// Serialize the settings to JSON
					Class<?> serializeClass = cl.loadClass("lucee.runtime.functions.conversion.SerializeJSON");
					java.lang.reflect.Method serializeMethod = serializeClass.getMethod("call",
						PageContext.class, Object.class, Object.class);
					result.value = (String) serializeMethod.invoke(null, pc, settings, "struct");
				} finally {
					releaseMethod.invoke(null);
				}
			} catch (Throwable e) {
				Log.debug("getApplicationSettings failed: " + e.getMessage());
				result.value = "\"Error: " + e.getMessage().replace("\"", "\\\"") + "\"";
			}
		});

		thread.start();
		try {
			thread.join(5000); // 5 second timeout
		} catch (InterruptedException e) {
			return "\"Timeout getting application settings\"";
		}

		return result.value;
	}

	@Override
	public String getSourcePathForVariablesRef(int variablesRef) {
		return valTracker
			.maybeGetFromId(variablesRef)
			.map(taggedObj -> CfValueDebuggerBridge.getSourcePath(taggedObj.obj))
			.orElse(null);
	}

	@Override
	public org.eclipse.lsp4j.debug.CompletionItem[] getCompletions(int frameId, String partialExpr) {
		// Get PageContext from frame or any suspended frame
		PageContext pc = null;
		IDebugFrame frame = frameCache.get((long) frameId);
		if (frame instanceof NativeDebugFrame) {
			pc = ((NativeDebugFrame) frame).getPageContext();
		}
		if (pc == null) {
			for (IDebugFrame f : frameCache.values()) {
				if (f instanceof NativeDebugFrame) {
					pc = ((NativeDebugFrame) f).getPageContext();
					if (pc != null) break;
				}
			}
		}

		if (pc == null) {
			return new org.eclipse.lsp4j.debug.CompletionItem[0];
		}

		return doGetCompletionsWithPageContext(pc, partialExpr);
	}

	private org.eclipse.lsp4j.debug.CompletionItem[] doGetCompletionsWithPageContext(PageContext pc, String partialExpr) {
		final java.util.List<org.eclipse.lsp4j.debug.CompletionItem> results = new java.util.ArrayList<>();

		try {
			ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

			// Parse the expression: "local.foo.ba" -> base="local.foo", prefix="ba"
			// Or just "va" -> base=null, prefix="va"
			String base = null;
			String prefix = partialExpr.toLowerCase();
			int lastDot = partialExpr.lastIndexOf('.');

			if (lastDot > 0) {
				base = partialExpr.substring(0, lastDot);
				prefix = partialExpr.substring(lastDot + 1).toLowerCase();
			}

			if (base != null) {
				// Evaluate the base to get keys
				try {
					Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
					java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
					java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");
					Class<?> evaluateClass = cl.loadClass("lucee.runtime.functions.dynamicEvaluation.Evaluate");
					java.lang.reflect.Method callMethod = evaluateClass.getMethod("call", PageContext.class, Object[].class);

					registerMethod.invoke(null, pc);
					try {
						Object result = callMethod.invoke(null, pc, new Object[]{base});
						if (result instanceof java.util.Map) {
							@SuppressWarnings("unchecked")
							java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) result;
							for (Object key : map.keySet()) {
								String keyStr = String.valueOf(key);
								if (keyStr.toLowerCase().startsWith(prefix)) {
									var item = new org.eclipse.lsp4j.debug.CompletionItem();
									item.setLabel(keyStr);
									item.setType(org.eclipse.lsp4j.debug.CompletionItemType.PROPERTY);
									results.add(item);
								}
							}
						}
					} finally {
						releaseMethod.invoke(null);
					}
				} catch (Exception e) {
					// Evaluation failed, return empty
					Log.debug("Completion evaluation failed: " + e.getMessage());
				}
			} else {
				// No base - complete from scope names and top-level scope variables
				String[] scopes = {"variables", "local", "arguments", "form", "url", "cgi", "cookie", "session", "application", "server", "request", "this"};
				for (String scope : scopes) {
					if (scope.toLowerCase().startsWith(prefix)) {
						var item = new org.eclipse.lsp4j.debug.CompletionItem();
						item.setLabel(scope);
						item.setType(org.eclipse.lsp4j.debug.CompletionItemType.MODULE);
						results.add(item);
					}
				}

				// Also try to complete from variables scope
				try {
					Object variablesScope = pc.variablesScope();
					if (variablesScope instanceof java.util.Map) {
						@SuppressWarnings("unchecked")
						java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) variablesScope;
						for (Object key : map.keySet()) {
							String keyStr = String.valueOf(key);
							if (keyStr.toLowerCase().startsWith(prefix)) {
								var item = new org.eclipse.lsp4j.debug.CompletionItem();
								item.setLabel(keyStr);
								item.setType(org.eclipse.lsp4j.debug.CompletionItemType.VARIABLE);
								results.add(item);
							}
						}
					}
				} catch (Exception e) {
					// Ignore scope access errors
				}
			}
		} catch (Exception e) {
			Log.debug("Completion failed: " + e.getMessage());
		}

		// Sort by label and limit
		results.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));

		Log.info("Completions for '" + partialExpr + "': returning " + results.size() + " items");
		for (var item : results) {
			Log.debug("  - " + item.getLabel());
		}

		if (results.size() > 100) {
			return results.subList(0, 100).toArray(new org.eclipse.lsp4j.debug.CompletionItem[0]);
		}
		return results.toArray(new org.eclipse.lsp4j.debug.CompletionItem[0]);
	}

	@Override
	public Either<String, Either<ICfValueDebuggerBridge, String>> evaluate(int frameID, String expr) {
		// For native mode, use the frame's PageContext to evaluate expressions
		IDebugFrame frame = frameCache.get((long) frameID);
		if (frame == null) {
			return Either.Left("Frame not found: " + frameID);
		}

		if (!(frame instanceof NativeDebugFrame)) {
			// Fall back to JDWP mode if available
			if (GlobalIDebugManagerHolder.debugManager != null) {
				return GlobalIDebugManagerHolder.debugManager.evaluate((Long)(long)frameID, expr);
			}
			return Either.Left("evaluate only supported for native frames");
		}

		NativeDebugFrame nativeFrame = (NativeDebugFrame) frame;
		PageContext pc = nativeFrame.getPageContext();
		if (pc == null) {
			return Either.Left("No PageContext available for frame");
		}

		try {
			// Use reflection to access Lucee classes - in extension mode, direct class access fails
			ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

			// Get ThreadLocalPageContext class and methods via reflection
			Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
			java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
			java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");

			// Get Evaluate class and call method via reflection
			Class<?> evaluateClass = cl.loadClass("lucee.runtime.functions.dynamicEvaluation.Evaluate");
			java.lang.reflect.Method callMethod = evaluateClass.getMethod("call", PageContext.class, Object[].class);

			// Register PageContext with ThreadLocal so Lucee functions work
			registerMethod.invoke(null, pc);

			try {
				// Evaluate the expression
				Object result = callMethod.invoke(null, pc, new Object[]{expr});

				// Return the result as a debug entity
				if (result == null) {
					return Either.Right(Either.Right("null"));
				} else if (result instanceof String) {
					return Either.Right(Either.Right("\"" + ((String)result).replaceAll("\"", "\\\\\"") + "\""));
				} else if (result instanceof Number || result instanceof Boolean) {
					return Either.Right(Either.Right(result.toString()));
				} else {
					// Complex object - wrap it for display
					CfValueDebuggerBridge bridge = new CfValueDebuggerBridge(valTracker, result);
					return Either.Right(Either.Left(bridge));
				}
			} finally {
				releaseMethod.invoke(null);
			}
		} catch (Throwable e) {
			// Unwrap InvocationTargetException to get the real cause
			Throwable cause = e;
			if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
				cause = e.getCause();
			}
			String msg = cause.getMessage();
			if (msg == null) {
				msg = cause.getClass().getName();
			}
			return Either.Left("Evaluation error: " + msg);
		}
	}

	@Override
	public Either<String, Either<ICfValueDebuggerBridge, String>> setVariable(long variablesReference, String name, String value, long frameIdHint) {
		// Get the frame to access PageContext
		// First try using the frameId from ValTracker (associated with the variablesReference)
		Long trackedFrameId = valTracker.getFrameId(variablesReference);
		long actualFrameId = (trackedFrameId != null) ? trackedFrameId : frameIdHint;

		IDebugFrame frame = frameCache.get(actualFrameId);
		if (frame == null) {
			return Either.Left("Frame not found: " + actualFrameId);
		}

		if (!(frame instanceof NativeDebugFrame)) {
			return Either.Left("setVariable only supported for native frames");
		}

		NativeDebugFrame nativeFrame = (NativeDebugFrame) frame;
		PageContext pc = nativeFrame.getPageContext();
		if (pc == null) {
			return Either.Left("No PageContext available for frame");
		}

		// Get the parent path from ValTracker
		String parentPath = valTracker.getPath(variablesReference);
		if (parentPath == null) {
			return Either.Left("Cannot determine variable path for variablesReference: " + variablesReference);
		}

		// Build the full variable path
		String fullPath = parentPath + "." + name;
		Log.debug("setVariable: " + fullPath + " = " + value);

		try {
			// Use reflection to access Lucee classes - in extension mode, direct class access fails
			ClassLoader cl = luceeClassLoader != null ? luceeClassLoader : pc.getClass().getClassLoader();

			// Get ThreadLocalPageContext class and methods via reflection
			Class<?> tlpcClass = cl.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
			java.lang.reflect.Method registerMethod = tlpcClass.getMethod("register", PageContext.class);
			java.lang.reflect.Method releaseMethod = tlpcClass.getMethod("release");

			// Get Evaluate class and call method via reflection
			Class<?> evaluateClass = cl.loadClass("lucee.runtime.functions.dynamicEvaluation.Evaluate");
			java.lang.reflect.Method callMethod = evaluateClass.getMethod("call", PageContext.class, Object[].class);

			// Register PageContext with ThreadLocal so Lucee functions work
			registerMethod.invoke(null, pc);

			try {
				// First, evaluate the value expression to get the actual object
				Object evaluatedValue = callMethod.invoke(null, pc, new Object[]{value});

				// Use Lucee's setVariable to set the value
				Object result = pc.setVariable(fullPath, evaluatedValue);

				// Return the result as a debug entity
				if (result == null) {
					return Either.Right(Either.Right("null"));
				} else if (result instanceof String) {
					return Either.Right(Either.Right("\"" + ((String)result).replaceAll("\"", "\\\\\"") + "\""));
				} else if (result instanceof Number || result instanceof Boolean) {
					return Either.Right(Either.Right(result.toString()));
				} else {
					// Complex object - wrap it for display
					CfValueDebuggerBridge bridge = new CfValueDebuggerBridge(valTracker, result);
					return Either.Right(Either.Left(bridge));
				}
			} finally {
				releaseMethod.invoke(null);
			}
		} catch (Throwable e) {
			// Unwrap InvocationTargetException to get the real cause
			Throwable cause = e;
			if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
				cause = e.getCause();
			}
			String msg = cause.getMessage();
			if (msg == null) {
				msg = cause.getClass().getName();
			}
			Log.debug("setVariable failed: " + msg);
			return Either.Left("Error setting variable: " + msg);
		}
	}

	@Override
	public void registerExceptionEventCallback(Consumer<Long> cb) {
		exceptionEventCallback = cb;
	}

	@Override
	public void registerPauseEventCallback(Consumer<Long> cb) {
		pauseEventCallback = cb;
	}

	@Override
	public void pause(long threadID) {
		NativeDebuggerListener.requestPause(threadID);
	}

	@Override
	public Throwable getExceptionForThread(long threadId) {
		NativeDebuggerListener.SuspendLocation loc = NativeDebuggerListener.getSuspendLocation(threadId);
		return loc != null ? loc.exception : null;
	}

	/**
	 * Get executable line numbers for a file.
	 * Used by DAP breakpointLocations request.
	 *
	 * @param serverPath The server-side absolute file path
	 * @return Array of line numbers where breakpoints can be set
	 */
	public int[] getExecutableLines(String serverPath) {
		return NativeDebuggerListener.getExecutableLines(serverPath);
	}
}
