package luceedebug.coreinject;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import lucee.runtime.PageContext;
import luceedebug.Config;
import luceedebug.Log;

/**
 * Step mode for stepping operations.
 */
enum StepMode {
	NONE,
	STEP_INTO,
	STEP_OVER,
	STEP_OUT
}

/**
 * Implementation of Lucee's DebuggerListener interface for native breakpoint support.
 * This allows luceedebug to receive suspend/resume callbacks and manage breakpoints
 * without JDWP instrumentation.
 *
 * Note: This class implements the interface via reflection since it's in Lucee core,
 * not in the loader. We create a dynamic proxy that forwards calls to this class.
 *
 * TODO: Consider using DAP OutputEvent to show debug messages in VS Code Debug Console
 * instead of System.out.println. This would give users visibility into native breakpoint
 * activity within the IDE.
 */
public class NativeDebuggerListener {

	/**
	 * Name for this debugger (shown in Lucee logs).
	 */
	public static String getName() {
		return NativeDebuggerListener.class.getName();
	}

	/**
	 * Breakpoint storage - parallel arrays for fast lookup.
	 * Writers synchronize on breakpointLock, readers just access the arrays.
	 * The volatile hasSuspendConditions provides the memory barrier for visibility.
	 */
	private static final Object breakpointLock = new Object();
	private static int[] bpLines = new int[0];
	private static String[] bpFiles = new String[0];
	private static String[] bpConditions = new String[0];  // null entries for unconditional

	/**
	 * Pre-computed bounds for fast rejection in shouldSuspend().
	 * Updated whenever breakpoints change.
	 */
	private static int bpMinLine = Integer.MAX_VALUE;
	private static int bpMaxLine = Integer.MIN_VALUE;
	private static int bpMaxPathLen = 0;

	/**
	 * Function breakpoint storage - parallel arrays for fast lookup.
	 * Writers synchronize on funcBpLock, readers just access the arrays.
	 */
	private static final Object funcBpLock = new Object();
	private static String[] funcBpNames = new String[0];        // lowercase function names
	private static String[] funcBpComponents = new String[0];   // lowercase component names (null = any)
	private static String[] funcBpConditions = new String[0];   // CFML conditions (null = unconditional)
	private static boolean[] funcBpIsWildcard = new boolean[0]; // true if name ends with *

	/**
	 * Pre-computed bounds for fast rejection in onFunctionEntry().
	 */
	private static int funcBpMinLen = Integer.MAX_VALUE;
	private static int funcBpMaxLen = Integer.MIN_VALUE;
	private static volatile boolean hasFuncBps = false;

	/**
	 * Map of Java thread ID -> WeakReference<PageContext> for natively suspended threads.
	 * Used to call debuggerResume() when DAP continue is received.
	 * Note: We use PageContext (loader interface) not PageContextImpl to avoid class loading cycles.
	 */
	private static final ConcurrentHashMap<Long, WeakReference<PageContext>> nativelySuspendedThreads = new ConcurrentHashMap<>();

	/**
	 * Suspend location info for threads (file and line where suspended).
	 * Used when there are no native DebuggerFrames (top-level code).
	 */
	private static final ConcurrentHashMap<Long, SuspendLocation> suspendLocations = new ConcurrentHashMap<>();

	/**
	 * Simple holder for file/line/label at suspend point.
	 */
	public static class SuspendLocation {
		public final String file;
		public final int line;
		public final String label;
		public final Throwable exception; // non-null if suspended due to exception
		public SuspendLocation( String file, int line, String label ) {
			this( file, line, label, null );
		}
		public SuspendLocation( String file, int line, String label, Throwable exception ) {
			this.file = file;
			this.line = line;
			this.label = label;
			this.exception = exception;
		}
	}

	/**
	 * Pending exception for a thread - stored when onException returns true,
	 * then consumed when onSuspend is called.
	 */
	private static final ConcurrentHashMap<Long, Throwable> pendingExceptions = new ConcurrentHashMap<>();

	/**
	 * Cache for executable lines - keyed by file path, stores compileTime and lines.
	 * Avoids re-decoding bitmap on every breakpoint set if file hasn't changed.
	 */
	private static class CachedExecutableLines {
		final long compileTime;
		final int[] lines;
		CachedExecutableLines( long compileTime, int[] lines ) {
			this.compileTime = compileTime;
			this.lines = lines;
		}
	}
	private static final ConcurrentHashMap<String, CachedExecutableLines> executableLinesCache = new ConcurrentHashMap<>();

	/**
	 * Callback to notify LuceeVm when a thread suspends via native breakpoint.
	 * Called with Java thread ID and optional label. Used for "breakpoint" stop reason in DAP.
	 * Label is non-null for programmatic breakpoint("label") calls, null otherwise.
	 */
	private static volatile BiConsumer<Long, String> onNativeSuspendCallback = null;

	/**
	 * Callback to notify LuceeVm when a thread stops after a step.
	 * Called with Java thread ID. Used for "step" stop reason in DAP.
	 */
	private static volatile Consumer<Long> onNativeStepCallback = null;

	/**
	 * Callback to notify LuceeVm when a thread stops due to an exception.
	 * Called with Java thread ID. Used for "exception" stop reason in DAP.
	 */
	private static volatile Consumer<Long> onNativeExceptionCallback = null;

	/**
	 * Native mode flag - when true, use Lucee's DebuggerRegistry API.
	 */
	private static volatile boolean nativeMode = false;

	/**
	 * Flag indicating a DAP client is actually connected.
	 * Set to true when DAP client connects, false when it disconnects.
	 * Distinct from callbacks being registered (which happens at extension startup).
	 */
	private static volatile boolean dapClientConnected = false;

