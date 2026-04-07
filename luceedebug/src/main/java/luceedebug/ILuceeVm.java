package luceedebug;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import luceedebug.strong.DapBreakpointID;
import luceedebug.strong.CanonicalServerAbsPath;
import luceedebug.strong.RawIdePath;

public interface ILuceeVm {
    /**
     * Register callback for step events.
     * Called with thread ID when a step completes.
     */
    public void registerStepEventCallback(Consumer<Long> cb);

    /**
     * Register callback for JDWP breakpoint events.
     * Called with thread ID and breakpoint ID when a JDWP breakpoint is hit.
     * For NativeLuceeVm, this is unused (use registerNativeBreakpointEventCallback instead).
     */
    public void registerBreakpointEventCallback(BiConsumer<Long, DapBreakpointID> cb);

    /**
     * Register callback for native breakpoint events (Lucee7+).
     * Called with Java thread ID and optional label when a thread hits a native breakpoint.
     * Label is non-null for programmatic breakpoint("label") calls, null otherwise.
     */
    public void registerNativeBreakpointEventCallback(BiConsumer<Long, String> cb);

    public static class BreakpointsChangedEvent {
        IBreakpoint[] newBreakpoints = new IBreakpoint[0];
        IBreakpoint[] changedBreakpoints = new IBreakpoint[0];
        int[] deletedBreakpointIDs = new int[0];

        public static BreakpointsChangedEvent justChanges(IBreakpoint[] changes) {
            var result = new BreakpointsChangedEvent();
            result.changedBreakpoints = changes;
            return result;
        }
    }
    public void registerBreakpointsChangedCallback(Consumer<BreakpointsChangedEvent> cb);

    public ThreadInfo[] getThreadListing();
    public IDebugFrame[] getStackTrace(long threadID);
    public IDebugEntity[] getScopes(long frameID);

    /**
     * note we return an array, for a single ID
     * The ID might be itself for an array or object, with many nested variables
     *
     * named and indexed are pretty much the same thing for CF purposes ... though we do report that something "has" indexed variables if it is an Array,
     * so we'll need to respect frontend requests for those indexed variables.
     */
    public IDebugEntity[] getVariables(long ID); // both named and indexed
    public IDebugEntity[] getNamedVariables(long ID);
    public IDebugEntity[] getIndexedVariables(long ID);

    public IBreakpoint[] bindBreakpoints(RawIdePath idePath, CanonicalServerAbsPath serverAbsPath, int[] lines, String[] exprs);

    public void continue_(long threadID);

    public void continueAll();

    public void stepIn(long threadID);
    public void stepOver(long threadID);
    public void stepOut(long threadID);

    /**
     * Request a thread to pause at the next CFML line.
     * In native mode, this is cooperative - the thread pauses at the next instrumentation point.
     * @param threadID The thread ID to pause
     */
    public void pause(long threadID);


    public void clearAllBreakpoints();

    public String dump(int dapVariablesReference);
    public String dumpAsJSON(int dapVariablesReference);
    public String getMetadata(int dapVariablesReference);
    public String getApplicationSettings();

    /**
     * Get completion suggestions for the debug console.
     * @param frameId The stack frame ID for context (0 for global scope)
     * @param partialExpr The partial expression to complete (e.g., "local.fo" or "variables.")
     * @return Array of CompletionItems
     */
    public org.eclipse.lsp4j.debug.CompletionItem[] getCompletions(int frameId, String partialExpr);

    public String[] getTrackedCanonicalFileNames();
    /**
     * array of tuples
     * [original path, transformed path][]
     **/
    public String[][] getBreakpointDetail();

    /**
     * @return String | null
     */
    public String getSourcePathForVariablesRef(int variablesRef);

    public Either<String, Either<ICfValueDebuggerBridge, String>> evaluate(int frameID, String expr);

    /**
     * Set a variable value.
     * @param variablesReference The parent container's variablesReference
     * @param name The variable name within the container
     * @param value The new value as a string expression
     * @param frameId The frame ID for context (used to get PageContext)
     * @return Either an error message (Left), or the new value as ICfValueDebuggerBridge or String (Right)
     */
    public Either<String, Either<ICfValueDebuggerBridge, String>> setVariable(long variablesReference, String name, String value, long frameId);

    /**
     * Register callback for exception events (native mode only).
     * Called with Java thread ID when a thread stops due to an uncaught exception.
     */
    public void registerExceptionEventCallback(java.util.function.Consumer<Long> cb);

    /**
     * Register callback for pause events (native mode only).
     * Called with Java thread ID when a thread stops due to user pause request.
     */
    public void registerPauseEventCallback(java.util.function.Consumer<Long> cb);

    /**
     * Get the exception that caused a thread to suspend.
     * Returns null if the thread is not suspended due to an exception.
     * @param threadId The Java thread ID
     * @return The exception, or null
     */
    public Throwable getExceptionForThread(long threadId);
}
