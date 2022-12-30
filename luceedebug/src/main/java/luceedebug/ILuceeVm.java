package luceedebug;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.sun.jdi.*;

public interface ILuceeVm {
    public void registerStepEventCallback(Consumer</*threadID*/Long> cb);
    public void registerBreakpointEventCallback(BiConsumer</*threadID*/Long, /*bpID*/Integer> cb);

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
     */
    public IDebugEntity[] getVariables(long ID);

    public IBreakpoint[] bindBreakpoints(OriginalAndTransformedString absPath, int[] lines);

    public void continue_(long jdwpThreadID);

    public void continueAll();

    public void stepIn(long jdwpThreadID);
    public void stepOver(long jdwpThreadID);
    public void stepOut(long jdwpThreadID);


    public void clearAllBreakpoints();

    public String[] getTrackedCanonicalFileNames();
    /**
     * array of tuples
     * [original path, transformed path][]
     **/
    public String[][] getBreakpointDetail();
}