	/**
	 * Flag to break on uncaught exceptions.
	 * Set via DAP setExceptionBreakpoints request.
	 */
	private static volatile boolean breakOnUncaughtExceptions = false;

	/**
	 * Flag to forward System.out/err to DAP client.
	 * Set via launch.json consoleOutput option.
	 */
	private static volatile boolean consoleOutput = false;

	/**
	 * Fast-path flag: true when there's anything that could cause a suspend.
	 * When false, shouldSuspend() returns immediately without any work.
	 * Updated whenever breakpoints, exceptions, or stepping state changes.
	 */
	private static volatile boolean hasSuspendConditions = false;

	/**
	 * Per-thread stepping state.
	 */
	private static final ConcurrentHashMap<Long, StepState> steppingThreads = new ConcurrentHashMap<>();

	/**
	 * Threads that have been requested to pause.
	 * Checked in shouldSuspend() - when a thread is in this set it will pause at the next CFML line.
	 */
	private static final ConcurrentHashMap<Long, Boolean> threadsToPause = new ConcurrentHashMap<>();

	/**
	 * Threads that suspended due to a pause request.
	 * Set in shouldSuspend() when consuming a pause request, consumed in onSuspend().
	 */
	private static final ConcurrentHashMap<Long, Boolean> pausedThreads = new ConcurrentHashMap<>();

	/**
	 * Callback to notify when a thread pauses due to user-initiated pause request.
	 * Called with Java thread ID. Used for "pause" stop reason in DAP.
	 */
	private static volatile Consumer<Long> onNativePauseCallback = null;

	/**
	 * Update hasSuspendConditions flag based on current state.
	 * Called whenever breakpoints, exception settings, stepping, or pause state changes.
	 */
	private static void updateHasSuspendConditions() {
		hasSuspendConditions = dapClientConnected &&
			(bpLines.length > 0 || hasFuncBps || breakOnUncaughtExceptions || !steppingThreads.isEmpty() || !threadsToPause.isEmpty());
	}

	/**
	 * Rebuild breakpoint bounds after any modification.
	 * Must be called while holding breakpointLock.
	 */
	private static void rebuildBreakpointBounds() {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int maxLen = 0;
		for (int i = 0; i < bpLines.length; i++) {
			if (bpLines[i] < min) min = bpLines[i];
			if (bpLines[i] > max) max = bpLines[i];
			if (bpFiles[i].length() > maxLen) maxLen = bpFiles[i].length();
		}
		bpMinLine = min;
		bpMaxLine = max;
		bpMaxPathLen = maxLen;
	}

	/**
	 * Stepping state for a single thread.
	 */
	private static class StepState {
		final StepMode mode;
		final int startDepth;

		StepState(StepMode mode, int startDepth) {
			this.mode = mode;
			this.startDepth = startDepth;
		}
	}

	/**
	 * Cached reflection method for DebuggerFrame.getLine().
	 * Initialized lazily on first use in getStackDepth().
	 */
	private static volatile java.lang.reflect.Method debuggerFrameGetLineMethod = null;

	public static void setNativeMode(boolean enabled) {
		nativeMode = enabled;
		Log.info("Native mode: " + enabled);
	}

	public static boolean isNativeMode() {
		return nativeMode;
	}

	/**
	 * Set the callback for native suspend events (breakpoints).
	 * LuceeVm should register this to receive notifications and send DAP stopped events.
	 */
	public static void setOnNativeSuspendCallback(BiConsumer<Long, String> callback) {
		onNativeSuspendCallback = callback;
	}

	/**
	 * Set the callback for native step events.
	 * LuceeVm should register this to receive notifications and send DAP step events.
	 */
	public static void setOnNativeStepCallback(Consumer<Long> callback) {
		onNativeStepCallback = callback;
	}

	/**
	 * Set the callback for native exception events.
	 * LuceeVm should register this to receive notifications and send DAP exception events.
	 */
	public static void setOnNativeExceptionCallback(Consumer<Long> callback) {
		onNativeExceptionCallback = callback;
	}

	/**
	 * Set the callback for native pause events.
	 * LuceeVm should register this to receive notifications and send DAP pause events.
	 */
	public static void setOnNativePauseCallback(Consumer<Long> callback) {
		onNativePauseCallback = callback;
	}

	/**
	 * Add a breakpoint at the given file and line.
	 */
	public static void addBreakpoint(String file, int line) {
		addBreakpoint(file, line, null);
	}

	/**
	 * Add a breakpoint at the given file and line with optional condition.
	 * @param condition CFML expression to evaluate, or null for unconditional breakpoint
	 */
	public static void addBreakpoint(String file, int line, String condition) {
		String canonFile = Config.canonicalizeFileName(file);
		String newCondition = (condition != null && !condition.isEmpty()) ? condition : null;
		synchronized (breakpointLock) {
			// Check if already exists - update condition if so
			for (int i = 0; i < bpLines.length; i++) {
				if (bpLines[i] == line && bpFiles[i].equals(canonFile)) {
					// Copy-on-write: create new array for thread safety
					String[] newConditions = bpConditions.clone();
					newConditions[i] = newCondition;
					bpConditions = newConditions;
					Log.info("Breakpoint updated: " + Config.shortenPath(canonFile) + ":" + line);
					return;
				}
			}
			// Add new breakpoint
			int len = bpLines.length;
			int[] newLines = new int[len + 1];
			String[] newFiles = new String[len + 1];
			String[] newConditions = new String[len + 1];
			System.arraycopy(bpLines, 0, newLines, 0, len);
			System.arraycopy(bpFiles, 0, newFiles, 0, len);
			System.arraycopy(bpConditions, 0, newConditions, 0, len);
			newLines[len] = line;
			newFiles[len] = canonFile;
			newConditions[len] = newCondition;
			bpLines = newLines;
			bpFiles = newFiles;
			bpConditions = newConditions;
			rebuildBreakpointBounds();
		}
		updateHasSuspendConditions();
		Log.info("Breakpoint set: " + Config.shortenPath(canonFile) + ":" + line +
			(newCondition != null ? " condition=" + newCondition : ""));
	}

