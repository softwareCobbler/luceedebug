package luceedebug.coreinject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.servlet.ServletException;

import com.google.common.collect.MapMaker;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.AttachingConnector;

import static lucee.loader.engine.CFMLEngine.DIALECT_CFML;
import lucee.runtime.PageContext;
import lucee.runtime.exp.PageException;
import luceedebug.Config;
import luceedebug.DapServer;
import luceedebug.Either;
import luceedebug.GlobalIDebugManagerHolder;
import luceedebug.ICfValueDebuggerBridge;
import luceedebug.IDebugEntity;
import luceedebug.IDebugFrame;
import luceedebug.IDebugManager;
import luceedebug.coreinject.frame.DebugFrame;
import luceedebug.coreinject.frame.Frame;

public class DebugManager implements IDebugManager {

    /**
     * see DebugManager.dot for class loader graph
     */
    public DebugManager() {
        // Sanity check that we're being loaded as expected.
        // DebugManager must be loaded with the "lucee core loader", which means we need to have already seen PageContextImpl.
        // Using the "core loader" (which is used to load, amongst other things, PageContextImpl) gives us
        // same-classloader-visibility (term for that?) to PageContextImpl, so we can ask it for detailed runtime info.
        if (GlobalIDebugManagerHolder.luceeCoreLoader == null) {
            System.out.println("[luceedebug] fatal - expected luceedebug.coreinject.DebugManager to be loaded with the Lucee core loader, but the Lucee core loader hasn't been loaded yet.");
            System.exit(1);
        }
        else if (GlobalIDebugManagerHolder.luceeCoreLoader != this.getClass().getClassLoader()) {
            System.out.println("[luceedebug] fatal - expected luceedebug.coreinject.DebugManager to be loaded with the Lucee core loader, but it is being loaded with classloader='" + this.getClass().getClassLoader() + "'.");
            System.out.println("[luceedebug]         lucee coreLoader has been seen, and is " + GlobalIDebugManagerHolder.luceeCoreLoader);
            System.exit(1);
        }
        else {
            // ok, no problem; nothing to do.
            // This should be a singleton, but the instance is stored outside of coreinject.
        }
    }

    // definitely non-null after spawnWorker
    private Config config_ = null;

    public void spawnWorker(Config config, String jdwpHost, int jdwpPort, String debugHost, int debugPort) {
        config_ = config;
        final String threadName = "luceedebug-worker";

        System.out.println("[luceedebug] attempting jdwp self connect to jdwp on " + jdwpHost + ":" + jdwpPort + "...");

        VirtualMachine vm = jdwpSelfConnect(jdwpHost, jdwpPort);
        LuceeVm luceeVm = new LuceeVm(config, vm);

        new Thread(() -> {
            System.out.println("[luceedebug] jdwp self connect OK");
            DapServer.createForSocket(luceeVm, config, debugHost, debugPort);
        }, threadName).start();
    }

