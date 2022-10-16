package luceedebug.coreinject;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.VirtualMachine;

import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;

import lucee.runtime.type.Collection;
import lucee.runtime.PageContext;
import lucee.runtime.type.scope.Scope;
import luceedebug.DapServer;
import lucee.runtime.PageContext;
import lucee.runtime.PageContextImpl; // compiles fine, but IDE says it doesn't resolve

import luceedebug.*;

public class DebugManager {

    /**
     * called from instrumented PageContextImpl
     */
    @SuppressWarnings("unused")
    static public void spawnWorker(String jdwpHost, int jdwpPort, String cfHost, int cfPort) {

        // do we need to touch DebugManager from <clinit> of PageContextImpl?
        // then everything in DebugManager needs to be static
        final String threadName = "luceedebug-worker";
        var thread = new Thread(new Runnable() {
            public void run() {
                VirtualMachine vm = jdwpSelfConnect(jdwpHost, jdwpPort); // jdwpPort
                CfVm cfvm = new CfVm(vm);
                DapServer.createForSocket(cfvm, cfHost, cfPort);
            }
        }, threadName);

        thread.start();
    }

    static AttachingConnector getConnector() {
        var vmm = Bootstrap.virtualMachineManager();
        var attachingConnectors = vmm.attachingConnectors();
        for (var c : attachingConnectors) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) {
                return c;
            }
        }
        System.out.println("no socket attaching connector?");
        System.exit(1);
        return null;
    }

    static VirtualMachine jdwpSelfConnect(String host, int port) {
        var connector = getConnector();
        var args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));
        try {
            return connector.attach(args);
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    private static Cleaner cleaner = Cleaner.create();

    private static WeakHashMap<Thread, Stack<DebugFrame>> cfStackByThread = new WeakHashMap<>();
    private static HashMap<Long, DebugFrame> frameTracker = new HashMap<>();
    
    /**
     * an entity represents a Java object that itself is a CF object
     * There is exactly one unique Java object per unique CF object
     * These are nameless values floating in memory, and we wrap them in objects that themselves have IDs
     * Asking for an object (or registering the same object again) should produce the same ID for the same object
     */
    private static ValTracker valTracker = new ValTracker(cleaner);

    /**
     * An entityRef is a named reference to an entity. There can be many entityRefs for a single entity.
     * Each entity ref has a unique ID. e.g. `local.foo` and `variables.foo` and `arguments.bar` may all point to the same entity, but they
     * are different entityRefs. Once created, it is not possible to look them up by object identity, they must be looked up by ID.
     */
    private static RefTracker<CfEntityRef> refTracker = new RefTracker<>(valTracker, cleaner);

    public static interface CfStepCallback {
        void call(Thread thread, int distanceToJvmFrame);
    }

    private static CfStepCallback didStepCallback = null;
    public static void registerCfStepHandler(CfStepCallback cb) {
        didStepCallback = cb;
    }
    private static void notifyStep(Thread thread, int distanceToJvmFrame) {
        if (didStepCallback != null) {
            didStepCallback.call(thread, distanceToJvmFrame + 1);
        }
    }

    synchronized public static IDebugEntity[] getScopesForFrame(long frameID) {
        DebugFrame frame = frameTracker.get(frameID);
        System.out.println("Get scopes for frame, frame was " + frame);
        if (frame == null) {
            return new IDebugEntity[0];
        }
        return frame.getScopes();
    }

    synchronized public static IDebugEntity[] getVariables(long id) {
        RefTracker.Wrapper_t<CfEntityRef> cfEntityRef = refTracker.maybeGetFromId(id);
        if (cfEntityRef == null) {
            return new IDebugEntity[0];
        }
        return cfEntityRef.wrapped.getAsDebugEntity();
    }

    synchronized public static IDebugFrame[] getCfStack(Thread thread) {
        Stack<DebugFrame> stack = cfStackByThread.get(thread);
        if (stack == null) {
            System.out.println("getCfStack called, frames was null, frames is " + cfStackByThread + ", passed thread was " + thread);
            return new DebugFrame[0];
        }

        ArrayList<DebugFrame> result = new ArrayList<>();
        result.ensureCapacity(stack.size());

        // go backwards, "most recent first"
        for (int i = stack.size() - 1; i >= 0; --i) {
            DebugFrame frame = stack.get(i);
            if (frame.getLine() == 0) {
                // ???? should we just not push such frames on the stack?
                // what does this mean?
                continue;
            }
            else {
                result.add(frame);
            }
        }

        return result.toArray(new DebugFrame[result.size()]);
    }

    static class CfStepRequest {
        // same enum values as jdwp / jvmti
        static final int STEP_INTO = 0;
        static final int STEP_OVER = 1;
        static final int STEP_OUT = 2;

        final long __debug__startTime = System.nanoTime();
        long __debug__stepOverhead = 0;
        int __debug__steps = 0;

        final int startDepth;
        final int type;

        CfStepRequest(int startDepth, int type) {
            this.startDepth = startDepth;
            this.type = type;
        }

        public String toString() {
            var s_type = type == STEP_INTO ? "into" : type == STEP_OVER ? "over" : "out";
            return "(stepRequest // startDepth=" + startDepth + " type=" + s_type + ")";
        }
    };

    static void registerStepRequest(Thread thread, int type) {
        DebugFrame frame = getTopmostFrame(thread);
        if (frame == null) {
            System.out.println("[luceedebug] registerStepRequest found no frames");
            System.exit(1);
            return;
        }

        switch (type) {
            case CfStepRequest.STEP_INTO:
            case CfStepRequest.STEP_OVER:
            case CfStepRequest.STEP_OUT: {
                stepRequestByThread.put(thread, new CfStepRequest(frame.getDepth(), type));
                return;
            }
            default: {
                System.out.println("[luceedebug] bad step type");
                System.exit(1);
                return;
            }
        }
    }

    // This holds strongrefs to Thread objects, but requests should be cleared out after their completion
    // It doesn't make sense to have a step request for thread that would otherwise be reclaimable but for our reference to it here
    private static ConcurrentHashMap<Thread, CfStepRequest> stepRequestByThread = new ConcurrentHashMap<>();

    static public void clearStepRequest(Thread thread) {
        stepRequestByThread.remove(thread);
    }

    /**
     * The amount of code between `notifyStep` and returning to the caller must be kept at a minimum
     * Every bytecode instruction between `notifyStep` and returning to
     * the next instruction in the object represented by `stepOccuredInThisInstance` is a jdwp event that we will need to handle
     * 
     * The instance we're stepping in must be the first object reached of that class type between this java frame and the instance we're stepping in
     * This is pretty trivial to guarantee -- the JVM stack should look something like
     * 
     * step (xdebugger) <-- current JVM frame
     * step (PageContextImpl)
     * step (PageContext) (note: abstract, probably not on stack)
     * {call, udfCall} (stepping object) <-- pop back here is the actual step we're interested in
     * 
     * We would use `stepOccuredInThisInstance` rather than `stepOccuredInThisKlass`,
     * but we don't get notified of the "current this instance identity" in a jdwp step event, only the "curent class"
     */
    public static void step(int distanceToActualFrame, int lineNumber) {
        long start = System.nanoTime();
        Thread currentThread = Thread.currentThread();
        
        DebugFrame frame = maybeUpdateTopmostFrame(currentThread, lineNumber);
        if (frame == null) {
            // ? caller probably shouldn't have called
            return;
        }

        CfStepRequest request = stepRequestByThread.get(currentThread);
        if (request == null) {
            return;
        }
        else {
            request.__debug__steps++;
            maybeNotifyOfStepCompletion(currentThread, frame, request, distanceToActualFrame + 1, start);
        }
    }

    private static void maybeNotifyOfStepCompletion(Thread currentThread, DebugFrame frame, CfStepRequest request, int distanceToActualFrame, long start) {
        if (request.type == CfStepRequest.STEP_INTO) {
            // step in, every step is a valid step
            clearStepRequest(currentThread);
            notifyStep(currentThread, distanceToActualFrame + 1);
        }
        else if (request.type == CfStepRequest.STEP_OVER) {
            if (frame.getDepth() > request.startDepth) {
                long end = System.nanoTime();
                request.__debug__stepOverhead += (end - start);
                return;
            }
            else {
                long end = System.nanoTime();
                double elapsed_ms = (end - request.__debug__startTime) / 1e6;
                double stepsPerMs = request.__debug__steps / elapsed_ms;

                System.out.println("  currentframedepth=" + frame.getDepth() + ", startframedepth=" + request.startDepth + ", notifying native of step occurence...");
                System.out.println("    " + request.__debug__steps + " cf steps in " + elapsed_ms + "ms for " + stepsPerMs + " steps/ms, overhead was " + (request.__debug__stepOverhead / 1e6) + "ms");

                clearStepRequest(currentThread);
                notifyStep(currentThread, distanceToActualFrame + 1);
            }
        }
        else if (request.type == CfStepRequest.STEP_OUT) {
            if (frame.getDepth() >= request.startDepth) {
                // stepping out, we need to have popped a frame to notify
                return;
            }
            else {
                clearStepRequest(currentThread);
                notifyStep(currentThread, distanceToActualFrame + 1);
            }
        }
        else {
            // unreachable
        }
    }

    private static DebugFrame maybeUpdateTopmostFrame(Thread thread, int lineNumber) {
        DebugFrame frame = getTopmostFrame(thread);
        if (frame == null) {
            return null;
        }
        frame.setLine(lineNumber);
        return frame;
    }

    private static DebugFrame getTopmostFrame(Thread thread) {
        Stack<DebugFrame> stack = cfStackByThread.get(thread);
        if (stack == null) {
            return null;
        }
        return stack.lastElement();
    }

    /**
     * distanceToActualFrame would be zero from within the actual frame,
     * then 1 from one call deep, and 2 for 2 calls deep (probably we are 2), etc.
     * each caller bumps it until we get here
     */
    public static void pushCfFrame(PageContext pageContext, String sourceFilePath, int distanceToActualFrame) {
        Thread currentThread = Thread.currentThread();

        Stack<DebugFrame> stack = cfStackByThread.get(currentThread);

        // The null case means "fresh stack", this is the first frame
        // Frame length shouldn't ever be zero, we should tear it down when it hits zero
        if (stack == null || stack.size() == 0) {
            Stack<DebugFrame> list = new Stack<>();
            cfStackByThread.put(currentThread, list);
            stack = list;
        }

        final int depth = stack.size(); // first frame is frame 0, and prior to pushing the first frame the stack is length 0; next frame is frame 1, and prior to pushing it the stack is of length 1, ...
        final DebugFrame frame = new DebugFrame(sourceFilePath, depth, valTracker, refTracker, pageContext);

        stack.push(frame);

        // if (stepRequestByThread.containsKey(currentThread)) {
        //     System.out.println("pushed frame during active step request:");
        //     System.out.println("  " + frame.getName() + " @ " + frame.getSourceFilePath() + ":" + frame.getLine());
        // }

        frameTracker.put(frame.getId(), frame);
    }

    synchronized public static void popCfFrame() {
        Thread currentThread = Thread.currentThread();
        Stack<DebugFrame> maybeNull_frameListing = cfStackByThread.get(currentThread);

        if (maybeNull_frameListing == null) {
            // error case, maybe throw
            // we should not be popping from a non-existent thing
            return;
        }

        if (maybeNull_frameListing.size() > 0) {
            DebugFrame frame = maybeNull_frameListing.pop();
            frameTracker.remove(frame.getId());

            // if (stepRequestByThread.containsKey(currentThread)) {
            //     System.out.println("popped frame during active step request:");
            //     System.out.println("  " + frame.sourceFilePath + ":" + frame.line);
            // }
        }

        if (maybeNull_frameListing.size() == 0) {
            // we popped the last frame, so we destroy the whole stack
            cfStackByThread.remove(currentThread);
        }
    }
}