	/**
	 * Remove a breakpoint at the given file and line.
	 */
	public static void removeBreakpoint(String file, int line) {
		String canonFile = Config.canonicalizeFileName(file);
		synchronized (breakpointLock) {
			int idx = -1;
			for (int i = 0; i < bpLines.length; i++) {
				if (bpLines[i] == line && bpFiles[i].equals(canonFile)) {
					idx = i;
					break;
				}
			}
			if (idx < 0) return;  // not found

			int len = bpLines.length;
			int[] newLines = new int[len - 1];
			String[] newFiles = new String[len - 1];
			String[] newConditions = new String[len - 1];
			System.arraycopy(bpLines, 0, newLines, 0, idx);
			System.arraycopy(bpLines, idx + 1, newLines, idx, len - idx - 1);
			System.arraycopy(bpFiles, 0, newFiles, 0, idx);
			System.arraycopy(bpFiles, idx + 1, newFiles, idx, len - idx - 1);
			System.arraycopy(bpConditions, 0, newConditions, 0, idx);
			System.arraycopy(bpConditions, idx + 1, newConditions, idx, len - idx - 1);
			bpLines = newLines;
			bpFiles = newFiles;
			bpConditions = newConditions;
			rebuildBreakpointBounds();
		}
		updateHasSuspendConditions();
		Log.info("Breakpoint removed: " + Config.shortenPath(canonFile) + ":" + line);
	}

	/**
	 * Clear all breakpoints for a given file.
	 */
	public static void clearBreakpointsForFile(String file) {
		String canonFile = Config.canonicalizeFileName(file);
		synchronized (breakpointLock) {
			// Count how many to keep
			int keepCount = 0;
			for (int i = 0; i < bpFiles.length; i++) {
				if (!bpFiles[i].equals(canonFile)) keepCount++;
			}
			if (keepCount == bpFiles.length) return;  // nothing to remove

			int[] newLines = new int[keepCount];
			String[] newFiles = new String[keepCount];
			String[] newConditions = new String[keepCount];
			int j = 0;
			for (int i = 0; i < bpFiles.length; i++) {
				if (!bpFiles[i].equals(canonFile)) {
					newLines[j] = bpLines[i];
					newFiles[j] = bpFiles[i];
					newConditions[j] = bpConditions[i];
					j++;
				}
			}
			bpLines = newLines;
			bpFiles = newFiles;
			bpConditions = newConditions;
			rebuildBreakpointBounds();
		}
		updateHasSuspendConditions();
		Log.debug("Breakpoints cleared: " + Config.shortenPath(file));
	}

	/**
	 * Clear all breakpoints.
	 */
	public static void clearAllBreakpoints() {
		synchronized (breakpointLock) {
			bpLines = new int[0];
			bpFiles = new String[0];
			bpConditions = new String[0];
			bpMinLine = Integer.MAX_VALUE;
			bpMaxLine = Integer.MIN_VALUE;
			bpMaxPathLen = 0;
		}
		updateHasSuspendConditions();
		Log.debug("Breakpoints cleared: all");
	}

	/**
	 * Get breakpoint count (for debugging).
	 */
	public static int getBreakpointCount() {
		return bpLines.length;
	}

	/**
	 * Get breakpoint details as array of [file, line] pairs.
	 * Used by debugBreakpointBindings command.
	 * @return Array of [serverPath, "line:N"] pairs
	 */
	public static String[][] getBreakpointDetails() {
		int[] lines = bpLines;
		String[] files = bpFiles;
		String[][] result = new String[lines.length][2];
		for (int i = 0; i < lines.length; i++) {
			result[i][0] = files[i];
			result[i][1] = "line:" + lines[i];
		}
		return result;
	}

	/**
	 * Check if a thread is natively suspended.
	 */
	public static boolean isNativelySuspended(long javaThreadId) {
		return nativelySuspendedThreads.containsKey(javaThreadId);
	}

	/**
	 * Get all suspended thread IDs.
	 * Used by NativeLuceeVm to include suspended threads in thread listing.
	 */
	public static java.util.Set<Long> getSuspendedThreadIds() {
		return new java.util.HashSet<>(nativelySuspendedThreads.keySet());
	}

	/**
	 * Get any PageContext from the suspended threads map.
	 * Used to bootstrap access to CFMLFactory for thread listing.
	 * @return a PageContext if any thread is suspended, null otherwise
	 */
	public static PageContext getAnyPageContext() {
		for (WeakReference<PageContext> ref : nativelySuspendedThreads.values()) {
			PageContext pc = ref.get();
			if (pc != null) {
				return pc;
			}
		}
		return null;
	}

	/**
	 * Get PageContext for a specific suspended thread.
	 * Used by NativeLuceeVm to get stack frames.
	 * @param javaThreadId The Java thread ID
	 * @return the PageContext if found and still valid, null otherwise
	 */
	public static PageContext getPageContext(long javaThreadId) {
		WeakReference<PageContext> ref = nativelySuspendedThreads.get(javaThreadId);
		if (ref == null) {
			Log.warn("getPageContext: thread " + javaThreadId + " not in map! Map=" + nativelySuspendedThreads.keySet());
			return null;
		}
		PageContext pc = ref.get();
		if (pc == null) {
			Log.warn("getPageContext: PageContext for thread " + javaThreadId + " was GC'd!");
		}
		return pc;
	}

