package luceedebug.coreinject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import luceedebug.*;

public class CfVm implements ICfVm {
    // This is a key into a map stored on breakpointRequest objects; the value should always be of Integer type
    // "step finalization" breakpoints will not have this, so lookup against it will yield null
    final static private String LUCEEDEBUG_BREAKPOINT_ID = "luceedebug-breakpoint-id";

    private final VirtualMachine vm_;

    private static class ThreadMap {
        // leaks, need a cleaner for the WeakRef
        private final ConcurrentHashMap</*jdwpID for thread*/Long, WeakReference<Thread>> threadByJdwpId = new ConcurrentHashMap<>();
        private final WeakHashMap<Thread, ThreadReference> threadRefByThread = new WeakHashMap<>();

        public Thread getThreadByJdwpId(long jdwpId) {
            var weakRef = threadByJdwpId.get(jdwpId);
            if (weakRef == null) {
                return null;
            }
            else {
                return weakRef.get();
            }
        }

        private Thread getThreadByJdwpIdOrFail(long id) {
            var thread = getThreadByJdwpId(id);
            if (thread != null) {
                return thread;
            }
            System.out.println("[luceedebug] couldn't find thread with id '" + id + "'");
            System.exit(1);
            return null;
        }

        public ThreadReference getThreadRefByThread(Thread thread) {
            return threadRefByThread.get(thread);
        }

        public ThreadReference getThreadRefByThreadOrFail(Thread thread) {
            var result = getThreadRefByThread(thread);
            if (result != null) {
                return result;
            }
            System.out.println("[luceedebug] couldn't find thread reference for thread " + thread );
            System.exit(1);
            return null;
        }

        public ThreadReference getThreadRefByJdwpIdOrFail(long jdwpID) {
            return getThreadRefByThreadOrFail(getThreadByJdwpIdOrFail(jdwpID));
        }
        
        public void register(Thread thread, ThreadReference threadRef) {
            threadByJdwpId.put(threadRef.uniqueID(), new WeakReference<>(thread));
            threadRefByThread.put(thread, threadRef);
        }

        public void unregister(ThreadReference threadRef) {
            var thread = getThreadByJdwpId(threadRef.uniqueID());
            threadByJdwpId.remove(threadRef.uniqueID());
            if (thread != null) {
                threadRefByThread.remove(thread);
            }
        }
    }

    private static class ReplayableCfBreakpointRequest {
        final int line;
        final int id;
        final BreakpointRequest maybeNull_jdwpBreakpointRequest;

        ReplayableCfBreakpointRequest(int line, int id) {
            this.line = line;
            this.id = id;
            this.maybeNull_jdwpBreakpointRequest = null;
        }

        ReplayableCfBreakpointRequest(int line, int id, BreakpointRequest jdwpBreakpointRequest) {
            this.line = line;
            this.id = id;
            this.maybeNull_jdwpBreakpointRequest = jdwpBreakpointRequest;
        }

        static int[] getLines(ArrayList<ReplayableCfBreakpointRequest> vs) {
            return vs.stream().map(v -> v.line).mapToInt(v -> v).toArray();
        }

        static int[] getIDs(ArrayList<ReplayableCfBreakpointRequest> vs) {
            return vs.stream().map(v -> v.id).mapToInt(v -> v).toArray();
        }

        static List<BreakpointRequest> getJdwpRequests(ArrayList<ReplayableCfBreakpointRequest> vs) {
            return vs
                .stream()
                .filter(v -> v.maybeNull_jdwpBreakpointRequest != null)
                .map(v -> v.maybeNull_jdwpBreakpointRequest)
                .collect(Collectors.toList());
        }

        static BpLineAndId[] getLineInfo(ArrayList<ReplayableCfBreakpointRequest> vs) {
            return vs
                .stream()
                .map(v -> new BpLineAndId(v.line, v.id))
                .toArray(size -> new BpLineAndId[size]);
        }
    }