    static private AttachingConnector getConnector() {
        VirtualMachineManager vmm;
        try {
            vmm = Bootstrap.virtualMachineManager();
        }
        catch (NoClassDefFoundError e) {
            if (e.getMessage().contains("com/sun/jdi/Bootstrap")) {
                // this is a common error
                System.out.println("[luceedebug]");
                System.out.println("[luceedebug]");
                System.out.println("[luceedebug] couldn't load a com.sun.jdi.VirtualMachineManager; you might not be running the JDK version of your Java release");
                System.out.println("[luceedebug]");
                System.out.println("[luceedebug]");
            }
            throw e;
        }

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

    static private VirtualMachine jdwpSelfConnect(String host, int port) {
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

    private String wrapDumpInHtmlDoc(String s) {
        return "<!DOCTYPE html><html><body>" + s + "</body></html>";
    }

    // we only need to know the thread, so we can get the pagecontext, so we can get Config and ServletConfig,
    // which are required to generate a fresh page context, which we want so we have a fresh state and especially an empty output buffer.
    // Are Config and ServletConfig globally available? Pulling those values from some global context would be much less kludgy.
    // We need to know which thread is suspended, but caller doesn't have that info, just a variableID.
    // Caller does have "all the suspended threads", but not all of them may have an associated page context.
    // So we can iterate until we find one with an associated page context
    synchronized public String doDump(ArrayList<Thread> suspendedThreads, int variableID) {
        final var pageContext = maybeNull_findPageContext(suspendedThreads);
        if (pageContext == null) {
            var msgBuilder = new StringBuilder();
            suspendedThreads.forEach(thread -> msgBuilder.append("<div>" + thread + "</div>"));
            return "<div>couldn't get a page context, iterated over threads:</div>" + msgBuilder.toString();
        }

        final var entity = findEntity(variableID);
        if (entity.isRight()) {
            return "<div>" + entity.right + "</div>";
        }

        return doDump(pageContext, entity.left);
    }

    synchronized public String doDumpAsJSON(ArrayList<Thread> suspendedThreads, int variableID) {
        final var pageContext = maybeNull_findPageContext(suspendedThreads);
        if (pageContext == null) {
            return "\"couldn't find a page context to do work on\"";
        }

        final var entity = findEntity(variableID);
        if (entity.isRight()) {
            return "\"" + entity.right.replace("\"", "\\\"") + "\"";
        }

        return doDumpAsJSON(pageContext, entity.left);
    }

    synchronized private Either<Object, String> findEntity(int variableID) {
        return Either
            .fromOpt(valTracker.maybeGetFromId(variableID))
            .bimap(
                taggedObj -> taggedObj.obj,
                v -> "Lookup of ref having ID " + variableID + " found nothing."
            );
    }
    
    /**
     * synchronized might be unnecessary here
     */
    synchronized private lucee.runtime.PageContext maybeNull_findPageContext(ArrayList<Thread> suspendedThreads) {
        final var pageContextRef = ((Supplier<WeakReference<PageContext>>) () -> {
            for (var thread : suspendedThreads) {
                var pageContextRef_ = pageContextByThread.get(thread);
                if (pageContextRef_ != null) {
                    return pageContextRef_;
                }
            }
            return null;
        }).get();
        return pageContextRef == null ? null : pageContextRef.get();
    }

    public static class PageContextAndOutputStream {
        public final PageContext pageContext;
        public final ByteArrayOutputStream outStream;
        public PageContextAndOutputStream(PageContext pageContext, ByteArrayOutputStream outStream) {
            this.pageContext = pageContext;
            this.outStream = outStream;
        }

        // is there a way to conjure up a new PageContext without having some other page context?
        public static PageContextAndOutputStream ephemeralPageContextFromOther(PageContext pc) throws ServletException {
            final var outputStream = new ByteArrayOutputStream();
            PageContext freshEphemeralPageContext = lucee.runtime.util.PageContextUtil.getPageContext(
                /*Config config*/ pc.getConfig(),
                /*ServletConfig servletConfig*/ pc.getServletConfig(),
                /*File contextRoot*/ new File("."),
                /*String host*/ "",
                /*String scriptName*/ "",
                /*String queryString*/ "",
                /*Cookie[] cookies*/ new javax.servlet.http.Cookie[] {},
                /*Map<String, Object> headers*/ new HashMap<>(),
                /*Map<String, String> parameters*/ new HashMap<>(),
                /*Map<String, Object> attributes*/ new HashMap<>(),
                /*OutputStream os*/ outputStream,
                /*boolean register*/ false,
                /*long timeout*/ 99999, // seconds?
                /*boolean ignoreScopes*/ true
            );
            return new PageContextAndOutputStream(freshEphemeralPageContext, outputStream);
        }
    }

    // this is "single threaded" for now, only a single dumpable thing is tracked at once,
    // pushing another dump overwrites the old dump.
    synchronized private String doDump(PageContext pageContext, Object someDumpable) {
        final var result = new Object(){ String value = "if this text is present, something went wrong when calling writeDump(...)"; };
        final var thread = new Thread(() -> {
            try {
                final var ephemeralContext = PageContextAndOutputStream.ephemeralPageContextFromOther(pageContext);
                final var freshEphemeralPageContext = ephemeralContext.pageContext;
                final var outputStream = ephemeralContext.outStream;

                lucee.runtime.engine.ThreadLocalPageContext.register(freshEphemeralPageContext);
                
                lucee.runtime.functions.system.CFFunction.call(
                    freshEphemeralPageContext, new Object[]{
                        lucee.runtime.type.FunctionValueImpl.newInstance(lucee.runtime.type.util.KeyConstants.___filename, "writeDump.cfm"),
                        lucee.runtime.type.FunctionValueImpl.newInstance(lucee.runtime.type.util.KeyConstants.___name, "writeDump"),
                        lucee.runtime.type.FunctionValueImpl.newInstance(lucee.runtime.type.util.KeyConstants.___isweb, Boolean.FALSE),
                        lucee.runtime.type.FunctionValueImpl.newInstance(lucee.runtime.type.util.KeyConstants.___mapping, "/mapping-function"),
                        someDumpable
                        //LiteralArray.call(var1, new Object[]{LiteralValue.toNumber(var1, 0L)})
                    });

                freshEphemeralPageContext.flush();
                result.value = wrapDumpInHtmlDoc(new String(outputStream.toByteArray(), "UTF-8"));

                lucee.runtime.util.PageContextUtil.releasePageContext(
                    /*PageContext pc*/ freshEphemeralPageContext,
                    /*boolean register*/ true
                );

                outputStream.close();

                lucee.runtime.engine.ThreadLocalPageContext.release();
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        });

        thread.start();

        try {
            // needs to be on its own thread for PageContext reasons
            // but we still want to do this synchronously
            thread.join();
        }
        catch (Throwable e) {
            if (thread.isAlive()) {
                e.printStackTrace();
                System.exit(1);
            }
            else {
                // thread is joined, discard exception
            }
        }

        return result.value;
    }

    synchronized private String doDumpAsJSON(PageContext pageContext, Object someDumpable) {
        final var result = new Object(){ String value = "\"Something went wrong when calling serializeJSON(...)\""; };
        final var thread = new Thread(() -> {
            try {
                final var ephemeralContext = PageContextAndOutputStream.ephemeralPageContextFromOther(pageContext);
                final var freshEphemeralPageContext = ephemeralContext.pageContext;
                final var outputStream = ephemeralContext.outStream;

                lucee.runtime.engine.ThreadLocalPageContext.register(freshEphemeralPageContext);
                
                result.value = (String)lucee.runtime.functions.conversion.SerializeJSON.call(
                    /*PageContext pc*/ freshEphemeralPageContext,
                    /*Object var*/ someDumpable,
                    /*Object queryFormat*/"struct"
                );

                lucee.runtime.util.PageContextUtil.releasePageContext(
                    /*PageContext pc*/ freshEphemeralPageContext,
                    /*boolean register*/ true
                );

                outputStream.close();

                lucee.runtime.engine.ThreadLocalPageContext.release();
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        });

        thread.start();

        try {
            // needs to be on its own thread for PageContext reasons
            // but we still want to do this synchronously
            thread.join();
        }
        catch (Throwable e) {
            if (thread.isAlive()) {
                e.printStackTrace();
                System.exit(1);
            }
            else {
                // thread is joined, discard exception
            }
        }

        return result.value;
    }

    public Either</*err*/String, /*ok*/Either<ICfValueDebuggerBridge, String>> evaluate(Long frameID, String expr) {
        final var zzzframe = frameByFrameID.get(frameID);
        if (!(zzzframe instanceof Frame)) {
            return Either.Left("<<no such frame>>");
        }

        Frame frame = (Frame)zzzframe;

        return doEvaluate(frame, expr)
            .bimap(
                err -> err,
                ok -> {
                    // what about bool, Long, etc. ?...
                    if (ok == null) {
                        return Either.Right("null");
                    }
                    else if (ok instanceof String) {
                        return Either.Right("\"" + ((String)ok).replaceAll("\"", "\\\"") + "\"");
                    }
                    else if (ok instanceof Number || ok instanceof Boolean) {
                        return Either.Right(ok.toString());
                    }
                    else {
                        return Either.Left(frame.trackEvalResult(ok));
                    }
                }
            );
    }

    // concurrency here needs to be at the level of the DAP server?
    // does the DAP server do multiple concurrent requests ... ? ... it's all one socket so probably not ? ... well many inbound messages can be being serviced ...
    private Either</*err*/String, /*ok*/Object> doEvaluate(Frame frame, String expr) {
        try {
            return CompletableFuture
                .supplyAsync(
                    (Supplier<Either<String,Object>>)(() -> {
                        return frame
                            .getFrameContext()
                            .doWorkInThisFrame((Supplier<Either<String,Object>>)() -> {
                                try {
                                    lucee.runtime.engine.ThreadLocalPageContext.register(frame.getFrameContext().pageContext);
                                    return ExprEvaluator.eval(frame, expr);
                                }
                                catch (Throwable e) {
                                    return Either.Left(e.getMessage());
                                }
                                finally {
                                    lucee.runtime.engine.ThreadLocalPageContext.release();
                                }
                            });
                    })
                ).get(5, TimeUnit.SECONDS);
        }
        catch (Throwable e) {
            return Either.Left(e.getMessage());
        }
    }

    public boolean evaluateAsBooleanForConditionalBreakpoint(Thread thread, String expr) {
        var stack = cfStackByThread.get(thread);
        if (stack == null) {
            return false;
        }
        if (stack.isEmpty()) {
            return false;
        }
        
        DebugFrame frame = stack.get(stack.size() - 1);
        
        if (frame instanceof Frame) {
            return doEvaluateAsBoolean((Frame)frame, expr);
        }
        else {
            return false;
        }
    }

    private boolean doEvaluateAsBoolean(Frame frame, String expr) {
        try {
            return CompletableFuture
                .supplyAsync(
                    (Supplier<Boolean>)(() -> {
                        return frame
                            .getFrameContext()
                            .doWorkInThisFrame((Supplier<Boolean>)() -> {
                                try {
                                    lucee.runtime.engine.ThreadLocalPageContext.register(frame.getFrameContext().pageContext);
                                    Object obj = lucee.runtime.functions.dynamicEvaluation.Evaluate.call(
                                        frame.getFrameContext().pageContext,
                                        new String[]{expr}
                                    );
                                    return lucee.runtime.op.Caster.toBoolean(obj);
                                }
                                catch (PageException e) {
                                    return false;
                                }
                                finally {
                                    lucee.runtime.engine.ThreadLocalPageContext.release();
                                }
                            });
                    })
                ).get(5, TimeUnit.SECONDS);
        }
        catch (Throwable e) {
            return false;
        }
    }

    private final Cleaner cleaner = Cleaner.create();

    // MapMaker().concurrencyLevel(4).weakKeys().makeMap() ---> ~20% overhead on pushFrame/popFrame
    // MapMaker().concurrencyLevel(4).makeMap()            ---> ~10% overhead on pushFrame/popFrame
    // Collections.synchronizedMap(new HashMap<>());       ---> ~12% overhead on pushFrame/popFrame
    private final ConcurrentMap<Thread, ArrayList<DebugFrame>> cfStackByThread = new MapMaker()
        .concurrencyLevel(/* default as per docs */ 4)
        .makeMap();
    private final ConcurrentMap<Thread, WeakReference<PageContext>> pageContextByThread = new MapMaker()
        .concurrencyLevel(/* default as per docs */ 4)
        .makeMap();

    private final ConcurrentHashMap<Long, DebugFrame> frameByFrameID = new ConcurrentHashMap<>();
    
    /**
     * an entity represents a Java object that itself is a CF object
     * There is exactly one unique Java object per unique CF object
     * These are nameless values floating in memory, and we wrap them in objects that themselves have IDs
     * Asking for an object (or registering the same object again) should produce the same ID for the same object
     */
    private ValTracker valTracker = new ValTracker(cleaner);

    private CfStepCallback didStepCallback = null;
    public void registerCfStepHandler(CfStepCallback cb) {
        didStepCallback = cb;
    }
    private void notifyStep(Thread thread, int minDistanceToLuceedebugStepNotificationEntryFrame) {
        if (didStepCallback != null) {
            didStepCallback.call(thread, minDistanceToLuceedebugStepNotificationEntryFrame + 1);
        }
    }

    synchronized public IDebugEntity[] getScopesForFrame(long frameID) {
        DebugFrame frame = frameByFrameID.get(frameID);
        //System.out.println("Get scopes for frame, frame was " + frame);
        if (frame == null) {
            return new IDebugEntity[0];
        }
        return frame.getScopes();
    }

    /**
     * @maybeNull_which --> null means "any type"
     */
    synchronized public IDebugEntity[] getVariables(long id, IDebugEntity.DebugEntityType maybeNull_which) {
        return valTracker
            .maybeGetFromId(id)
            .map(taggedObj -> CfValueDebuggerBridge.getAsDebugEntity(valTracker, taggedObj.obj, maybeNull_which))
            .orElseGet(() -> new IDebugEntity[0]);
    }

    synchronized public IDebugFrame[] getCfStack(Thread thread) {
        ArrayList<DebugFrame> stack = cfStackByThread.get(thread);
        if (stack == null) {
            System.out.println("getCfStack called, frames was null, frames is " + cfStackByThread + ", passed thread was " + thread);
            System.out.println("                   thread=" + thread + " this=" + this);
            return new Frame[0];
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

        return result.toArray(new Frame[result.size()]);
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

    public void registerStepRequest(Thread thread, int type) {
        DebugFrame frame = getTopmostFrame(thread);
        if (frame == null) {
            System.out.println("[luceedebug] registerStepRequest found no frames");
            System.exit(1);
            return;
        }

        switch (type) {
            case CfStepRequest.STEP_INTO:
                // fallthrough
            case CfStepRequest.STEP_OVER:
                // fallthrough
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
    private ConcurrentHashMap<Thread, CfStepRequest> stepRequestByThread = new ConcurrentHashMap<>();

    public void clearStepRequest(Thread thread) {
        stepRequestByThread.remove(thread);
    }

    public void luceedebug_stepNotificationEntry_step(int lineNumber) {
        final int minDistanceToLuceedebugStepNotificationEntryFrame = 0;
        Thread currentThread = Thread.currentThread();
        DebugFrame frame = maybeUpdateTopmostFrame(currentThread, lineNumber); // should be "definite update topmost frame", we 100% expect there to be a frame

        CfStepRequest request = stepRequestByThread.get(currentThread);
        if (request == null) {
            return;
        }
        else if (frame instanceof Frame) {
            request.__debug__steps++;
            maybeNotifyOfStepCompletion(currentThread, (Frame) frame, request, minDistanceToLuceedebugStepNotificationEntryFrame + 1, System.nanoTime());
        }
        else {
            // no-op
        }
    }

    /**
     * we need to know when stepped out of a udf call, back to the callsite.
     * This is different that "did the frame get popped", because if an exception was thrown, we won't return to the callsite even though the frame does get popped.
     * So we want the debugger to return to the callsite in the normal case, but jump to any catch/finally blocks in the exceptional case.
     */
    public void luceedebug_stepNotificationEntry_stepAfterCompletedUdfCall() {
        final int minDistanceToLuceedebugStepNotificationEntryFrame = 0;

        Thread currentThread = Thread.currentThread();
        DebugFrame frame = getTopmostFrame(Thread.currentThread());

        if (frame == null) {
            // just popped last frame?
            return;
        }

        CfStepRequest request = stepRequestByThread.get(currentThread);
        if (request == null) {
            return;
        }
        else if (frame instanceof Frame) {
            request.__debug__steps++;
            maybeNotifyOfStepCompletion(currentThread, (Frame)frame, request, minDistanceToLuceedebugStepNotificationEntryFrame + 1, System.nanoTime());
        }
        else {
            // no-op
        }
    }

    private void maybeNotifyOfStepCompletion(Thread currentThread, Frame frame, CfStepRequest request, int minDistanceToLuceedebugStepNotificationEntryFrame, long start) {
        if (frame.isUdfDefaultValueInitFrame && !config_.getStepIntoUdfDefaultValueInitFrames()) {
            return;
        }

        if (request.type == CfStepRequest.STEP_INTO) {
            // step in, every step is a valid step
            clearStepRequest(currentThread);
            notifyStep(currentThread, minDistanceToLuceedebugStepNotificationEntryFrame + 1);
        }
        else if (request.type == CfStepRequest.STEP_OVER) {
            if (frame.getDepth() > request.startDepth) {
                long end = System.nanoTime();
                request.__debug__stepOverhead += (end - start);
                return;
            }
            else {
                // long end = System.nanoTime();
                // double elapsed_ms = (end - request.__debug__startTime) / 1e6;
                // double stepsPerMs = request.__debug__steps / elapsed_ms;

                // System.out.println("  currentframedepth=" + frame.getDepth() + ", startframedepth=" + request.startDepth + ", notifying native of step occurence...");
                // System.out.println("    " + request.__debug__steps + " cf steps in " + elapsed_ms + "ms for " + stepsPerMs + " steps/ms, overhead was " + (request.__debug__stepOverhead / 1e6) + "ms");

                clearStepRequest(currentThread);
                notifyStep(currentThread, minDistanceToLuceedebugStepNotificationEntryFrame + 1);
            }
        }
        else if (request.type == CfStepRequest.STEP_OUT) {
            if (frame.getDepth() >= request.startDepth) {
                // stepping out, we need to have popped a frame to notify
                return;
            }
            else {
                clearStepRequest(currentThread);
                notifyStep(currentThread, minDistanceToLuceedebugStepNotificationEntryFrame + 1);
            }
        }
        else {
            // unreachable
        }
    }

    private DebugFrame maybeUpdateTopmostFrame(Thread thread, int lineNumber) {
        DebugFrame frame = getTopmostFrame(thread);
        if (frame == null) {
            return null;
        }
        frame.setLine(lineNumber);
        return frame;
    }

    private DebugFrame getTopmostFrame(Thread thread) {
        ArrayList<DebugFrame> stack = cfStackByThread.get(thread);
        if (stack == null || stack.size() == 0) {
            return null;
        }
        return stack.get(stack.size() - 1);
    }

    public void pushCfFrame(PageContext pageContext, String sourceFilePath) {
        maybe_pushCfFrame_worker(pageContext, sourceFilePath);
    }
    
    private DebugFrame maybe_pushCfFrame_worker(PageContext pageContext, String sourceFilePath) {
        Thread currentThread = Thread.currentThread();

        ArrayList<DebugFrame> stack = cfStackByThread.get(currentThread);

        // The null case means "fresh stack", this is the first frame
        // Frame length shouldn't ever be zero, we should tear it down when it hits zero
        if (stack == null || stack.size() == 0) {
            ArrayList<DebugFrame> list = new ArrayList<>();
            cfStackByThread.put(currentThread, list);
            stack = list;

            pageContextByThread.put(currentThread, new WeakReference<>(pageContext));
        }

        final int depth = stack.size(); // first frame is frame 0, and prior to pushing the first frame the stack is length 0; next frame is frame 1, and prior to pushing it the stack is of length 1, ...
        
        final DebugFrame frame = DebugFrame.makeFrame(sourceFilePath, depth, valTracker, pageContext);

        stack.add(frame);

        // if (stepRequestByThread.containsKey(currentThread)) {
        //     System.out.println("pushed frame during active step request:");
        //     System.out.println("  " + frame.getName() + " @ " + frame.getSourceFilePath() + ":" + frame.getLine());
        // }

        frameByFrameID.put(frame.getId(), frame);

        return frame;
    }

    public void pushCfFunctionDefaultValueInitializationFrame(lucee.runtime.PageContext pageContext, String sourceFilePath) {
        DebugFrame frame = maybe_pushCfFrame_worker(pageContext, sourceFilePath);
        if (frame instanceof Frame) {
            ((Frame)frame).isUdfDefaultValueInitFrame = true;
        }
    }

    public void popCfFrame() {
        Thread currentThread = Thread.currentThread();
        ArrayList<DebugFrame> maybeNull_frameListing = cfStackByThread.get(currentThread);

        if (maybeNull_frameListing == null) {
            // error case, maybe throw
            // we should not be popping from a non-existent thing
            return;
        }

        DebugFrame poppedFrame = null;

        if (maybeNull_frameListing.isEmpty()) {
            System.out.println("Popping from an empty stack?");
            System.exit(1);
        }
        else {
            poppedFrame = maybeNull_frameListing.remove(maybeNull_frameListing.size() - 1);
            frameByFrameID.remove(poppedFrame.getId());
        }

        if (maybeNull_frameListing.size() == 0) {
            // we popped the last frame, so we destroy the whole stack
            cfStackByThread.remove(currentThread);
            pageContextByThread.remove(currentThread);
            
            // TODO: cancel any current thread step requests here (e.g. a "step over" on last line of last frame in current request)
        }
    }

    public String getSourcePathForVariablesRef(int variablesRef) {
        return valTracker
            .maybeGetFromId(variablesRef)
            .map(taggedObj -> CfValueDebuggerBridge.getSourcePath(taggedObj.obj))
            .orElseGet(() -> null);
    }
}