	/**
	 * Get suspend location for a specific thread.
	 * Used to create synthetic frame for top-level code.
	 * @param javaThreadId The Java thread ID
	 * @return the SuspendLocation if thread is suspended, null otherwise
	 */
	public static SuspendLocation getSuspendLocation(long javaThreadId) {
		return suspendLocations.get(javaThreadId);
	}

	/**
	 * Resume a natively suspended thread by calling debuggerResume() on its PageContext.
	 * Uses reflection since debuggerResume() is a Lucee7+ method not in the loader interface.
	 * @return true if the thread was found and resumed, false otherwise
	 */
	public static boolean resumeNativeThread(long javaThreadId) {
		Log.info("resumeNativeThread: thread=" + javaThreadId + ", map=" + nativelySuspendedThreads.keySet());
		WeakReference<PageContext> pcRef = nativelySuspendedThreads.remove(javaThreadId);
		if (pcRef == null) {
			Log.warn("resumeNativeThread: thread " + javaThreadId + " not in map!");
			return false;
		}
		PageContext pc = pcRef.get();
		if (pc == null) {
			Log.warn("resumeNativeThread: PageContext for thread " + javaThreadId + " was GC'd!");
			return false;
		}
		Log.info("resumeNativeThread: calling debuggerResume() for thread " + javaThreadId);
		try {
			// Call debuggerResume() via reflection (Lucee7+ method)
			java.lang.reflect.Method resumeMethod = pc.getClass().getMethod("debuggerResume");
			resumeMethod.invoke(pc);
			Log.info("resumeNativeThread: debuggerResume() completed for thread " + javaThreadId);
			return true;
		} catch (NoSuchMethodException e) {
			Log.error("debuggerResume() not available (pre-Lucee7?)");
			return false;
		} catch (Exception e) {
			Log.error("Error calling debuggerResume()", e);
			return false;
		}
	}

	/**
	 * Resume all natively suspended threads.
	 */
	public static void resumeAllNativeThreads() {
		for (Long threadId : nativelySuspendedThreads.keySet()) {
			resumeNativeThread(threadId);
		}
	}

	// ========== Pause methods ==========

	/**
	 * Virtual thread ID for "All Threads" - used when no specific thread is targeted.
	 * Thread ID 0 means "all threads" in DAP, and we also use 1 as an alias for the
	 * "All CFML Threads" entry shown in the VSCode threads panel.
	 * Must match ALL_THREADS_VIRTUAL_ID in NativeLuceeVm.
	 */
	private static final long ALL_THREADS_VIRTUAL_ID = 1;

	/**
	 * Request a thread to pause at the next CFML line.
	 * The thread will suspend cooperatively when it hits the next instrumentation point.
	 * @param threadId The Java thread ID to pause, or 0/1 to pause all threads
	 */
	public static void requestPause(long threadId) {
		if (threadId == 0 || threadId == ALL_THREADS_VIRTUAL_ID) {
			// Pause all - we set a flag that shouldSuspend() will check for any thread
			threadsToPause.put(0L, Boolean.TRUE);
		} else {
			threadsToPause.put(threadId, Boolean.TRUE);
		}
		updateHasSuspendConditions();
		Log.info("Pause requested for thread: " + (threadId == 0 || threadId == ALL_THREADS_VIRTUAL_ID ? "all" : threadId));
	}

	/**
	 * Check if a thread has a pending pause request.
	 * Clears the request after checking (pause is consumed).
	 */
	private static boolean consumePauseRequest(long threadId) {
		// Check for specific thread first, then "pause all"
		if (threadsToPause.remove(threadId) != null) {
			updateHasSuspendConditions();
			return true;
		}
		if (threadsToPause.remove(0L) != null) {
			updateHasSuspendConditions();
			return true;
		}
		return false;
	}

	// ========== Stepping methods ==========

	/**
	 * Start stepping for a thread.
	 * @param threadId The Java thread ID
	 * @param mode The step mode (STEP_INTO, STEP_OVER, STEP_OUT)
	 * @param currentDepth The current stack depth when stepping started
	 */
	public static void startStepping(long threadId, StepMode mode, int currentDepth) {
		steppingThreads.put(threadId, new StepState(mode, currentDepth));
		updateHasSuspendConditions();
		Log.debug("Start stepping: thread=" + threadId + " mode=" + mode + " depth=" + currentDepth);
	}

	/**
	 * Stop stepping for a thread.
	 */
	public static void stopStepping(long threadId) {
		steppingThreads.remove(threadId);
		updateHasSuspendConditions();
	}

	/**
	 * Get the current stack depth for a PageContext.
	 * Uses reflection to get debugger frames.
	 * Only counts frames with line > 0 to match NativeDebugFrame.getNativeFrames() filtering.
	 */
	public static int getStackDepth(PageContext pc) {
		try {
			java.lang.reflect.Method getFrames = pc.getClass().getMethod("getDebuggerFrames");
			Object[] frames = (Object[]) getFrames.invoke(pc);
			if (frames == null || frames.length == 0) return 0;

			// Cache the getLine method on first use
			java.lang.reflect.Method getLine = debuggerFrameGetLineMethod;
			if (getLine == null) {
				getLine = frames[0].getClass().getMethod("getLine");
				debuggerFrameGetLineMethod = getLine;
			}

			// Count only frames with line > 0 (matching getNativeFrames filtering)
			// Frames start with line=0 before first ExecutionLog.start() call
			int count = 0;
			for (Object frame : frames) {
				int line = (int) getLine.invoke(frame);
				if (line > 0) count++;
			}
			return count;
		} catch (Exception e) {
			// Log error - silent failure could cause incorrect step behavior
			Log.error("Error getting stack depth: " + e.getMessage());
			return 0;
		}
	}

	// ========== DebuggerListener interface methods ==========