    private final ThreadMap threadMap_ = new ThreadMap();
    private final AsyncWorker asyncWorker_ = new AsyncWorker();
    private final ConcurrentHashMap</*sourceAbsPath*/ String, ArrayList<ReplayableCfBreakpointRequest>> replayableBreakpointRequestsByAbsPath_ = new ConcurrentHashMap<>();
    private final ConcurrentHashMap</*absPath*/ String, KlassMap> klassMap_ = new ConcurrentHashMap<>();
    private long JDWP_WORKER_CLASS_ID = 0;
    private ThreadReference JDWP_WORKER_THREADREF = null;

    private final JdwpStaticCallable jdwp_getThread;

    private static class JdwpStaticCallable {
        public final ClassType classType;
        public final Method method;
        public JdwpStaticCallable(ClassType classType, Method method) {
            this.classType = classType;
            this.method = method;
        }
    }

    private static class JdwpWorker {
        static void touch() {
            // Just load this class.
            // Intent is to be in the caller's class loader.
        }

        private static void jdwp_stays_suspended_in_this_method_as_a_worker() {
            @SuppressWarnings("unused")
            int x = 0; // set bp here (bytecode index 0), then we'll do jdwp work on the thread this paused on
            x++;
        }

        static void spawnThreadForJdwpToSuspend() {
            new Thread(new Runnable() {
                public void run() {
                    JdwpWorker.jdwp_stays_suspended_in_this_method_as_a_worker();
                }
            }).start();
        }

        static ConcurrentHashMap<Long, Thread> threadBuffer_ = new ConcurrentHashMap<>();
        static AtomicLong threadBufferId_ = new AtomicLong();

        /**
         * call via jdwp, when the caller has only a jdwp ThreadReference
         * it places the actual thread object into a buffer, returning the key to retrieve it from that buffer
         * This allows us to grab the actual Thread the ThreadReference is referencing
         */
        @SuppressWarnings("unused") // only called indirectly, via jdwp `invokeMethod`
        static long jdwp_getThread(Thread thread) {
            long nextId = threadBufferId_.incrementAndGet();
            threadBuffer_.put(nextId, thread);
            return nextId;
        }

        /**
         * given a key from jdwp_getThread, return the actual results
         */
        static Thread jdwp_getThreadResult(long id) {
            var thread = threadBuffer_.get(id);
            threadBuffer_.remove(id);
            return thread;
        }

        private static volatile boolean ack = false;

        /**
         * we expect to do this exactly once per jvm startup,
         * so it shouldn't be too wasteful
         */
        static void spinWaitForJdwpBpToSuspendWorkerThread() {
            while (!ack);
        }

        static void notifyJdwpSuspendedWorkerThread() {
            ack = true;
        }
    }

    private void bootThreadTracking() {
        final var threadStartRequest = vm_.eventRequestManager().createThreadStartRequest();
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        threadStartRequest.enable();
        initCurrentThreadListing();

        final var threadDeathRequest = vm_.eventRequestManager().createThreadDeathRequest();
        threadDeathRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        threadDeathRequest.enable();
    }

    private void bootClassTracking() {
        final var pageRef = vm_.classesByName("lucee.runtime.Page");

        if (pageRef.size() == 0) {
            var request = vm_.eventRequestManager().createClassPrepareRequest();
            request.addClassFilter("lucee.runtime.Page");
            request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
            request.setEnabled(true);
        }
        else if (pageRef.size() == 1) {
            // we hoped it would be this easy, but when we initialize CfVm, lucee.runtime.Page is probably not loaded yet
            bootClassTracking(pageRef.get(0));
        }
        else {
            System.out.println("[luceedebug] Expected 0 or 1 ref for class with name 'lucee.runtime.Page', but got " + pageRef.size());
            System.exit(1);
        }
    }

