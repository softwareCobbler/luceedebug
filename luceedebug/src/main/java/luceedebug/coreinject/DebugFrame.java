package luceedebug.coreinject;

import lucee.runtime.PageContext;
import lucee.runtime.PageContextImpl;
import lucee.runtime.type.scope.LocalNotSupportedScope;
import lucee.runtime.type.scope.Scope;

import lucee.runtime.type.Collection;
import lucee.runtime.type.Collection.Key;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import luceedebug.*;

public class DebugFrame implements IDebugFrame {
    static private AtomicLong nextId = new AtomicLong(0);

    private ValTracker valTracker;
    private RefTracker<CfEntityRef> refTracker;

    final private Scopes rawScopesRef_;
    final private String sourceFilePath;
    final private long id;
    final private String name;
    final private int depth; // 0 is first frame in stack, 1 is next, ...
    private int line = 0; // initially unknown, until first step notification

    public String getSourceFilePath() { return sourceFilePath; };
    public long getId() { return id; }
    public String getName() { return name; }
    public int getDepth() { return depth; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    // lazy initialized on request for scopes
    // This is "scopes, wrapped with trackable IDs, which are expensive to create and cleanup"
    private LinkedHashMap<String, CfEntityRef> scopes_ = null;

    // hold strong refs to scopes, because PageContext will swap them out as frames change (variables, local, this)
    // (application, server and etc. maybe could be held as globals)
    // We don't want to construct tracked refs to them until a debugger asks for them, because it is expensive
    // to create and clean up references for every pushed frame, especially if that frame isn't ever inspected in a debugger.
    // This should be valid for the entirety of the frame, and should the frame should be always be disposed of at the end of the actual cf frame.
    static class Scopes {
        final lucee.runtime.type.scope.Scope application;
        final lucee.runtime.type.scope.Scope arguments;
        final lucee.runtime.type.scope.Scope form;
        final lucee.runtime.type.scope.Scope local;
        final lucee.runtime.type.scope.Scope request;
        final lucee.runtime.type.scope.Scope session;
        final lucee.runtime.type.scope.Scope server;
        final lucee.runtime.type.scope.Scope url;
        final lucee.runtime.type.scope.Scope variables;

        Scopes(PageContext pageContext) {
            this.application = getScopeOr(() -> pageContext.applicationScope());
            this.arguments   = getScopeOr(() -> pageContext.argumentsScope());
            this.form        = getScopeOr(() -> pageContext.formScope());
            this.local       = getScopeOr(() -> pageContext.localScope());
            this.request     = getScopeOr(() -> pageContext.requestScope());
            this.session     = getScopeOr(() -> pageContext.sessionScope());
            this.server      = getScopeOr(() -> pageContext.serverScope());
            this.url         = getScopeOr(() -> pageContext.urlScope());
            this.variables   = getScopeOr(() -> pageContext.variablesScope());
        }

        interface SupplierOrNull<T> {
            T get() throws Throwable;
        }

        // some scope getters throw or return garbage scopes; if we can't get a scope for some reason, we should investigate,
        // but we can keep running, just with less information (can't see that scope in the debugger)
        private <T> T getScopeOr(SupplierOrNull<T> f) {
            try {
                final var v = f.get();
                if (v instanceof LocalNotSupportedScope) {
                    return null;
                }
                return v;
            }
            catch(Throwable e) {
                return null;
            }
        }
    }

    public DebugFrame(String sourceFilePath, int depth, ValTracker valTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext) {
        this(sourceFilePath, depth, valTracker, refTracker, pageContext, DebugFrame.tryGetFrameName(pageContext));
    }

    public DebugFrame(String sourceFilePath, int depth, ValTracker varTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext, String name) {
        this.rawScopesRef_ = new Scopes(pageContext);
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

    private void putScopeRefIfNonNull(String name, lucee.runtime.type.scope.Scope scope) {
        if (scope != null) {
            scopes_.put(name, CfEntityRef.freshRef(valTracker, refTracker, name, scope));
        }
    }

    private void lazyInitScopeRefs() {
        if (scopes_ != null) {
            // already init'd
            return;
        }

        scopes_ = new LinkedHashMap<>();
        putScopeRefIfNonNull("application", rawScopesRef_.application);
        putScopeRefIfNonNull("arguments", rawScopesRef_.arguments);
        putScopeRefIfNonNull("form", rawScopesRef_.form);
        putScopeRefIfNonNull("local", rawScopesRef_.local);
        putScopeRefIfNonNull("request", rawScopesRef_.request);
        putScopeRefIfNonNull("session", rawScopesRef_.session);
        putScopeRefIfNonNull("server", rawScopesRef_.server);
        putScopeRefIfNonNull("url", rawScopesRef_.url);
        putScopeRefIfNonNull("variables", rawScopesRef_.variables);
    }



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
}