	/**
	 * Called by Lucee when a thread is about to suspend.
	 * This is invoked on the suspending thread's stack, before it blocks.
	 */
	public static void onSuspend(PageContext pc, String file, int line, String label) {
		long threadId = Thread.currentThread().getId();
		Log.info("onSuspend: thread=" + threadId + " file=" + Config.shortenPath(file) + " line=" + line);

		// Check if we were stepping BEFORE clearing state
		StepState stepState = steppingThreads.remove(threadId);
		boolean wasStepping = (stepState != null);

		// Check if we paused due to user pause request
		boolean wasPaused = pausedThreads.remove(threadId) != null;

		// Check if we hit a breakpoint (breakpoint wins over step/pause)
		boolean hitBreakpoint = hasBreakpoint(file, line);

		// Check if there's a pending exception for this thread (from onException)
		Throwable pendingException = pendingExceptions.remove(threadId);

		// Track the suspended thread so we can resume it later
		// We store PageContext (not PageContextImpl) to avoid class loading cycles
		nativelySuspendedThreads.put(threadId, new WeakReference<>(pc));
		Log.info("onSuspend: added thread " + threadId + " to map, map=" + nativelySuspendedThreads.keySet());

		// Store suspend location for stack trace (needed when no native DebuggerFrames exist)
		// Include the exception if we're suspending due to one
		suspendLocations.put(threadId, new SuspendLocation(file, line, label, pendingException));

		// Fire appropriate callback - exception takes precedence, then breakpoint, then pause, then step
		if (pendingException != null) {
			// Stopped due to uncaught exception
			Consumer<Long> callback = onNativeExceptionCallback;
			if (callback != null) {
				callback.accept(threadId);
			}
		} else if (hitBreakpoint) {
			// Stopped at breakpoint (no label for line breakpoints)
			BiConsumer<Long, String> callback = onNativeSuspendCallback;
			if (callback != null) {
				callback.accept(threadId, null);
			}
		} else if (wasPaused) {
			// Stopped due to user pause request
			Consumer<Long> callback = onNativePauseCallback;
			if (callback != null) {
				callback.accept(threadId);
			}
		} else if (wasStepping) {
			// Stopped due to stepping
			Consumer<Long> callback = onNativeStepCallback;
			if (callback != null) {
				callback.accept(threadId);
			}
		} else {
			// Programmatic breakpoint() call or other suspend - pass the label
			BiConsumer<Long, String> callback = onNativeSuspendCallback;
			if (callback != null) {
				callback.accept(threadId, label);
			}
		}
	}

	/**
	 * Called by Lucee when a thread resumes after suspension.
	 */
	public static void onResume(PageContext pc) {
		long threadId = Thread.currentThread().getId();
		Log.debug("Resume: thread=" + threadId);

		// Remove from suspended threads map and location
		nativelySuspendedThreads.remove(threadId);
		suspendLocations.remove(threadId);
	}

	/**
	 * Check if a DAP client is connected and ready to handle breakpoints.
	 * Uses explicit flag set when DAP client connects, not just callback registration.
	 */
	public static boolean isDapClientConnected() {
		return dapClientConnected;
	}

	/**
	 * Set the DAP client connected state.
	 * Called by DapServer when client connects/disconnects.
	 */
	public static void setDapClientConnected(boolean connected) {
		dapClientConnected = connected;
		if (!connected) {
			onClientDisconnect();
		}
		updateHasSuspendConditions();
		Log.info("DAP client connected: " + connected);
	}

	/**
	 * Clean up state when DAP client disconnects.
	 * Resumes any suspended threads to prevent deadlocks.
	 */
	private static void onClientDisconnect() {
		// Resume any suspended threads so they're not stuck forever
		resumeAllNativeThreads();

		// Clear stepping state
		steppingThreads.clear();

		// Clear pause state
		threadsToPause.clear();
		pausedThreads.clear();

		// Clear pending exceptions
		pendingExceptions.clear();

		// Reset exception settings
		breakOnUncaughtExceptions = false;
		consoleOutput = false;

		// Note: We intentionally keep breakpoints - they'll be inactive
		// since dapClientConnected=false, and will be replaced on next connect

		Log.info("DAP client disconnected - cleanup complete");
	}

	/**
	 * Set whether to break on uncaught exceptions.
	 * Called from DapServer when handling setExceptionBreakpoints request.
	 */
	public static void setBreakOnUncaughtExceptions(boolean enabled) {
		breakOnUncaughtExceptions = enabled;
		updateHasSuspendConditions();
		Log.info("Exception breakpoints: " + (enabled ? "uncaught" : "none"));
	}

	/**
	 * Check if we should break on uncaught exceptions.
	 */
	public static boolean shouldBreakOnUncaughtExceptions() {
		return breakOnUncaughtExceptions && dapClientConnected;
	}

	/**
	 * Set whether to forward System.out/err to DAP client.
	 * Called from DapServer when handling attach request.
	 */
	public static void setConsoleOutput(boolean enabled) {
		consoleOutput = enabled;
		Log.setConsoleOutput(enabled);
	}

	/**
	 * Called by Lucee's DebuggerPrintStream when output is written to System.out/err.
	 * Forwards to DAP client if consoleOutput is enabled.
	 *
	 * @param text The text that was written
	 * @param isStdErr true if stderr, false if stdout
	 */
	public static void onOutput(String text, boolean isStdErr) {
		if (!consoleOutput || !dapClientConnected) {
			return;
		}
		Log.systemOutput(text, isStdErr);
	}