    private void bootClassTracking(ReferenceType lucee_runtime_Page) {
        final var classPrepareRequest = vm_.eventRequestManager().createClassPrepareRequest();

        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        classPrepareRequest.addClassFilter(lucee_runtime_Page);
        classPrepareRequest.enable();

        final var classUnloadRequest = vm_.eventRequestManager().createClassUnloadRequest();
        classUnloadRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
    }

    private JdwpStaticCallable bootThreadWorker() {
        JdwpWorker.touch();

        final String className = "luceedebug.coreinject.CfVm$JdwpWorker";
        final var refs = vm_.classesByName(className);
        if (refs.size() != 1) {
            System.out.println("Expected 1 ref for class " + className + " but got " + refs.size());
            System.exit(1);
        }

        final var refType = refs.get(0);

        Method jdwp_stays_suspended_in_this_method_as_a_worker = null;
        Method jdwp_getThread = null;
        for (var method : refType.methods()) {
            if (method.name().equals("jdwp_stays_suspended_in_this_method_as_a_worker")) {
                jdwp_stays_suspended_in_this_method_as_a_worker = method;
            }
            if (method.name().equals("jdwp_getThread")) {
                jdwp_getThread = method;
            }
        }

        if (jdwp_stays_suspended_in_this_method_as_a_worker == null) {
            System.out.println("Couldn't find helper method 'jdwp_stays_suspended_in_this_method_as_a_worker'");
            System.exit(1);
        }
        if (jdwp_getThread == null) {
            System.out.println("Couldn't find helper method 'jdwp_getThread'");
            System.exit(1);
        }

        JDWP_WORKER_CLASS_ID = refType.classObject().uniqueID();

        var bpRequest = vm_.eventRequestManager().createBreakpointRequest(jdwp_stays_suspended_in_this_method_as_a_worker.locationOfCodeIndex(0));
        bpRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bpRequest.enable();
        // this should spawn a thread, and hit a breakpoint in that thread
        JdwpWorker.spawnThreadForJdwpToSuspend();
        // breakpoint event handler knows about this, and should acknowledge
        // receipt of a breakpoint in this class
        // After this is complete, `JDWP_WORKER_THREADREF` is a thread we can invoke methods on over JDWP
        JdwpWorker.spinWaitForJdwpBpToSuspendWorkerThread();

        return new JdwpStaticCallable(((ClassType)refType.classObject().reflectedType()), jdwp_getThread);
    }

    public CfVm(VirtualMachine vm) {
        this.vm_ = vm;
        this.asyncWorker_.start();
        
        initEventPump();

        jdwp_getThread = bootThreadWorker();

        bootClassTracking();

        bootThreadTracking();

        DebugManager.registerCfStepHandler((thread, distanceToFrame) -> {
            final var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);
            final var done = new AtomicBoolean(false);

            //
            // Have to do this on a seperate thread in order to suspend the supplied thread,
            // which by current design will always be the current thread.
            //
            // Weird, we take a `thread` argument, but we always have the caller passing in its current thread.
            // And the caller's current thread is the same as our current thread.
            //
            // Maybe it is good that we support either / or
            // (i.e. the passed in thread may or may not be the current thread, we should 'just work' in either case)
            //
            asyncWorker_.queueWork(() -> {
                try {
                    threadRef.suspend();
                    // "current location in frame + 3" is expected to be immediately after `invokeVirtual`
                    // which we have magic knowledge about. The design is that the cf frame is currently on an `invokeVirtual`
                    // instruction, which is how we got here. We want to resume the thread but then immediately stop after
                    // returning from the invokeVirtual. Using jdwp breakpoints in this way, instead of
                    // the perhaps more obvious jdwp step-notifications approach, avoids dropping the jvm into interpreted mode.
                    final var frame = threadRef.frame(distanceToFrame);
                    final var location = frame.location().method().locationOfCodeIndex(frame.location().codeIndex() + 3);

                    final var bp = vm_.eventRequestManager().createBreakpointRequest(location);
                    bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    bp.addCountFilter(1);
                    bp.setEnabled(true);
                    
                    steppingState = SteppingState.finalizingViaAwaitedBreakpoint;

                    continue_(threadRef);
                    done.set(true);
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            });

            // We'll get here, and the thread may or may not be suspended yet.
            // But, `done` will not be true until the work is done.
            // The worker will suspend *this* thread, then do stuff, and then unsuspend it.
            // After unspending it, the worker will set done=true and we will be unblocked
            var start = System.nanoTime();
            while (!done.get()) {
                // 
                // Spin / poll
                // This will happen pretty often (every succesful DAP step{in,out,over} request),
                // maybe we want some kind of monitor.
                //
                // We expect to spin for about ~5-10ms
                //
                // The worker queue is generally expected to always be empty,
                // so we shouldn't have to wait long after we schedule the work for the work to begin.
                //
                // We poll at a granularity of 1ms which shouldn't introduce a noticeable lag,
                // and avoids 5-10ms of wasteful "pure spin" cpu work.
                //
                // Effectively this is "check if we're done every millisecond, probably at most around 10 times"
                //
                try {
                    Thread.sleep(1);
                }
                catch (InterruptedException e) {
                    // discard ?...
                }
            }
            var end = System.nanoTime();
            System.out.println("Spin wait on step completition handler took " + (((end -start) / 1e6) + "ms"));
        });
    }

