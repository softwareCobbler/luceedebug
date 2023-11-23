package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentMap;

import luceedebug.*;

public class LuceeVm implements ILuceeVm {
    // This is a key into a map stored on breakpointRequest objects; the value should always be of Integer type
    // "step finalization" breakpoints will not have this, so lookup against it will yield null
    final static private String LUCEEDEBUG_BREAKPOINT_ID = "luceedebug-breakpoint-id";
    final static private String LUCEEDEBUG_BREAKPOINT_EXPR = "luceedebug-breakpoint-expr";

    private final Config config_;
    private final VirtualMachine vm_;

    /**
     * A multimap of (jdwp threadID -> jvm Thread) & (jvm Thread -> jdwp ThreadRef)
     */
    private static class ThreadMap {
        private final Cleaner cleaner = Cleaner.create();

        private final ConcurrentHashMap</*jdwpID for thread*/Long, WeakReference<Thread>> threadByJdwpId = new ConcurrentHashMap<>();
        private final ConcurrentMap<Thread, ThreadReference> threadRefByThread = new MapMaker()
            .concurrencyLevel(/* default as per docs */ 4)
            .weakKeys()
            .makeMap();

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
            final long threadID = threadRef.uniqueID();
            threadByJdwpId.put(threadID, new WeakReference<>(thread));
            threadRefByThread.put(thread, threadRef);
            cleaner.register(thread, () -> {
                // Manually remove from (threadID -> WeakRef<Thread>) mapping
                // The (WeakRef<Thread> -> ThreadRef) map should be autocleaning by virtue of "weakKeys"
                threadByJdwpId.remove(threadID);
            });
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
        final String ideAbsPath;
        final String serverAbsPath;
        final int line;
        final int id;
        /**
         * expression for conditional breakpoints
         * can be null for "not a conditional breakpoint"
         **/
        final String expr;

        /**
         * The implication is that a breakpoint is bound if we found a location for it an issued
         * a jdwp breakpoint request; but can we further interrogate the jdwp breakpoint request
         * and ask it if it itself is bound? Does `isEnabled` yield that, or is that just "we asked for it to be enabled"?
         */
        final BreakpointRequest maybeNull_jdwpBreakpointRequest;
        
        ReplayableCfBreakpointRequest(String ideAbsPath, String serverAbsPath, int line, int id, String expr) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
            this.maybeNull_jdwpBreakpointRequest = null;
        }