	/**
	 * Called by Lucee when an exception is about to be handled.
	 * Returns true if we should suspend to let the debugger inspect.
	 *
	 * @param pc The PageContext
	 * @param exception The exception
	 * @param caught true if caught by try/catch, false if uncaught
	 * @return true to suspend execution
	 */
	public static boolean onException(PageContext pc, Throwable exception, boolean caught) {
		Log.debug("onException called: caught=" + caught + ", exception=" + exception.getClass().getName() + ", breakOnUncaught=" + breakOnUncaughtExceptions + ", dapConnected=" + dapClientConnected);

		// Log exception to debug console if enabled (both caught and uncaught)
		Log.exception(exception);

		// Only handle uncaught exceptions for now
		if (caught) {
			return false;
		}
		boolean shouldSuspend = shouldBreakOnUncaughtExceptions();
		if (shouldSuspend) {
			// Store exception for this thread - will be consumed in onSuspend
			long threadId = Thread.currentThread().getId();
			pendingExceptions.put(threadId, exception);
		}
		Log.debug("onException returning: " + shouldSuspend);
		return shouldSuspend;
	}

	/**
	 * Called by Lucee's DebuggerExecutionLog.start() on every line.
	 * Checks breakpoints AND stepping state.
	 * Must be fast - this is on the hot path.
	 */
	public static boolean shouldSuspend(PageContext pc, String file, int line) {
		// Fast path - nothing could possibly cause a suspend
		if (!hasSuspendConditions) {
			return false;
		}

		// Check breakpoints with fast bounds rejection
		// Grab local refs - volatile hasSuspendConditions provides memory barrier
		int[] lines = bpLines;
		if (lines.length > 0) {
			// Bounds check - reject 99% of lines instantly
			if (line >= bpMinLine && line <= bpMaxLine) {
				// Line is in range - check for matching line numbers first
				// Only canonicalize file path if we find a line match (rare)
				String[] files = bpFiles;
				String[] conditions = bpConditions;
				String canonFile = null;  // lazy init
				for (int i = 0; i < lines.length; i++) {
					if (lines[i] == line) {
						// Line matches - now check file (canonicalize once)
						if (canonFile == null) {
							// Length check before expensive canonicalization
							if (file.length() > bpMaxPathLen) {
								break;  // file too long, can't match any breakpoint
							}
							canonFile = Config.canonicalizeFileName(file);
						}
						if (files[i].equals(canonFile)) {
							// Hit! Check condition if present
							String condition = conditions[i];
							if (condition != null) {
								return evaluateCondition(pc, condition);
							}
							return true;
						}
					}
				}
			}
		}

		long threadId = Thread.currentThread().getId();

		// Check for pause request (user clicked pause button)
		if (consumePauseRequest(threadId)) {
			pausedThreads.put(threadId, Boolean.TRUE);
			return true;
		}

		// Check stepping state
		StepState stepState = steppingThreads.get(threadId);
		if (stepState == null) {
			return false;
		}

		int currentDepth = getStackDepth(pc);

		switch (stepState.mode) {
			case STEP_INTO:
				// Always stop on next line
				return true;

			case STEP_OVER:
				// Stop when at same or shallower depth
				return currentDepth <= stepState.startDepth;

			case STEP_OUT:
				// Stop when shallower than start depth
				return currentDepth < stepState.startDepth;

			default:
				return false;
		}
	}