    /**
     * Our steps are different than jdwp step events. We don't use jdwp step events because
     * they tend to (always? not sure) drop the jvm into interpreted mode, with associated perf degredation.
     * `isStepping=true` means "the next breakpoint we hit actually implies a completed DAP step event, rather
     * than a user-defined breakpoint event"
     */
    private static enum SteppingState { none, stepping, finalizingViaAwaitedBreakpoint }
    private SteppingState steppingState = SteppingState.none;
    private Consumer</*jdwp threadID*/Long> stepEventCallback = null;
    private BiConsumer</*jdwp threadID*/ Long, /*breakpoint ID*/ Integer> breakpointEventCallback = null;
    private Consumer<BreakpointsChangedEvent> breakpointsChangedCallback = null;

    public void registerStepEventCallback(Consumer<Long> cb) {
        stepEventCallback = cb;
    }

    public void registerBreakpointEventCallback(BiConsumer<Long, Integer> cb) {
        breakpointEventCallback = cb;
    }

    public void registerBreakpointsChangedCallback(Consumer<BreakpointsChangedEvent> cb) {
        this.breakpointsChangedCallback = cb;
    }

    private void initEventPump() {
        new java.lang.Thread(new Runnable() {
            public void run() {
                try {
                    while (true) {
                        var eventSet = vm_.eventQueue().remove();
                        for (var event : eventSet) {
                            if (event instanceof ThreadStartEvent) {
                                handleThreadStartEvent((ThreadStartEvent) event);
                            }
                            else if (event instanceof ThreadDeathEvent) {
                                handleThreadDeathEvent((ThreadDeathEvent) event);
                            }
                            else if (event instanceof ClassPrepareEvent) {
                                handleClassPrepareEvent((ClassPrepareEvent) event);
                            }
                            else if (event instanceof BreakpointEvent) {
                                handleBreakpointEvent((BreakpointEvent) event);
                            }
                            else {
                                System.out.println("Unexpected jdwp event " + event);
                                System.exit(1);
                            }
                        }
                    }
                }
                catch (InterruptedException e) {
                    // Maybe we want to handle this differently?
                    e.printStackTrace();
                    System.exit(1);
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }).start();
    }

    private void initCurrentThreadListing() {
        for (var threadRef : vm_.allThreads()) {
            trackThreadReference(threadRef);
        }
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void trackThreadReference(ThreadReference threadRef) {
        asyncWorker_.queueWork(() -> {
            try {
                final List<? extends Value> args = Arrays.asList(threadRef);
                final var v = (LongValue) jdwp_getThread.classType.invokeMethod(
                    JDWP_WORKER_THREADREF,
                    jdwp_getThread.method,
                    args,
                    ObjectReference.INVOKE_SINGLE_THREADED
                );

                final long key = v.value();
                final Thread thread = JdwpWorker.jdwp_getThreadResult(key);
                threadMap_.register(thread, threadRef);
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        });
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void untrackThreadReference(ThreadReference threadRef) {
        threadMap_.unregister(threadRef);
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void trackClassRef(ReferenceType refType) {
        try {
            var replayableBreakpointRequests = replayableBreakpointRequestsByAbsPath_.get(refType.sourceName());
            final var klassMap = new KlassMap(refType);
            klassMap_.put(refType.sourceName(), klassMap);
            if (replayableBreakpointRequests != null) {
                rebindBreakpoints(refType.sourceName(), replayableBreakpointRequests);
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void handleThreadStartEvent(ThreadStartEvent event) {
        trackThreadReference(event.thread());
    }

    private void handleThreadDeathEvent(ThreadDeathEvent event) {
        untrackThreadReference(event.thread());
    }

    private void handleClassPrepareEvent(ClassPrepareEvent event) {
        if (event.referenceType().name().equals("lucee.runtime.Page")) {
            // This can happen exactly once
            // Once we get the reftype, we create the tracking request to
            // track class prepare events for all subtypes of lucee.runtime.Page
            vm_.eventRequestManager().deleteEventRequest(event.request());
            bootClassTracking(event.referenceType());
        }
        else {
            trackClassRef(event.referenceType());
            // we will have suspended this thread,
            // in order to bind breakpoints synchronously with respect to the class's loading
            event.thread().resume();
        }
    }

    private void handleBreakpointEvent(BreakpointEvent event) {
        // worker initialization, should only happen once per jvm instance
        if (event.location().declaringType().classObject().uniqueID() == JDWP_WORKER_CLASS_ID) {
            JDWP_WORKER_THREADREF = event.thread();
            JdwpWorker.notifyJdwpSuspendedWorkerThread();
            return;
        }

        final var threadRef = event.thread();

        suspendedThreads.add(threadRef.uniqueID());

        if (steppingState == SteppingState.finalizingViaAwaitedBreakpoint) {
            // We're stepping, and we completed a step; now, we hit the breakpoint
            // that the step-completition handler installed. Stepping is complete.
            steppingState = SteppingState.none;
            if (stepEventCallback != null) {
                // We would delete the breakpoint request here,
                // but it should have been registered with an eventcount filter of 1,
                // meaning that it has auto-expired
                stepEventCallback.accept(event.thread().uniqueID());
            }
        }
        else {
            // if we are stepping, but we hit a breakpoint, cancel the stepping
            if (steppingState == SteppingState.stepping) {
                DebugManager.clearStepRequest(threadMap_.getThreadByJdwpIdOrFail(threadRef.uniqueID()));
                steppingState = SteppingState.none;
            }
            if (breakpointEventCallback != null) {
                final var bpID = (Integer) event.request().getProperty(LUCEEDEBUG_BREAKPOINT_ID);
                breakpointEventCallback.accept(threadRef.uniqueID(), bpID);
            }
        }
    }

    public ThreadReference[] getThreadListing() {
        var result = new ArrayList<ThreadReference>();
        
        for (var threadRef : threadMap_.threadRefByThread.values()) {
            result.add(threadRef);
        }

        return result.toArray(size -> new ThreadReference[size]);
    }

    public IDebugFrame[] getStackTrace(long jdwpThreadId) {
        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadId);
        return DebugManager.getCfStack(thread);
    }

    public IDebugEntity[] getScopes(long frameID) {
        return DebugManager.getScopesForFrame(frameID);
    }

    public IDebugEntity[] getVariables(long ID) {
        return DebugManager.getVariables(ID);
    }

    static private class KlassMap {
        // not strictly necessary, but accessing `refType.sourceName()` throws a checked exception,
        // so we can avoid unnecessary try/catch if we hoist it out and just reference it as a field
        final public String sourceName; 
        final public HashMap<Integer, Location> lineMap;
        final public ReferenceType refType;

        KlassMap(ReferenceType refType) {
            String sourceName = "";
            HashMap<Integer, Location> lineMap = new HashMap<>();
            try {
                sourceName = refType.sourceName();
                lineMap = new HashMap<Integer, Location>();
                for (var loc : refType.allLineLocations()) {
                    lineMap.put(loc.lineNumber(), loc);
                }
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
            finally {
                // appease the "final qualified field possibly not initialized" analyzer
                // by doing this in a finally
                this.sourceName = sourceName;
                this.lineMap = lineMap;
                this.refType = refType;
            }
        }
    }

    private AtomicInteger breakpointID = new AtomicInteger();
    private int nextBreakpointID() {
        return breakpointID.incrementAndGet();
    }

    public void rebindBreakpoints(String absPath, ArrayList<ReplayableCfBreakpointRequest> cfBpRequests) {
        System.out.println("Rebinding breakpoints for " + absPath );

        var changedBreakpoints = __internal__bindBreakpoints(absPath, ReplayableCfBreakpointRequest.getLineInfo(cfBpRequests));

        if (breakpointsChangedCallback != null) {
            breakpointsChangedCallback.accept(BreakpointsChangedEvent.justChanges(changedBreakpoints));
        }
    }   

    static class BpLineAndId {
        final int line;
        final int id;
        public BpLineAndId(int line, int id) {
            this.line = line;
            this.id = id;
        }

    }

    private BpLineAndId[] freshBpLineAndIdRecordsFromLines(int[] lines) {
        var result = new BpLineAndId[lines.length];
        for (var i = 0; i < lines.length; ++i) {
            result[i] = new BpLineAndId(lines[i], nextBreakpointID());
        }
        return result;
    }

    public IBreakpoint[] bindBreakpoints(String absPath, int[] lines) {
        return __internal__bindBreakpoints(absPath, freshBpLineAndIdRecordsFromLines(lines));
    }

    /**
     * caller is responsible for transforming the source path into a cf path,
     * i.e. the IDE might say "/foo/bar/baz.cfc" but we are only aware of "/app-host-container/foo/bar/baz.cfc" or etc. 
     */
    private IBreakpoint[] __internal__bindBreakpoints(String absPath, BpLineAndId[] lineInfo) {
        final var klassMap = klassMap_.get(absPath);

        if (klassMap == null) {
            var replayable = new ArrayList<ReplayableCfBreakpointRequest>();

            var result = new Breakpoint[lineInfo.length];
            for (int i = 0; i < lineInfo.length; i++) {
                final var line = lineInfo[i].line;
                final var id = lineInfo[i].id;

                result[i] = Breakpoint.Unbound(line, id);
                replayable.add(new ReplayableCfBreakpointRequest(line, id));
            }

            replayableBreakpointRequestsByAbsPath_.put(absPath, replayable);

            return result;
        }

        return __internal__idempotentBindBreakpoints(klassMap, lineInfo);
    }

    

    /**
     * Seems we're not allowed to inspect the jdwp-native id, but we can attach our own
     */
    private IBreakpoint[] __internal__idempotentBindBreakpoints(KlassMap klassMap, BpLineAndId[] lineInfo) {
        clearExistingBreakpoints(klassMap.sourceName);
        
        final var replayable = new ArrayList<ReplayableCfBreakpointRequest>();
        final var result = new ArrayList<IBreakpoint>();

        for (int i = 0; i < lineInfo.length; ++i) {
            final var line = lineInfo[i].line;
            final var id = lineInfo[i].id;
            final var maybeNull_location = klassMap.lineMap.get(line);

            if (maybeNull_location == null) {
                replayable.add(new ReplayableCfBreakpointRequest(line, id));
                result.add(Breakpoint.Unbound(line, id));
            }
            else {
                final var bpRequest = vm_.eventRequestManager().createBreakpointRequest(maybeNull_location);
                bpRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                bpRequest.putProperty(LUCEEDEBUG_BREAKPOINT_ID, id);
                bpRequest.setEnabled(true);
                replayable.add(new ReplayableCfBreakpointRequest(line, id, bpRequest));
                result.add(Breakpoint.Bound(line, id));
            }
        }

        replayableBreakpointRequestsByAbsPath_.put(klassMap.sourceName, replayable);

        return result.toArray(size -> new IBreakpoint[size]);
    }

    /**
     * returns an array of the line numbers the old breakpoints were bound to
     */
    private void clearExistingBreakpoints(String absPath) {
        var replayable = replayableBreakpointRequestsByAbsPath_.get(absPath);

        // "just do it" in all cases
        replayableBreakpointRequestsByAbsPath_.remove(absPath);

        if (replayable == null) {
            // no existing bp requests for the class having this source path
            return;
        }

        var bpRequests = ReplayableCfBreakpointRequest.getJdwpRequests(replayable);
        final var result = new int[bpRequests.size()];

        for (int i = 0; i < result.length; ++i) {
            result[i] = bpRequests.get(i).location().lineNumber();
        }

        vm_.eventRequestManager().deleteEventRequests(bpRequests);
    }

    public void clearAllBreakpoints() {
        replayableBreakpointRequestsByAbsPath_.clear();
        vm_.eventRequestManager().deleteAllBreakpoints();
    }

    /**
     * Non-concurrent map is OK here?
     * reasoning: all requests come from the IDE, and there is only one connected IDE, communicating over a single socket.
     */
    private HashSet</*jdwpID*/ Long> suspendedThreads = new HashSet<>();

    public void continue_(long jdwpThreadID) {
        final var threadRef = threadMap_.getThreadRefByJdwpIdOrFail(jdwpThreadID);
        continue_(threadRef);
    }

    private void continue_(ThreadReference threadRef) {
        // Maybe a race here -- order of these ops matters?
        // Our tracking info is slightly out of sync with the realworld here,
        // if we remove the entry from suspended threads and then call resume.
        // But the same problem exists if we call resume, and then remove it from suspended threads ... ?
        suspendedThreads.remove(threadRef.uniqueID());

        /**
         * Make a copy of "current suspend count", rather than loop by testing `threadRef.suspendCount()`
         * Otherwise, we race with the resumed thread and
         * breakpoints that might get hit immediately after resuming:
         *  - call thread.resume()
         *  - suspendCount() hits 0 --> target thread resumes
         *  - Before we restart the loop, a breakpoint on the resumed thread is hit
         *  - retest loop predicate, checking against `threadRef.suspendCount()` shows that is non-zero
         *  - We `threadRef.resume()` again, effectively skipping the breakpoint the earlier resume allowed us to hit
         */
        var suspendCount = threadRef.suspendCount();

        while (suspendCount > 0) {
            threadRef.resume();
            suspendCount--;
        }
    }

    public void continueAll() {
        suspendedThreads.forEach(jdwpThreadID -> continue_(jdwpThreadID));
    }

    public void stepIn(long jdwpThreadID) {
        if (steppingState != SteppingState.none) {
            return;
        }

        steppingState = SteppingState.stepping;

        System.out.println(" STEP IN");
        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            return;
        }

        DebugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_INTO);

        continue_(threadRef);
    }

    public void stepOver(long jdwpThreadID) {
        if (steppingState != SteppingState.none) {
            return;
        }

        steppingState = SteppingState.stepping;

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);
        
        if (threadRef.suspendCount() == 0) {
            System.out.println("step over handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        DebugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OVER);
        
        continue_(threadRef);
    }

    public void stepOut(long jdwpThreadID) {
        if (steppingState != SteppingState.none) {
            return;
        }

        steppingState = SteppingState.stepping;

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            return;
        }

        DebugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OUT);
        
        continue_(threadRef);
    }
}
