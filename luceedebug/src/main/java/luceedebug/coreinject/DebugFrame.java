package luceedebug.coreinject;

import lucee.runtime.PageContext;
import lucee.runtime.PageContextImpl;
import lucee.runtime.type.scope.LocalNotSupportedScope;
import lucee.runtime.type.scope.Scope;

import lucee.runtime.type.Collection;
import lucee.runtime.type.Collection.Key;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import luceedebug.*;

public class DebugFrame implements IDebugFrame {
    static private AtomicLong nextId = new AtomicLong(0);

    private ValTracker valTracker;
    private RefTracker<CfEntityRef> refTracker;

    /** native accesses */
    final private String sourceFilePath;
    /** native accesses */
    final private long id;
    /** native accesses */
    final private String name;
    /** native accesses */
    final private int depth; // 0 is first frame in stack, 1 is next, ...
    /** native accesses */
    private int line = 0; // unknown until notified by native

    public String getSourceFilePath() { return sourceFilePath; };
    public long getId() { return id; }
    public String getName() { return name; }
    public int getDepth() { return depth; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    private HashMap<String, CfEntityRef> scopes = new HashMap<>();

    public DebugFrame(String sourceFilePath, int depth, ValTracker valTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext) {
        this(sourceFilePath, depth, valTracker, refTracker, pageContext, DebugFrame.tryGetFrameName(pageContext));
    }

    public DebugFrame(String sourceFilePath, int depth, ValTracker varTracker, RefTracker<CfEntityRef> refTracker, PageContext pageContext, String name) {
        this.sourceFilePath = sourceFilePath;
        this.valTracker = varTracker;
        this.refTracker = refTracker;
        this.id = nextId.incrementAndGet();
        this.name = name;
        this.depth = depth;
        pushScopes(pageContext);
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

    private void pushScopes(PageContext pageContext) {
        maybePushScope("variables", pageContext.variablesScope());
        maybePushScope("arguments", pageContext.argumentsScope());
        maybePushScope("local", pageContext.localScope());
    }

    private void maybePushScope(String name, Scope scope) {
        if (scope == null || scope instanceof LocalNotSupportedScope) {
            return;
        }

        scopes.put(name, CfEntityRef.freshRef(valTracker, refTracker, name, scope));
    }

    public IDebugEntity[] getScopes() {
        IDebugEntity[] result = new DebugEntity[scopes.size()];
        int i = 0;
        for (CfEntityRef entityRef : scopes.values()) {
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