	/**
	 * Evaluate a CFML condition expression and return its boolean result.
	 * Returns false if evaluation fails (exception, timeout, etc.).
	 * Uses reflection to call Lucee's Evaluate function to avoid classloader issues.
	 */
	private static boolean evaluateCondition(PageContext pc, String condition) {
		try {
			// Use reflection to call Evaluate.call() through Lucee's classloader
			ClassLoader luceeLoader = pc.getClass().getClassLoader();
			Class<?> evaluateClass = luceeLoader.loadClass("lucee.runtime.functions.dynamicEvaluation.Evaluate");
			java.lang.reflect.Method callMethod = evaluateClass.getMethod("call", PageContext.class, Object[].class);
			Object result = callMethod.invoke(null, pc, new Object[]{ condition });

			// Cast result to boolean using Lucee's Caster
			Class<?> casterClass = luceeLoader.loadClass("lucee.runtime.op.Caster");
			java.lang.reflect.Method toBooleanMethod = casterClass.getMethod("toBoolean", Object.class);
			return (Boolean) toBooleanMethod.invoke(null, result);
		} catch (Exception e) {
			// Condition evaluation failed - don't suspend
			Log.error("Condition evaluation failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Check if a breakpoint exists at the given file and line.
	 */
	public static boolean hasBreakpoint(String file, int line) {
		String canonFile = Config.canonicalizeFileName(file);
		int[] lines = bpLines;
		String[] files = bpFiles;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i] == line && files[i].equals(canonFile)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Get executable line numbers for a file.
	 * Triggers compilation if the file hasn't been compiled yet.
	 * Uses caching based on compileTime to avoid repeated bitmap decoding.
	 *
	 * @param absolutePath The absolute file path
	 * @return Array of line numbers where breakpoints can be set, or empty array if file has errors
	 */
	public static int[] getExecutableLines( String absolutePath ) {
		// Try suspended threads first, then any active PageContext, then create temp
		PageContext pc = getAnyPageContext();
		if ( pc == null ) {
			pc = getAnyActivePageContext();
		}
		if ( pc == null ) {
			pc = createTemporaryPageContext();
		}
		if ( pc == null ) {
			Log.debug( "getExecutableLines: no PageContext available" );
			return new int[0];
		}

		try {
			// Get the webroot from the PageContext's servlet context
			Object servletContext = pc.getClass().getMethod( "getServletContext" ).invoke( pc );
			String webroot = (String) servletContext.getClass().getMethod( "getRealPath", String.class ).invoke( servletContext, "/" );

			// Convert absolute path to relative path by stripping webroot prefix
			String normalizedAbsPath = absolutePath.replace( '\\', '/' ).toLowerCase();
			String normalizedWebroot = webroot.replace( '\\', '/' ).toLowerCase();
			if ( !normalizedWebroot.endsWith( "/" ) ) normalizedWebroot += "/";

			String relativePath;
			if ( normalizedAbsPath.startsWith( normalizedWebroot ) ) {
				relativePath = "/" + absolutePath.substring( webroot.length() ).replace( '\\', '/' );
				// Handle case where webroot didn't have trailing slash
				if ( relativePath.startsWith( "//" ) ) relativePath = relativePath.substring( 1 );
			}
			else {
				// File is outside webroot - can't load it via PageSource
				Log.debug( "getExecutableLines: file outside webroot: " + absolutePath );
				return new int[0];
			}

			// Use reflection for PageContextImpl.getPageSource() - core class not visible to OSGi bundle
			java.lang.reflect.Method getPageSourceMethod = pc.getClass().getMethod( "getPageSource", String.class );
			Object ps = getPageSourceMethod.invoke( pc, relativePath );
			if ( ps == null ) {
				Log.debug( "getExecutableLines: no PageSource for " + absolutePath );
				return new int[0];
			}

			// Load/compile the page via PageSource.loadPage(PageContext, boolean)
			java.lang.reflect.Method loadPageMethod = ps.getClass().getMethod( "loadPage", PageContext.class, boolean.class );
			Object page = loadPageMethod.invoke( ps, pc, false );
			if ( page == null ) {
				Log.debug( "getExecutableLines: failed to load page " + absolutePath );
				return new int[0];
			}

			// Get executable lines from compiled Page class - returns Object[] {compileTime, lines}
			java.lang.reflect.Method getExecLinesMethod = page.getClass().getMethod( "getExecutableLines" );
			Object result = getExecLinesMethod.invoke( page );

			// Handle old Lucee versions that return int[] directly
			if ( result instanceof int[] ) {
				return (int[]) result;
			}

			// New format: Object[] {compileTime (Long), lines (int[] or null)}
			if ( result instanceof Object[] ) {
				Object[] arr = (Object[]) result;
				long compileTime = ( (Long) arr[0] ).longValue();
				int[] lines = (int[]) arr[1];

				// Check cache
				CachedExecutableLines cached = executableLinesCache.get( absolutePath );
				if ( cached != null && cached.compileTime == compileTime ) {
					Log.debug( "getExecutableLines: cache hit for " + absolutePath );
					return cached.lines;
				}

				// Cache miss or stale - update cache
				int[] resultLines = lines != null ? lines : new int[0];
				executableLinesCache.put( absolutePath, new CachedExecutableLines( compileTime, resultLines ) );
				Log.debug( "getExecutableLines: cached " + resultLines.length + " lines for " + absolutePath );
				return resultLines;
			}

			// Unexpected return type
			Log.debug( "getExecutableLines: unexpected return type: " + ( result != null ? result.getClass().getName() : "null" ) );
			return new int[0];
		}
		catch ( NoSuchMethodException e ) {
			// Method only exists in debug-compiled classes - this shouldn't happen in debugger mode
			Log.error( "getExecutableLines: compiled class for [" + absolutePath + "] is missing debug info (should be auto-generated in debugger mode)" );
			return new int[0];
		}
		catch ( java.lang.reflect.InvocationTargetException e ) {
			// Unwrap the real exception from reflection
			Throwable cause = e.getCause();
			Log.debug( "getExecutableLines failed for " + absolutePath + ": " +
				( cause != null ? cause.getClass().getName() + ": " + cause.getMessage() : e.getMessage() ) );
			return new int[0];
		}
		catch ( Exception e ) {
			Log.debug( "getExecutableLines failed for " + absolutePath + ": " + e.getClass().getName() + ": " + e.getMessage() );
			return new int[0];
		}
	}

	/**
	 * Get any active PageContext from running requests.
	 * Used when no thread is suspended but we need a PageContext for compilation.
	 */
	private static PageContext getAnyActivePageContext() {
		try {
			Object engine = lucee.loader.engine.CFMLEngineFactory.getInstance();
			java.lang.reflect.Method getEngineMethod = engine.getClass().getMethod("getEngine");
			Object engineImpl = getEngineMethod.invoke(engine);

			java.lang.reflect.Method getFactoriesMethod = engineImpl.getClass().getMethod("getCFMLFactories");
			@SuppressWarnings("unchecked")
			java.util.Map<String, ?> factoriesMap = (java.util.Map<String, ?>) getFactoriesMethod.invoke(engineImpl);

			for (Object factory : factoriesMap.values()) {
				try {
					java.lang.reflect.Method getActiveMethod = factory.getClass().getMethod("getActivePageContexts");
					@SuppressWarnings("unchecked")
					java.util.Map<Integer, ?> activeContexts = (java.util.Map<Integer, ?>) getActiveMethod.invoke(factory);

					for (Object pc : activeContexts.values()) {
						if (pc instanceof PageContext) {
							return (PageContext) pc;
						}
					}
				} catch (Exception e) {
					// Skip this factory
				}
			}
		} catch (Exception e) {
			Log.debug("getAnyActivePageContext failed: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Create a temporary PageContext for compilation when no active request exists.
	 * Uses CFMLEngineFactory.getInstance().createPageContext() like LSPUtil does.
	 */
	private static PageContext createTemporaryPageContext() {
		try {
			Object engine = lucee.loader.engine.CFMLEngineFactory.getInstance();

			// Find and call createPageContext(File, String, String, String, Cookie[], Map, Map, Map, OutputStream, long, boolean)
			for (java.lang.reflect.Method m : engine.getClass().getMethods()) {
				if (m.getName().equals("createPageContext") && m.getParameterCount() == 11) {
					Class<?>[] params = m.getParameterTypes();
					if (params[0].getName().equals("java.io.File")) {
						java.io.File contextRoot = new java.io.File(".");
						java.io.OutputStream devNull = new java.io.ByteArrayOutputStream();
						Object pc = m.invoke(engine, contextRoot, "localhost", "/", "", null, null, null, null, devNull, -1L, false);
						if (pc instanceof PageContext) {
							return (PageContext) pc;
						}
					}
				}
			}
		} catch (Exception e) {
			Log.debug("createTemporaryPageContext failed: " + e.getMessage());
		}
		return null;
	}

	// ========== Function Breakpoints ==========

	/**
	 * Check if we should suspend on function entry.
	 * Called from Lucee's pushDebuggerFrame via DebuggerListener.onFunctionEntry().
	 * Must be blazing fast - every UDF call hits this.
	 */
	public static boolean onFunctionEntry( PageContext pc, String functionName,
											String componentName, String file, int startLine ) {
		// Fast path - no function breakpoints
		if ( !hasFuncBps || !dapClientConnected ) {
			return false;
		}

		int len = functionName.length();

		// Length bounds check - rejects 99% of calls instantly
		if ( len < funcBpMinLen || len > funcBpMaxLen ) {
			return false;
		}

		// Normalize for case-insensitive matching
		String lowerFunc = functionName.toLowerCase();
		String lowerComp = componentName != null ? componentName.toLowerCase() : null;

		// Check each breakpoint
		String[] names = funcBpNames;
		String[] comps = funcBpComponents;
		String[] conds = funcBpConditions;
		boolean[] wilds = funcBpIsWildcard;

		for ( int i = 0; i < names.length; i++ ) {
			// Check component qualifier first (if specified)
			if ( comps[i] != null && ( lowerComp == null || !lowerComp.equals( comps[i] ) ) ) {
				continue;
			}

			// Check function name match
			boolean match;
			if ( wilds[i] ) {
				// Wildcard: "on*" matches "onRequestStart"
				String prefix = names[i].substring( 0, names[i].length() - 1 );
				match = lowerFunc.startsWith( prefix );
			}
			else {
				match = lowerFunc.equals( names[i] );
			}

			if ( match ) {
				// Check condition if present
				if ( conds[i] != null ) {
					if ( !evaluateCondition( pc, conds[i] ) ) {
						continue;
					}
				}
				Log.info( "Function breakpoint hit: " + functionName +
					( componentName != null ? " in " + componentName : "" ) );
				return true;
			}
		}

		return false;
	}

	/**
	 * Set function breakpoints (replaces all existing).
	 * Called from DapServer.setFunctionBreakpoints().
	 */
	public static void setFunctionBreakpoints( String[] names, String[] conditions ) {
		synchronized ( funcBpLock ) {
			int count = names.length;
			String[] newNames = new String[count];
			String[] newComps = new String[count];
			String[] newConds = new String[count];
			boolean[] newWilds = new boolean[count];

			int minLen = Integer.MAX_VALUE;
			int maxLen = Integer.MIN_VALUE;

			for ( int i = 0; i < count; i++ ) {
				String name = names[i].trim();
				String condition = ( conditions != null && i < conditions.length && conditions[i] != null && !conditions[i].isEmpty() )
									? conditions[i] : null;

				// Parse qualified name: "Component.method" or just "method"
				int dot = name.lastIndexOf( '.' );
				String compName = null;
				String funcName = name;
				if ( dot > 0 ) {
					compName = name.substring( 0, dot ).toLowerCase();
					funcName = name.substring( dot + 1 );
				}

				// Check for wildcard
				boolean isWild = funcName.endsWith( "*" );

				// Store lowercase for case-insensitive matching
				newNames[i] = funcName.toLowerCase();
				newComps[i] = compName;
				newConds[i] = condition;
				newWilds[i] = isWild;

				// Update bounds (for wildcards, use prefix length)
				int effectiveLen = isWild ? funcName.length() - 1 : funcName.length();
				if ( !isWild ) {
					// Exact match: bounds are exact length
					if ( effectiveLen < minLen ) minLen = effectiveLen;
					if ( effectiveLen > maxLen ) maxLen = effectiveLen;
				}
				else {
					// Wildcard: any length >= prefix is possible
					if ( effectiveLen < minLen ) minLen = effectiveLen;
					maxLen = Integer.MAX_VALUE; // can't bound max for wildcards
				}

				Log.info( "Function breakpoint: " + name +
					( compName != null ? " (component: " + compName + ")" : "" ) +
					( isWild ? " (wildcard)" : "" ) +
					( condition != null ? " condition: " + condition : "" ) );
			}

			funcBpNames = newNames;
			funcBpComponents = newComps;
			funcBpConditions = newConds;
			funcBpIsWildcard = newWilds;
			funcBpMinLen = count > 0 ? minLen : Integer.MAX_VALUE;
			funcBpMaxLen = count > 0 ? maxLen : Integer.MIN_VALUE;
		}
		hasFuncBps = funcBpNames.length > 0;
		updateHasSuspendConditions();
		Log.info( "Function breakpoints set: " + funcBpNames.length );
	}

	/**
	 * Clear all function breakpoints.
	 */
	public static void clearFunctionBreakpoints() {
		synchronized ( funcBpLock ) {
			funcBpNames = new String[0];
			funcBpComponents = new String[0];
			funcBpConditions = new String[0];
			funcBpIsWildcard = new boolean[0];
			funcBpMinLen = Integer.MAX_VALUE;
			funcBpMaxLen = Integer.MIN_VALUE;
		}
		hasFuncBps = false;
		updateHasSuspendConditions();
		Log.debug( "Function breakpoints cleared" );
	}
}
