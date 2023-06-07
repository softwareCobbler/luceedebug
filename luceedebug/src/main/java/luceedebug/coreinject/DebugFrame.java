package luceedebug.coreinject;

import lucee.runtime.PageContext;
import lucee.runtime.PageContextImpl;
import lucee.runtime.type.scope.LocalNotSupportedScope;
import lucee.runtime.type.scope.Scope;

import lucee.runtime.type.Collection;
import lucee.runtime.type.Collection.Key;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.MapMaker;

import luceedebug.*;

public class DebugFrame implements IDebugFrame {
    static private AtomicLong nextId = new AtomicLong(0);

    private ValTracker valTracker;
    private RefTracker<CfEntityRef> refTracker;

    final private FrameContext frameContext_;
    final private String sourceFilePath;
    final private long id;
    final private String name;
    final private int depth; // 0 is first frame in stack, 1 is next, ...
    private int line = 0; // initially unknown, until first step notification
    
    /**
     * True if this cf frame's actual java method is "udfDefaultValue", see lucee source
     * This should be final and init'd via constructor but it's not a pressing issue.
     */
    public boolean isUdfDefaultValueInitFrame = false;

    public String getSourceFilePath() { return sourceFilePath; };
    public long getId() { return id; }
    public String getName() { return name; }
    public int getDepth() { return depth; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    // lazy initialized on request for scopes
    // This is "scopes, wrapped with trackable IDs, which are expensive to create and cleanup"
    private LinkedHashMap<String, CfEntityRef> scopes_ = null;

    // the results of evaluating complex expressions need to be kept alive for the entirety of the frame
    // these should be made gc'able when this frame is collected
    // We might want to place these results somewhere that is kept alive for the whole request?
    private ArrayList<CfEntityRef> refsToKeepAlive_ = new ArrayList<>();

    // hold strong refs to scopes, because PageContext will swap them out as frames change (variables, local, this)
    // (application, server and etc. maybe could be held as globals)
    // We don't want to construct tracked refs to them until a debugger asks for them, because it is expensive
    // to create and clean up references for every pushed frame, especially if that frame isn't ever inspected in a debugger.
    // This should be valid for the entirety of the frame, and should the frame should be always be disposed of at the end of the actual cf frame.
    //
    // lifetime: we shouldn't hold onto a frame longer than the engine holds onto the "frame"
    // (where frame there is in air quotes because the engine doesn't track explicit frames)
    // We want to not make this un-GC'able at the time the engine assumes it's GC'able.
    // This should be doable by virtue of our frames being popped and released immediately before
    // the engine is truly do with its "frame". Fallback here would be use a WeakRef<> but it doesn't
    // seem necessary.
    //
    static class FrameContext {
        final PageContext pageContext;

        final lucee.runtime.type.scope.Scope application;
        final lucee.runtime.type.scope.Argument arguments;
        final lucee.runtime.type.scope.Scope form;
        final lucee.runtime.type.scope.Local local;
        final lucee.runtime.type.scope.Scope request;
        final lucee.runtime.type.scope.Scope session;
        final lucee.runtime.type.scope.Scope server;
        final lucee.runtime.type.scope.Scope url;
        final lucee.runtime.type.scope.Variables variables;

        static private final ConcurrentMap<PageContext, Object> activeFrameLockByPageContext = new MapMaker()
            .weakKeys()
            .makeMap();

        //
        // Note that some of these `getScopeOrNull` calls need additional guards, to prevent from throwing
        // expensive exceptions on literally every frame, e.g. if a scope is disabled by the engine and trying to touch it
        // throws an ExpressionException.
        //
        FrameContext(PageContext pageContext) {
            this.pageContext = pageContext;
            this.application = getScopeOrNull(() -> pageContext.applicationScope());
            this.arguments   = getScopeOrNull(() -> pageContext.argumentsScope());
            this.form        = getScopeOrNull(() -> pageContext.formScope());
            this.local       = getScopeOrNull(() -> pageContext.localScope());
            this.request     = getScopeOrNull(() -> pageContext.requestScope());
            this.session     = getScopeOrNull(() -> pageContext.getApplicationContext().isSetSessionManagement() ? pageContext.sessionScope() : null);
            this.server      = getScopeOrNull(() -> pageContext.serverScope());
            this.url         = getScopeOrNull(() -> pageContext.urlScope());
            this.variables   = getScopeOrNull(() -> pageContext.variablesScope());
        }

        interface SupplierOrNull<T> {
            T get() throws Throwable;
        }

        // sometimes trying to get a scope throws, in which case we get null
        // scopes that are "garbage" scopes ("LocalNotSupportedScope") should be filtered away elsewhere
        // we especially are interested in when we swap out scopes during expression evaluation that we restore the scopes
        // as they were prior to; which might be troublesome if "getting a scope throws so we return null, but it doesn't make sense to restore the scope to null"
        private <T> T getScopeOrNull(SupplierOrNull<T> f) {
            try {
                return f.get();
            }
            catch(Throwable e) {
                return null;
            }
        }

        // if we're mutating some page context's frame information in place, we should only do it on one thread at a time.
        static private <T> T withFrameLock(PageContext pageContext, Supplier<T> f) {
            // lazy create the lock if it doesn't exist yet
            synchronized(activeFrameLockByPageContext.computeIfAbsent(pageContext, (_x) -> new Object())) {
                return f.get();
            }
        }

        /**
         * "real" frames are swapped-out in place inside the engine, so there's just one page context that has its 
         * current context mutated on function enter/exit. To evaluate an expression inside of some frame context,
         * we need to replace the page context's relevant scopes with the ones for "this" frame, perform the evaluation,
         * and then restore everything we swapped out.
         */
        public <T> T doWorkInThisFrame(Supplier<T> f)  {
            return withFrameLock(pageContext, () -> {
                final var saved_argumentsScope = getScopeOrNull(() -> pageContext.argumentsScope());
                final var saved_localScope = getScopeOrNull(() -> pageContext.localScope());
                final var saved_variablesScope = getScopeOrNull(() -> pageContext.variablesScope());
                try {
                    pageContext.setFunctionScopes(local, arguments);
                    pageContext.setVariablesScope(variables);
                    return f.get();
                }
                finally {
                    pageContext.setVariablesScope(saved_variablesScope);
                    pageContext.setFunctionScopes(saved_localScope, saved_argumentsScope);
                }
            });
        }
    }

    public DebugFrame(String sourceFilePath, int depth, ValTracker valTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext) {
        this(sourceFilePath, depth, valTracker, refTracker, pageContext, DebugFrame.tryGetFrameName(pageContext));
    }

    public DebugFrame(String sourceFilePath, int depth, ValTracker varTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext, String name) {
        this.frameContext_ = new FrameContext(pageContext);
        this.sourceFilePath = sourceFilePath;
        this.valTracker = varTracker;
        this.refTracker = refTracker;
        this.id = nextId.incrementAndGet();
        this.name = name;
        this.depth = depth;
    }

    private static String tryGetFrameName(PageContext pageContext) {
        String frameName = "??";
        try {
            final PageContextImpl pageContextImpl = (PageContextImpl)pageContext;
            final Collection.Key key = pageContextImpl.getActiveUDFCalledName();
            if (key != null) {
                frameName = key.getString();
            }
        }
        catch (Throwable e) {
            // discard, cast was bad for some reason?
        }
        return frameName;
    }

    private void checkedPutScopeRef(String name, lucee.runtime.type.scope.Scope scope) {

        if (scope != null && !(scope instanceof LocalNotSupportedScope)) {
            scopes_.put(name, CfEntityRef.freshRef(valTracker, refTracker, name, scope));
        }
    }

    private void lazyInitScopeRefs() {
        if (scopes_ != null) {
            // already init'd
            return;
        }

        scopes_ = new LinkedHashMap<>();
        checkedPutScopeRef("application", frameContext_.application);
        checkedPutScopeRef("arguments", frameContext_.arguments);
        checkedPutScopeRef("form", frameContext_.form);
        checkedPutScopeRef("local", frameContext_.local);
        checkedPutScopeRef("request", frameContext_.request);
        checkedPutScopeRef("session", frameContext_.session);
        checkedPutScopeRef("server", frameContext_.server);
        checkedPutScopeRef("url", frameContext_.url);
        checkedPutScopeRef("variables", frameContext_.variables);
    }

    /**
     * for debugger-internal use, e.g. in watch expressions
     */
    public FrameContext getFrameContext() {
        return frameContext_;
    }

    /**
     * for direct DAP use
     */
    public IDebugEntity[] getScopes() {
        lazyInitScopeRefs();
        IDebugEntity[] result = new DebugEntity[scopes_.size()];
        int i = 0;
        for (CfEntityRef entityRef : scopes_.values()) {
            var entity = new DebugEntity();
            entity.name = entityRef.name;
            entity.namedVariables = entityRef.getNamedVariablesCount();
            entity.indexedVariables = entityRef.getIndexedVariablesCount();
            entity.expensive = true;
            entity.variablesReference = entityRef.getId();
            result[i] = entity;
            i += 1;
        }
        return result;
    }

    CfEntityRef trackEvalResult(Object obj) {
        // Strings, numbers, etc. should be skipped over?
        final var ref = CfEntityRef.freshRef(valTracker, refTracker, "$eval", obj);
        refsToKeepAlive_.add(ref);
        return ref;
    }
}
