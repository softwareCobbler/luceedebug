package luceedebug;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.sun.jdi.*;

import luceedebug.strong.DapBreakpointID;
import luceedebug.strong.JdwpThreadID;
import luceedebug.strong.CanonicalServerAbsPath;
import luceedebug.strong.RawIdePath;

public interface ILuceeVm {
    public void registerStepEventCallback(Consumer<JdwpThreadID> cb);
    public void registerBreakpointEventCallback(BiConsumer<JdwpThreadID, DapBreakpointID> cb);

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

    public ThreadReference[] getThreadListing();
    public IDebugFrame[] getStackTrace(long jdwpThreadID);
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

    public void continue_(long jdwpThreadID);

    public void continueAll();

    public void stepIn(long jdwpThreadID);
    public void stepOver(long jdwpThreadID);
    public void stepOut(long jdwpThreadID);


    public void clearAllBreakpoints();

    public String dump(int dapVariablesReference);
    public String dumpAsJSON(int dapVariablesReference);

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
}