        ReplayableCfBreakpointRequest(String ideAbsPath, String serverAbsPath, int line, int id, String expr, BreakpointRequest jdwpBreakpointRequest) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
            this.maybeNull_jdwpBreakpointRequest = jdwpBreakpointRequest;
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
                .map(v -> new BpLineAndId(v.ideAbsPath, v.serverAbsPath, v.line, v.id, v.expr))
                .toArray(size -> new BpLineAndId[size]);
        }
    }

    private final ThreadMap threadMap_ = new ThreadMap();
    private final AsyncWorker asyncWorker_ = new AsyncWorker();
    private final ConcurrentHashMap</*canonical sourceAbsPath*/ String, ArrayList<ReplayableCfBreakpointRequest>> replayableBreakpointRequestsByAbsPath_ = new ConcurrentHashMap<>();
    private final ConcurrentHashMap</*canonical absPath*/ String, KlassMap> klassMap_ = new ConcurrentHashMap<>();
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
        
        // Should we suspend thread start event threads?
        // Is there a perf hit to doing so?
        // We can catch the ObjectCollectedExceptions that happen if they get collected prior to us doing
        // work against it, and event with SUSPEND_EVENT_THREAD we were somehow hitting ObjectCollectedExceptions.
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
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.setEnabled(true);
        }
        else if (pageRef.size() == 1) {
            // we hoped it would be this easy, but when we initialize LuceeVm, lucee.runtime.Page is probably not loaded yet
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

        final String className = "luceedebug.coreinject.LuceeVm$JdwpWorker";
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

    public LuceeVm(Config config, VirtualMachine vm, IDebugManager debugManager) {
        this.config_ = config;
        this.vm_ = vm;
        this.asyncWorker_.start();
        
        initEventPump();

        jdwp_getThread = bootThreadWorker();

        bootClassTracking();

        bootThreadTracking();
        
        registerDebugManagerHooks(debugManager);
    }

    public void registerDebugManagerHooks(IDebugManager debugManager) {
        debugManager.registerCfStepHandler((thread, distanceToFrame) -> {
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
                    //
                    // Thread filter considerations, troubleshooting weird "stepping across thread" behavior.
                    // currentLoc + 3 might be (handwavingly)
                    //   udfCallN():
                    //   bc 0 <-- you are here
                    //   bc +3 <-- jumping here
                    //   bc +6
                    //   ...
                    // But it's possible that the bc+3 represents a jump target from a switch dispatch table or elsewhere,
                    // (which is commonly used to dispatch local functions), and we are at the end of a control flow,
                    // where we've just scheduled work, whose code begins at bc+3, on a separate thread, as in
                    //
                    // (suspended on this line) | scheduleWork(() => {/* stepping over this can land on another thread */})
                    //
                    // So we want to add a thread filter to prevent weird jumps across threads.
                    //
                    // It's not clear why we'd match such a step events up from the step event handler though.
                    //
                    final var frame = threadRef.frame(distanceToFrame);
                    final var location = frame.location().method().locationOfCodeIndex(frame.location().codeIndex() + 3);

                    final var bp = vm_.eventRequestManager().createBreakpointRequest(location);
                    bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    bp.addThreadFilter(threadRef); // can we jump threads? seems like it doing something like linkedBlockingDeque.offer(new Runnable(() => ...)) ?
                    bp.addCountFilter(1);
                    bp.setEnabled(true);
                    
                    steppingStatesByThread.put(threadRef.uniqueID(), SteppingState.finalizingViaAwaitedBreakpoint); // races with step handlers ?

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
            while (!done.get()); // wait here is ~10ms
        });
    }

    /**
     * Our steps are different than jdwp step events. We don't use jdwp step events because
     * they tend to (always? not sure) drop the jvm into interpreted mode, with associated perf degredation.
     * `isStepping=true` means "the next breakpoint we hit actually implies a completed DAP step event, rather
     * than a user-defined breakpoint event"
     */
    private static enum SteppingState { stepping, finalizingViaAwaitedBreakpoint }
    private ConcurrentMap</*jdwp threadID*/Long, SteppingState> steppingStatesByThread = new ConcurrentHashMap<>();
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
        new java.lang.Thread(() -> {
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
            catch (ObjectCollectedException e) {
                if (JDWP_WORKER_THREADREF.isCollected()) {
                    System.out.println("[luceedebug] fatal: JDWP_WORKER_THREADREF is collected");
                    System.exit(1);
                }
                else {
                    // discard
                }
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
            final var maybeNull_klassMap = KlassMap.maybeNull_tryBuildKlassMap(config_, refType);
            
            if (maybeNull_klassMap == null) {
                // try to get a meaningful name; but default to normal "toString" in the exceptional case
                String name = refType.toString();
                try {
                    name = refType.sourceName();
                }
                catch (Throwable e) {
                    // discard
                }
                System.out.println("[luceedebug] class information for reftype " + name + " could not be retrieved.");
                return;
            }

            final var klassMap = maybeNull_klassMap; // definitely non-null

            var replayableBreakpointRequests = replayableBreakpointRequestsByAbsPath_.get(klassMap.sourceName.transformed);
            klassMap_.put(klassMap.sourceName.transformed, klassMap);
            if (replayableBreakpointRequests != null) {
                rebindBreakpoints(klassMap.sourceName.transformed, replayableBreakpointRequests);
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
            // We are required to have suspended the thread,
            // otherwise we may have just missed a bunch of subtypes of lucee.runtime.Page getting classPrepare'd.
            // Now that we have registered our request to track those class types, we can keep going.
            event.thread().resume();
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

        if (steppingStatesByThread.remove(threadRef.uniqueID(), SteppingState.finalizingViaAwaitedBreakpoint)) {
            // We're stepping, and we completed a step; now, we hit the breakpoint
            // that the step-completition handler installed. Stepping is complete.
            if (stepEventCallback != null) {
                // We would delete the breakpoint request here,
                // but it should have been registered with an eventcount filter of 1,
                // meaning that it has auto-expired
                stepEventCallback.accept(event.thread().uniqueID());
            }
        }
        else {
            // if we are stepping, but we hit a breakpoint, cancel the stepping
            if (steppingStatesByThread.remove(threadRef.uniqueID(), SteppingState.stepping)) {
                GlobalIDebugManagerHolder.debugManager.clearStepRequest(threadMap_.getThreadByJdwpIdOrFail(threadRef.uniqueID()));
            }

            final EventRequest request = event.request();
            final Object maybe_expr = request.getProperty(LUCEEDEBUG_BREAKPOINT_EXPR);
            if (maybe_expr instanceof String) {
                // if we have a conditional expr bound to this breakpoint, try to evaluate in the context of the topmost cf frame for this thread
                // if it's not cf-truthy, then unsuspend this thread
                final var jdwp_threadID = event.thread().uniqueID();
                if (!GlobalIDebugManagerHolder.debugManager.evaluateAsBooleanForConditionalBreakpoint(
                    threadMap_.getThreadByJdwpIdOrFail(jdwp_threadID),
                    (String)maybe_expr)
                ) {
                    continue_(jdwp_threadID);
                    return;
                }
            }

            if (breakpointEventCallback != null) {
                final var bpID = (Integer) request.getProperty(LUCEEDEBUG_BREAKPOINT_ID);
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
        return GlobalIDebugManagerHolder.debugManager.getCfStack(thread);
    }

    public IDebugEntity[] getScopes(long frameID) {
        return GlobalIDebugManagerHolder.debugManager.getScopesForFrame(frameID);
    }

    public IDebugEntity[] getVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, null);
    }

    public IDebugEntity[] getNamedVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, IDebugEntity.DebugEntityType.NAMED);
    }

    public IDebugEntity[] getIndexedVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, IDebugEntity.DebugEntityType.INDEXED);
    }

    static private class KlassMap {
        /**
         * original -> original
         * 
         * transformed -> canonicalized as per fs config
         */
        final public OriginalAndTransformedString sourceName; 
        final public HashMap<Integer, Location> lineMap;
        
        @SuppressWarnings("unused")
        final public ReferenceType refType;

        private KlassMap(Config config, ReferenceType refType) throws AbsentInformationException {
            String sourceName = refType.sourceName();
            var lineMap = new HashMap<Integer, Location>();
            
            for (var loc : refType.allLineLocations()) {
                lineMap.put(loc.lineNumber(), loc);
            }

            this.sourceName = config.canonicalizedPath(sourceName);
            this.lineMap = lineMap;
            this.refType = refType;
        }

        /**
         * May return null if ReferenceType throws an AbsentInformationException, which the caller
         * should interpret as "we can't do anything meaningful with this file"
         */
        static KlassMap maybeNull_tryBuildKlassMap(Config config, ReferenceType refType) {
            try {
                return new KlassMap(config, refType);
            }
            catch (AbsentInformationException e) {
                return null;
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }

            // unreachable
            return null;
        }
    }

    private AtomicInteger breakpointID = new AtomicInteger();
    private int nextBreakpointID() {
        return breakpointID.incrementAndGet();
    }

    public void rebindBreakpoints(String serverAbsPath, ArrayList<ReplayableCfBreakpointRequest> cfBpRequests) {
        System.out.println("Rebinding breakpoints for " + serverAbsPath);

        var changedBreakpoints = __internal__bindBreakpoints(serverAbsPath, ReplayableCfBreakpointRequest.getLineInfo(cfBpRequests));

        if (breakpointsChangedCallback != null) {
            breakpointsChangedCallback.accept(BreakpointsChangedEvent.justChanges(changedBreakpoints));
        }
    }   

    static class BpLineAndId {
        final String ideAbsPath;
        final String serverAbsPath;
        final int line;
        final int id;
        final String expr;

        public BpLineAndId(String ideAbsPath, String serverAbsPath, int line, int id, String expr) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
        }

    }

    private BpLineAndId[] freshBpLineAndIdRecordsFromLines(OriginalAndTransformedString absPath, int[] lines, String[] exprs) {
        if (lines.length != exprs.length) { // really this should be some kind of aggregate
            throw new AssertionError("lines.length != exprs.length");
        }

        var result = new BpLineAndId[lines.length];
        for (var i = 0; i < lines.length; ++i) {
            result[i] = new BpLineAndId(absPath.original, absPath.transformed, lines[i], nextBreakpointID(), exprs[i]);
        }
        return result;
    }

    public IBreakpoint[] bindBreakpoints(OriginalAndTransformedString absPath, int[] lines, String[] exprs) {
        return __internal__bindBreakpoints(absPath.transformed, freshBpLineAndIdRecordsFromLines(absPath, lines, exprs));
    }

    /**
     * caller is responsible for transforming the source path into a cf path,
     * i.e. the IDE might say "/foo/bar/baz.cfc" but we are only aware of "/app-host-container/foo/bar/baz.cfc" or etc. 
     */
    private IBreakpoint[] __internal__bindBreakpoints(String serverAbsPath, BpLineAndId[] lineInfo) {
        final var klassMap = klassMap_.get(serverAbsPath);

        if (klassMap == null) {
            var replayable = new ArrayList<ReplayableCfBreakpointRequest>();

            var result = new Breakpoint[lineInfo.length];
            for (int i = 0; i < lineInfo.length; i++) {
                final var ideAbsPath = lineInfo[i].ideAbsPath;
                final var shadow_serverAbsPath = lineInfo[i].serverAbsPath; // should be same as first arg to this method, kind of redundant
                final var line = lineInfo[i].line;
                final var id = lineInfo[i].id;
                final var expr = lineInfo[i].expr;

                result[i] = Breakpoint.Unbound(line, id);
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, shadow_serverAbsPath, line, id, expr));
            }

            replayableBreakpointRequestsByAbsPath_.put(serverAbsPath, replayable);

            return result;
        }

        return __internal__idempotentBindBreakpoints(klassMap, lineInfo);
    }

    

    /**
     * Seems we're not allowed to inspect the jdwp-native id, but we can attach our own
     */
    private IBreakpoint[] __internal__idempotentBindBreakpoints(KlassMap klassMap, BpLineAndId[] lineInfo) {
        clearExistingBreakpoints(klassMap.sourceName.transformed);
        
        final var replayable = new ArrayList<ReplayableCfBreakpointRequest>();
        final var result = new ArrayList<IBreakpoint>();

        for (int i = 0; i < lineInfo.length; ++i) {
            final var ideAbsPath = lineInfo[i].ideAbsPath;
            final var serverAbsPath = lineInfo[i].serverAbsPath;
            final var line = lineInfo[i].line;
            final var id = lineInfo[i].id;
            final var maybeNull_location = klassMap.lineMap.get(line);
            final var expr = lineInfo[i].expr;

            if (maybeNull_location == null) {
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, serverAbsPath, line, id, expr));
                result.add(Breakpoint.Unbound(line, id));
            }
            else {
                final var bpRequest = vm_.eventRequestManager().createBreakpointRequest(maybeNull_location);
                bpRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                bpRequest.putProperty(LUCEEDEBUG_BREAKPOINT_ID, id);

                if (expr != null) {
                    bpRequest.putProperty(LUCEEDEBUG_BREAKPOINT_EXPR, expr);
                }

                bpRequest.setEnabled(true);
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, serverAbsPath, line, id, expr, bpRequest));
                result.add(Breakpoint.Bound(line, id));
            }
        }

        replayableBreakpointRequestsByAbsPath_.put(klassMap.sourceName.transformed, replayable);

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
        // avoid concurrent modification exceptions, calling continue_ mutates `suspendedThreads`
        Arrays
            .asList(suspendedThreads.toArray(size -> new Long[size]))
            .forEach(jdwpThreadID -> continue_(jdwpThreadID));
    }

    public void stepIn(long jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            System.out.println("step in handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_INTO);

        continue_(threadRef);
    }

    public void stepOver(long jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);
        
        if (threadRef.suspendCount() == 0) {
            System.out.println("step over handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OVER);
        
        continue_(threadRef);
    }

    public void stepOut(long jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            System.out.println("step out handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OUT);
        
        continue_(threadRef);
    }

    // presumably, the requester is requesting to dump a variable because they
    // have at least one suspended thread they're investigating. We should have that thread,
    // or at least one suspended thread. It doesn't matter which thread we use, we just
    // need there to be an associated PageContext, so we can get its:
    //   - Config
    //   - ServletConfig
    // If we can figure out how to get those from some singleton somewhere then we wouldn't need
    // to do any thread lookup here.
    //
    // There's no guarantee that a suspended thread is associated with a PageContext,
    // so we need to pass a list of all suspended threads, and the manager can use that
    // to find a PageContext.
    //
    private ArrayList<Thread> getSuspendedThreadListForDumpWorker() {
        final var suspendedThreadsList = new ArrayList<Thread>();
        suspendedThreads.iterator().forEachRemaining(jdwpThreadID -> {
            var thread = threadMap_.getThreadByJdwpId(jdwpThreadID);
            if (thread != null) {
                suspendedThreadsList.add(thread);
            }
        });
        return suspendedThreadsList;
    }

    public String dump(int dapVariablesReference) {
        return GlobalIDebugManagerHolder.debugManager.doDump(getSuspendedThreadListForDumpWorker(), dapVariablesReference);
    }

    public String dumpAsJSON(int dapVariablesReference) {
        return GlobalIDebugManagerHolder.debugManager.doDumpAsJSON(getSuspendedThreadListForDumpWorker(), dapVariablesReference);
    }

    public String[] getTrackedCanonicalFileNames() {
        final var result = new ArrayList<String>();
        for (var klassMap : klassMap_.values()) {
            result.add(klassMap.sourceName.transformed);
        }
        return result.toArray(size -> new String[size]);
    }

    public String[][] getBreakpointDetail() {
        final var result = new ArrayList<ArrayList<String>>();
        for (var bps : replayableBreakpointRequestsByAbsPath_.entrySet()) {
            for (var bp : bps.getValue()) {
                final var commonSuffix = ":" + bp.line + (bp.maybeNull_jdwpBreakpointRequest == null ? " (unbound)" : " (bound)");
                final var pair = new ArrayList<String>();
                pair.add(bp.ideAbsPath + commonSuffix);
                pair.add(bp.serverAbsPath + commonSuffix);
                result.add(pair);
            }
        }
        return result.stream().map(u -> u.toArray(new String[0])).toArray(String[][]::new);
    }

    public String getSourcePathForVariablesRef(int variablesRef) {
        return GlobalIDebugManagerHolder.debugManager.getSourcePathForVariablesRef(variablesRef);
    }

    public Either<String, Either<ICfEntityRef, String>> evaluate(int frameID, String expr) {
        return GlobalIDebugManagerHolder.debugManager.evaluate((Long)(long)frameID, expr);
    }
}
